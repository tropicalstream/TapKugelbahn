package com.tapkugelbahn.engine

import com.tapkugelbahn.SettingsStore
import kotlin.math.abs
import kotlin.math.sqrt

/** Host callbacks (sounds run outside the GL thread). */
interface GameHost {
    fun sfx(id: Int, pitch: Float, vol: Float)
    fun rolling(on: Boolean, rate: Float, vol: Float)
}

enum class GameState { TITLE, RUN, COMPLETE, FINALE }

/** One ball in the machine. */
class Ball(
    var s: Float,
    var v: Float,
    val hue: Float,
    val dropTime: Float
) {
    var dropping = true          // free-falling into the intake
    var dropY = DROP_H
    var pauseT = 0f              // held by a pause mechanism
    var roll = 0f                // rolling angle for the wireframe sphere
    var laps = 0
    var finished = false         // crossed the finish line at least once
    var finishTime = -1f
    var touched = false          // bumped another ball — out of the objective
    var lastZone: Zone? = null
    var pendingV = 1f
    val pos = FloatArray(3)
}

// Swipe directions, classified on finger-up from the dominant axis.
const val SW_FWD = 0
const val SW_BACK = 1
const val SW_UP = 2
const val SW_DOWN = 3

/**
 * The rules: TAP drops a ball from five diameters above the intake. Balls keep
 * looping via the elevator forever; DOUBLE-TAP advances once the level is
 * complete. SWIPE walks the vantages, forward or back.
 */
class Game(private val store: SettingsStore, private val host: GameHost) {

    @Volatile var state = GameState.TITLE; private set
    var level = 1; private set
    var machine: MachineModel = Machine.build(1); private set
    var machineRevision = 1; private set   // renderer re-uploads static geometry on change

    val balls = ArrayList<Ball>()
    var time = 0f; private set
    var view = 0                 // 0 auto-director · 1 outside · 2 inside · 3 side · 4 follow
    var viewFlash = 0f           // HUD name flash after a swipe
    var touchFlash = 0f
    var winFlash = 0f
    var completed = false; private set

    private var physAcc = 0f
    private val tmp = FloatArray(3)

    companion object {
        const val MAX_BALLS = 6
        val VIEW_NAMES = arrayOf("AUTO", "OUTSIDE", "INSIDE", "SIDE VIEW", "FOLLOW")
        private const val PHYS_DT = 1f / 120f
        // Tuned for the leisurely, inevitable roll of a real Kugelbahn:
        // ~1.2-1.6 m/s cruise, so level 1's course takes about a minute.
        private const val G_SCALE = 3.4f     // stylized gravity along the slope
        private const val DRAG = 0.38f
        private const val MIN_V = 0.35f
        private const val MAX_V = 4.5f
    }

    fun boot() { loadLevel(store.reached.coerceIn(1, Machine.LEVELS), toTitle = true) }

    private fun loadLevel(l: Int, toTitle: Boolean = false) {
        level = l
        machine = Machine.build(l)
        machineRevision++
        balls.clear()
        completed = false
        state = if (toTitle) GameState.TITLE else GameState.RUN
        if (toTitle) {
            // attract mode: two ambient balls so the sculpture runs on the title
            balls.add(Ball(machine.length * 0.15f, 1.4f, 0.55f, -10f).also { it.dropping = false })
            balls.add(Ball(machine.length * 0.6f, 1.4f, 0.08f, -10f).also { it.dropping = false })
        }
    }

    // ------------------------------------------------------------- input

    fun tap() {
        when (state) {
            GameState.TITLE -> { balls.clear(); state = GameState.RUN; host.sfx(S_DROP, 1f, 0.8f) }
            GameState.RUN, GameState.COMPLETE, GameState.FINALE -> {
                if (balls.size >= MAX_BALLS) { host.sfx(S_CLACK, 0.4f, 0.5f); return }
                // tight geometry: never spawn a ball inside the previous one
                if (balls.any { it.dropping || (it.s < BALL_R * 4f && it.laps == 0) }) {
                    host.sfx(S_CLACK, 0.5f, 0.4f); return
                }
                balls.add(Ball(0f, 0f, ((balls.size * 0.17f + time * 0.05f) % 1f), time))
                host.sfx(S_DROP, 1.1f, 0.9f)
            }
        }
    }

    fun doubleTap() {
        when (state) {
            GameState.TITLE -> { balls.clear(); state = GameState.RUN }
            GameState.COMPLETE -> {
                if (level < Machine.LEVELS) { loadLevel(level + 1); host.sfx(S_DING, 1.4f, 1f) }
                else { state = GameState.FINALE; host.sfx(S_DING, 0.8f, 1f) }
            }
            GameState.FINALE -> loadLevel(1, toTitle = true)
            GameState.RUN -> {}
        }
    }

    /**
     * One discrete swipe = one step, classified on finger-up (the suite's
     * temple-pad convention). Forward/back walk the vantages in both
     * directions — until now every swipe went forward only, so getting back
     * to a view you had just passed meant cycling through all five.
     *
     * Vertical currently mirrors horizontal so nothing regresses; the edit
     * mode that is coming will reinterpret up/down against the slot palette.
     */
    fun swipe(dir: Int) {
        val step = if (dir == SW_BACK || dir == SW_DOWN) -1 else 1
        // On the title screen a swipe picks the machine rather than the camera,
        // and the machine REBUILDS as you choose — so what is winding away
        // behind the card is the one you are about to play, not a mock-up.
        if (state == GameState.TITLE) {
            val next = ((level - 1 + step + Machine.LEVELS) % Machine.LEVELS) + 1
            if (next != level) {
                loadLevel(next, toTitle = true)
                host.sfx(S_DING, 1.15f + 0.12f * next, 0.75f)
            }
            return
        }
        view = (view + step + VIEW_NAMES.size) % VIEW_NAMES.size
        viewFlash = 1.6f
        host.sfx(S_CLACK, 1.8f, 0.35f)
    }

    // ------------------------------------------------------------- update

    fun update(dt: Float) {
        time += dt
        viewFlash = (viewFlash - dt).coerceAtLeast(0f)
        touchFlash = (touchFlash - dt).coerceAtLeast(0f)
        winFlash = (winFlash - dt).coerceAtLeast(0f)
        physAcc += dt
        while (physAcc >= PHYS_DT) { step(PHYS_DT); physAcc -= PHYS_DT }

        // rolling bed: as long as ANY ball is rolling, the machine is heard —
        // rate and volume ride the fastest one
        val rolling = balls.filter { !it.dropping && it.pauseT <= 0f }.maxByOrNull { it.v }
        if (rolling != null) {
            host.rolling(true,
                (0.7f + rolling.v * 0.18f).coerceIn(0.6f, 1.9f),
                (0.45f + rolling.v * 0.12f).coerceAtMost(1f))
        } else host.rolling(false, 1f, 0f)
    }

    private fun step(dt: Float) {
        val m = machine
        for (b in balls) {
            if (b.dropping) {
                b.v += 9.8f * dt
                b.dropY -= b.v * dt
                if (b.dropY <= 0f) {
                    b.dropping = false
                    b.v = sqrt(2f * 9.8f * DROP_H) * 0.55f  // keep the landing lively but tame
                    host.sfx(S_CLACK, 1.1f, 0.8f)
                }
                m.pos(0f, b.pos); b.pos[1] += b.dropY.coerceAtLeast(0f)
                continue
            }
            if (b.pauseT > 0f) { b.pauseT -= dt; if (b.pauseT <= 0f) b.v = b.pendingV; m.pos(b.s, b.pos); continue }

            val z = m.zoneAt(b.s)
            when (z?.type) {
                Z_LIFT, Z_FERRIS -> b.v = z.speed
                Z_BOOST -> if (b.v < z.speed) b.v = z.speed
                Z_PAUSE -> {
                    if (b.lastZone !== z) {   // entering: stop, then eject
                        b.lastZone = z
                        b.pauseT = z.pause
                        b.pendingV = z.speed
                        b.v = 0f
                        m.pos(b.s, b.pos)
                        continue
                    }
                }
                else -> {
                    b.lastZone = null
                    val slope = m.slope(b.s)
                    b.v += (-G_SCALE * slope - DRAG * b.v) * dt
                    b.v = b.v.coerceIn(MIN_V, MAX_V)
                }
            }
            val prev = b.s
            b.s += b.v * dt
            b.roll += b.v * dt / BALL_R

            // notes crossed this step
            for (nte in m.notes) {
                if (crossed(prev, b.s, nte.s, m.length)) host.sfx(nte.sound, nte.pitch, nte.vol)
            }
            // finish line
            if (crossed(prev, b.s, m.finishS, m.length)) {
                b.laps++
                if (!b.finished) { b.finished = true; b.finishTime = time; checkObjective(b) }
            }
            if (b.s >= m.length) { b.s -= m.length }
            m.pos(b.s, b.pos)
        }
        // ball-ball touch: same-loop arc distance under one diameter
        for (i in balls.indices) for (j in i + 1 until balls.size) {
            val a = balls[i]; val c = balls[j]
            if (a.dropping || c.dropping) continue
            var d = abs(a.s - c.s)
            d = minOf(d, machine.length - d)
            if (d < BALL_R * 2f) {
                if (!a.touched || !c.touched) {
                    host.sfx(S_CLACK, 0.95f, 1f)
                    touchFlash = 1.8f
                }
                a.touched = true; c.touched = true
                // gentle separation so they don't buzz endlessly
                if (a.s < c.s) { val push = (BALL_R * 2f - d) / 2; a.s -= push; c.s += push }
                else { val push = (BALL_R * 2f - d) / 2; a.s += push; c.s -= push }
            }
        }
    }

    /** The level is complete the moment the player's ball finishes its round —
     *  the machine is the show; no further objective stands in the way. */
    private fun checkObjective(b: Ball) {
        if (state != GameState.RUN || b.dropTime < 0f) return
        completed = true
        state = GameState.COMPLETE
        winFlash = 3f
        store.reached = maxOf(store.reached, level + 1)
        host.sfx(S_XYLO, 1.5f, 1f); host.sfx(S_DING, 1.2f, 1f)
    }

    private fun crossed(s0: Float, s1: Float, mark: Float, len: Float): Boolean {
        if (s0 <= s1) return mark in s0..s1 || (mark + len) in s0..s1
        return false
    }

    /** The first-dropped player ball (the camera's subject), else any ball. */
    fun leadBall(): Ball? =
        balls.filter { it.dropTime >= 0f }.minByOrNull { it.dropTime } ?: balls.firstOrNull()

    fun activeCount() = balls.size
}
