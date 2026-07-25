package com.tapkugelbahn.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.tapkugelbahn.engine.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Additive glowing vector lines on black (black = transparent on the
 * waveguide). The machine's sculpture is baked once into a static VBO per
 * level; balls, animated mechanisms and the HUD rebuild each frame. The
 * camera always tracks the first ball from a visually satisfying distance —
 * in AUTO the director also cuts between vantages on its own.
 */
class GLRenderer(private val game: Game) : GLSurfaceView.Renderer {

    var sbs = false

    private var program = 0
    private var aPos = 0
    private var aColor = 0
    private var uMVP = 0
    private var uPointSize = 0
    private var uPoint = 0

    private var width = 1
    private var height = 1
    private var lastNanos = 0L

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val ortho = FloatArray(16)

    private val dyn = Batch(26000)
    private val fx = Batch(1200)
    private val hud = Batch(5000)

    // static machine geometry (VBO)
    private var staticVbo = 0
    private var staticCount = 0
    private var uploadedRevision = -1

    // solid mesh
    private var meshProgram = 0
    private var mPos = 0; private var mNrm = 0; private var mMat = 0
    private var mMVP = 0; private var mEye = 0
    private var meshVbo = 0
    private var meshUploaded = -1
    private var meshTrisDrawn = 0

    // camera state (smoothed)
    private val camPos = floatArrayOf(0f, 3f, -7f)
    private val camLook = floatArrayOf(0f, 2f, 0f)
    private var orbit = 0f
    private var autoSub = 1          // director's current vantage (1..4)
    private var autoTimer = 0f
    private var lastView = -1

    private val bp = FloatArray(3)
    private val bt = FloatArray(3)
    private val ballScratch = FloatArray(3)
    private val chunkOn = ArrayList<Boolean>()
    private var footprintsLogged = false
    private val ROMAN = arrayOf("I", "II", "III")

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram(VERT, FRAG)
        meshProgram = buildProgram(MESH_VERT, MESH_FRAG)
        mPos = GLES30.glGetAttribLocation(meshProgram, "aPos")
        mNrm = GLES30.glGetAttribLocation(meshProgram, "aNrm")
        mMat = GLES30.glGetAttribLocation(meshProgram, "aMat")
        mMVP = GLES30.glGetUniformLocation(meshProgram, "uMVP")
        mEye = GLES30.glGetUniformLocation(meshProgram, "uEye")
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aColor = GLES30.glGetAttribLocation(program, "aColor")
        uMVP = GLES30.glGetUniformLocation(program, "uMVP")
        uPointSize = GLES30.glGetUniformLocation(program, "uPointSize")
        uPoint = GLES30.glGetUniformLocation(program, "uPoint")
        // Depth testing is on from here, but the glowing line-work never WRITES
        // depth — it only tests. With nothing solid in the scene yet every line
        // passes, so the picture is unchanged; when the shaded mechanisms
        // arrive they will write depth in an earlier pass and the neon will
        // correctly disappear behind them instead of summing through.
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        lastNanos = 0L
        uploadedRevision = -1   // context loss: re-upload
        staticVbo = 0
        meshUploaded = -1
        meshVbo = 0
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        Matrix.orthoM(ortho, 0, 0f, 640f, 480f, 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now
        game.update(dt)

        if (game.machineRevision != uploadedRevision) uploadStatic()
        if (game.machineRevision != meshUploaded) uploadMesh()

        updateCamera(dt)
        buildDynamic(dt)
        buildHud()

        // ONE clear for the whole surface, deliberately outside the per-eye
        // loop below. glClear ignores glViewport — it honours only the scissor
        // box, which this renderer never enables — so a clear issued per eye
        // would wipe the entire framebuffer and the left eye would go black.
        // The two eye viewports are disjoint rectangles, so neither can ever
        // sample the other's depth and a per-eye depth clear is unnecessary.
        // The mask has to be reopened first or the depth clear is masked out.
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(program)

        Matrix.setLookAtM(view, 0,
            camPos[0], camPos[1], camPos[2],
            camLook[0], camLook[1], camLook[2], 0f, 1f, 0f)

        val eyes = if (sbs) 2 else 1
        val vw = if (sbs) width / 2 else width
        val aspect = vw.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 56f, aspect, 0.06f, 60f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        for (e in 0 until eyes) {
            GLES30.glViewport(e * vw, 0, vw, height)
            drawMesh()
            GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
            GLES30.glUniform1f(uPoint, 0f)
            drawStatic()
            dyn.draw(GLES30.GL_LINES)
            GLES30.glUniform1f(uPoint, 1f)
            GLES30.glUniform1f(uPointSize, 10f); fx.draw(GLES30.GL_POINTS)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, ortho, 0)
            GLES30.glUniform1f(uPoint, 0f)
            hud.draw(GLES30.GL_LINES)
        }
    }

    // ---------------------------------------------------------- static VBO

    private fun uploadStatic() {
        val m = game.machine
        if (staticVbo == 0) {
            val ids = IntArray(1); GLES30.glGenBuffers(1, ids, 0); staticVbo = ids[0]
        }
        val fb = ByteBuffer.allocateDirect(m.staticLines.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(m.staticLines); fb.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, staticVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.staticLines.size * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        staticCount = m.staticCount
        uploadedRevision = game.machineRevision
        Log.i("TapKugelbahn", "machine L${game.level}: ${m.length.toInt()}m track, $staticCount static verts")
        if (!footprintsLogged) {
            footprintsLogged = true
            for (line in (Steps.levelReport() + Steps.report()).trim().split("\n")) Log.i("TapKugelbahn", line)
        }
    }

    private fun drawStatic() {
        if (staticCount == 0) return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, staticVbo)
        GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 28, 0)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aColor, 4, GLES30.GL_FLOAT, false, 28, 12)
        GLES30.glEnableVertexAttribArray(aColor)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, staticCount)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    // ---------------------------------------------------------- solid mesh

    private fun uploadMesh() {
        val m = game.machine
        if (meshVbo == 0) { val ids = IntArray(1); GLES30.glGenBuffers(1, ids, 0); meshVbo = ids[0] }
        val fb = ByteBuffer.allocateDirect(m.meshVerts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(m.meshVerts); fb.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.meshVerts.size * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        meshUploaded = game.machineRevision
        Log.i("TapKugelbahn", "mesh: ${m.meshVerts.size / 7} verts in ${m.meshChunks.size} chunks")
    }

    /**
     * Opaque pass: solid track near the eye, neon everywhere else.
     *
     * The swap needs no bookkeeping on the line side. The tubes are swept from
     * exactly the same centres the rail LINES are drawn along, so a tube sits
     * over its own line; the mesh writes depth and the lines only test, which
     * means the neon vanishes precisely where solid geometry replaced it and
     * survives everywhere else. Chunks flip in and out on a hysteresis band so
     * a camera hovering at the boundary cannot make the track flicker.
     */
    private fun drawMesh() {
        val m = game.machine
        if (m.meshChunks.isEmpty()) return
        GLES30.glUseProgram(meshProgram)
        GLES30.glUniformMatrix4fv(mMVP, 1, false, mvp, 0)
        GLES30.glUniform3f(mEye, camPos[0], camPos[1], camPos[2])
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshVbo)
        GLES30.glVertexAttribPointer(mPos, 3, GLES30.GL_FLOAT, false, 28, 0)
        GLES30.glEnableVertexAttribArray(mPos)
        GLES30.glVertexAttribPointer(mNrm, 3, GLES30.GL_FLOAT, false, 28, 12)
        GLES30.glEnableVertexAttribArray(mNrm)
        GLES30.glVertexAttribPointer(mMat, 1, GLES30.GL_FLOAT, false, 28, 24)
        GLES30.glEnableVertexAttribArray(mMat)

        var verts = 0
        for (i in m.meshChunks.indices) {
            val c = m.meshChunks[i]
            val d = hypot(hypot(camPos[0] - c.cx, camPos[1] - c.cy), camPos[2] - c.cz) - c.rad
            val was = chunkOn.getOrElse(i) { false }
            val on = if (was) d < MESH_OUT else d < MESH_IN
            while (chunkOn.size <= i) chunkOn.add(false)
            chunkOn[i] = on
            if (!on) continue
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, c.start, c.count)
            verts += c.count
        }
        meshTrisDrawn = verts / 3

        GLES30.glDisableVertexAttribArray(mPos)
        GLES30.glDisableVertexAttribArray(mNrm)
        GLES30.glDisableVertexAttribArray(mMat)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glUseProgram(program)
    }

    // ---------------------------------------------------------- camera

    private fun updateCamera(dt: Float) {
        val m = game.machine
        orbit += dt * 0.12f
        val lead = game.leadBall()

        // AUTO director: dwell on a vantage, then cut — always framing the ball.
        val mode: Int
        if (game.view == 0) {
            autoTimer -= dt
            if (autoTimer <= 0f) {
                autoTimer = 8f
                autoSub = when (autoSub) { 2 -> 4; 4 -> 3; 3 -> 1; else -> 2 }  // inside→follow→side→outside→…
            }
            mode = if (lead == null) 1 else autoSub
        } else mode = game.view

        var tx: Float; var ty: Float; var tz: Float          // target eye
        var lx: Float; var ly: Float; var lz: Float          // target look
        if (lead != null) { lead.let { m.pos(it.s, bp); bp[1] = it.pos[1]; bp[0] = it.pos[0]; bp[2] = it.pos[2] } }
        else { bp[0] = m.center[0]; bp[1] = m.center[1]; bp[2] = m.center[2] }

        when (mode) {
            2 -> { // INSIDE — the panoramic view from the cylinder's heart
                val az = atan2(bp[0], bp[2])
                tx = sin(az) * 0.001f; ty = bp[1] + 0.35f; tz = cos(az) * 0.001f
                lx = bp[0]; ly = bp[1]; lz = bp[2]
            }
            3 -> { // SIDE — zoomed side-on view of the ball
                if (lead != null) m.tangent(lead.s, bt) else { bt[0] = 1f; bt[1] = 0f; bt[2] = 0f }
                var sx = bt[2]; var sz = -bt[0]
                val sl = hypot(sx, sz).coerceAtLeast(0.01f); sx /= sl; sz /= sl
                // stand on the OUTSIDE of the cylinder so the wall never blocks
                val out = if (bp[0] * sx + bp[2] * sz > 0) 1f else -1f
                tx = bp[0] + sx * 1.35f * out; ty = bp[1] + 0.22f; tz = bp[2] + sz * 1.35f * out
                lx = bp[0]; ly = bp[1]; lz = bp[2]
            }
            4 -> { // FOLLOW — chase cam, ball reads about fist-sized
                if (lead != null) m.tangent(lead.s, bt) else { bt[0] = 0f; bt[1] = 0f; bt[2] = 1f }
                tx = bp[0] - bt[0] * 0.95f; ty = bp[1] + 0.34f - bt[1] * 0.3f; tz = bp[2] - bt[2] * 0.95f
                lx = bp[0] + bt[0] * 0.5f; ly = bp[1] + bt[1] * 0.2f; lz = bp[2] + bt[2] * 0.5f
            }
            else -> { // OUTSIDE — orbiting overview at a satisfying distance
                val az = if (lead != null) atan2(bp[0], bp[2]) + 0.55f + sin(orbit) * 0.15f else orbit
                val r = m.radius * 1.28f
                tx = sin(az) * r; ty = (bp[1] * 0.55f + m.center[1] * 0.45f) + 1.0f; tz = cos(az) * r
                lx = bp[0] * 0.7f + m.center[0] * 0.3f
                ly = bp[1] * 0.7f + m.center[1] * 0.3f
                lz = bp[2] * 0.7f + m.center[2] * 0.3f
            }
        }

        // The title screen gets its own framing: a slow wide orbit that holds
        // the WHOLE sculpture in shot, tilted down a little so the tower reads
        // as a tower. The in-game vantages all sit close to the ball, which is
        // right when you are watching a ball and wrong when you are choosing a
        // machine — the rails filled the frame and swamped the cards.
        if (game.state == GameState.TITLE) {
            val az = orbit * 0.42f
            val rr = m.radius * 2.15f + 1.4f
            tx = sin(az) * rr; ty = m.center[1] + m.radius * 0.55f; tz = cos(az) * rr
            lx = m.center[0]; ly = m.center[1] - m.radius * 0.06f; lz = m.center[2]
        }

        // critically-damped style smoothing; a manual swipe snaps most of the way
        val snap = game.view != lastView
        lastView = game.view
        val kp = if (snap) 0.75f else 1f - exp(-3.4f * dt)
        val kl = if (snap) 0.85f else 1f - exp(-5.5f * dt)
        camPos[0] += (tx - camPos[0]) * kp; camPos[1] += (ty - camPos[1]) * kp; camPos[2] += (tz - camPos[2]) * kp
        camLook[0] += (lx - camLook[0]) * kl; camLook[1] += (ly - camLook[1]) * kl; camLook[2] += (lz - camLook[2]) * kl
    }

    // ---------------------------------------------------------- dynamic scene

    private fun buildDynamic(dt: Float) {
        dyn.reset(); fx.reset()
        val m = game.machine
        val t = game.time

        // intake beacon: dashed drop guide + pulsing ring at the drop height
        val ix = m.intake[0]; val iy = m.intake[1]; val iz = m.intake[2]
        val pulse = 0.45f + 0.3f * sin(t * 3f)
        var yy = 0f
        while (yy < DROP_H) {
            dyn.line(ix, iy + yy, iz, ix, iy + minOf(yy + 0.06f, DROP_H), iz, 0.5f, 1f, 0.7f, 0.5f)
            yy += 0.14f
        }
        ringXZ(ix, iy + DROP_H, iz, BALL_R * 1.6f, 12, 0.5f, 1f, 0.7f, pulse)

        for (mech in m.mechs) drawMech(mech, dt)
        for (b in game.balls) drawBall(b)
    }

    /** The ball currently riding this mechanism's stretch of track, if any. */
    private fun ridingBall(mc: Mech): Ball? {
        if (mc.s1 <= mc.s0) return null
        return game.balls.firstOrNull { !it.dropping && it.s >= mc.s0 && it.s <= mc.s1 }
    }

    private fun drawMech(mc: Mech, dt: Float) {
        val fxd = sin(mc.yaw); val fzd = cos(mc.yaw)
        val lxd = cos(mc.yaw); val lzd = -sin(mc.yaw)
        val c = TrackBuilder.hsv(mc.hue)
        when (mc.type) {
            M_FERRIS -> {
                // A riding ball is CARRIED: the wheel turns with it so a
                // gondola stays underneath from the top all the way down.
                val rider = ridingBall(mc)
                if (rider != null) {
                    val th = ((rider.s - mc.s0) / mc.a).coerceIn(0f, PI.toFloat())
                    mc.phase += (th - mc.phase) * (1f - exp(-12f * dt))
                } else mc.phase += dt * 0.55f
                val r = mc.a
                ringPlane(mc.x, mc.y, mc.z, fxd, fzd, r, 20, c[0], c[1], c[2], 0.75f)
                ringPlane(mc.x, mc.y, mc.z, fxd, fzd, r * 0.12f, 8, c[0], c[1], c[2], 0.9f)
                for (k in 0 until 8) {
                    val a = mc.phase + k * PI.toFloat() / 4f
                    val px = mc.x + fxd * sin(a) * r; val py = mc.y + cos(a) * r; val pz = mc.z + fzd * sin(a) * r
                    dyn.line(mc.x, mc.y, mc.z, px, py, pz, c[0], c[1], c[2], 0.55f)
                    // gondola basket — the container the ball rides in
                    val gw = 0.16f; val gd = 0.15f
                    val bx0 = px - fxd * gw; val bz0 = pz - fzd * gw
                    val bx1 = px + fxd * gw; val bz1 = pz + fzd * gw
                    dyn.line(px, py, pz, bx0, py - 0.06f, bz0, 1f, 1f, 1f, 0.6f)
                    dyn.line(px, py, pz, bx1, py - 0.06f, bz1, 1f, 1f, 1f, 0.6f)
                    dyn.line(bx0, py - 0.06f, bz0, bx0, py - gd, bz0, 1f, 1f, 1f, 0.7f)
                    dyn.line(bx1, py - 0.06f, bz1, bx1, py - gd, bz1, 1f, 1f, 1f, 0.7f)
                    dyn.line(bx0, py - gd, bz0, bx1, py - gd, bz1, 1f, 1f, 1f, 0.7f)
                }
            }
            M_SCREW -> {
                mc.phase += dt * 2.4f
                val len = mc.a; val rise = mc.b
                val n = 30
                for (h in 0 until 2) {
                    var px = 0f; var py = 0f; var pz = 0f
                    for (i in 0..n) {
                        val u = i.toFloat() / n
                        val ang = mc.phase + h * PI.toFloat() + u * 5f * PI.toFloat()
                        val cxp = mc.x + fxd * len * u + lxd * sin(ang) * 0.19f
                        val cyp = mc.y + rise * u + cos(ang) * 0.19f + 0.05f
                        val czp = mc.z + fzd * len * u + lzd * sin(ang) * 0.19f
                        if (i > 0) dyn.line(px, py, pz, cxp, cyp, czp, c[0], c[1], c[2], 0.8f)
                        px = cxp; py = cyp; pz = czp
                    }
                }
            }
            M_TROMMEL -> {
                // Lock the cage to whatever is inside it. The ball's sway across
                // the barrel is three quarters of a turn, so matching that keeps
                // a bar under the ball the whole way through instead of letting
                // it cut across a drum turning at some unrelated rate. Idles at
                // its old speed when empty, so the machine still looks alive.
                val rider = ridingBall(mc)
                if (rider != null) {
                    val u = ((rider.s - mc.s0) / (mc.s1 - mc.s0)).coerceIn(0f, 1f)
                    mc.phase = u * 1.5f * PI.toFloat()
                } else mc.phase += dt * 1.5f
                val len = mc.a; val r = mc.b
                for (k in 0 until 8) {
                    val a = mc.phase + k * PI.toFloat() / 4f
                    val ox = lxd * sin(a) * r; val oy = cos(a) * r
                    val oz = lzd * sin(a) * r
                    dyn.line(mc.x + ox, mc.y + oy + 0.1f, mc.z + oz,
                        mc.x + fxd * len + ox, mc.y + oy + 0.1f, mc.z + fzd * len + oz, c[0], c[1], c[2], 0.5f)
                }
                ringT(mc.x, mc.y + 0.1f, mc.z, mc.yaw, r, 12, c[0], c[1], c[2], 0.7f)
                ringT(mc.x + fxd * len, mc.y + 0.1f, mc.z + fzd * len, mc.yaw, r, 12, c[0], c[1], c[2], 0.7f)
            }
            M_PROP -> {
                mc.phase += dt * 6.5f
                dyn.line(mc.x, mc.y - 0.5f, mc.z, mc.x, mc.y, mc.z, 0.6f, 0.7f, 0.8f, 0.6f)
                for (k in 0 until 3) {
                    val a = mc.phase + k * 2f * PI.toFloat() / 3f
                    dyn.line(mc.x, mc.y, mc.z,
                        mc.x + fxd * cos(a) * mc.a, mc.y + sin(a) * mc.a, mc.z + fzd * cos(a) * mc.a,
                        c[0], c[1], c[2], 0.85f)
                }
                fx.v(mc.x, mc.y, mc.z, 1f, 1f, 1f, 0.9f)
            }
            M_CRADLE -> {
                // Impact starts a REAL damped pendulum on the far ball:
                // θ(t) = A·sin(ωt)·e^(-λt), ω = √(g/L) — it swings out, falls
                // back, clacks, and rings down over a few diminishing arcs.
                val rider = ridingBall(mc)
                if (rider != null && game.time - mc.trigger > 2.0f) mc.trigger = game.time
                val topY = mc.y + 0.55f
                val len = 0.42f
                val omega = kotlin.math.sqrt(9.8f / len)
                val t = if (mc.trigger >= 0f) game.time - mc.trigger else 99f
                val swing = if (t < 6f) 0.85f * sin(omega * t).coerceAtLeast(0f) * exp(-0.55f * t) else 0f
                val idle = 0.02f * sin(game.time * omega)   // faint residual sway
                val ax = mc.x - fxd * mc.a * 0.5f; val az = mc.z - fzd * mc.a * 0.5f
                val bx = mc.x + fxd * mc.a * 0.5f; val bz = mc.z + fzd * mc.a * 0.5f
                pendulum(ax, topY, az, fxd, fzd, idle, c)             // near ball rests
                pendulum(bx, topY, bz, fxd, fzd, swing + idle, c)     // far ball flies
            }
            M_ROCKER -> {
                // The plank angle is SOLVED from the rider using the very
                // function the builder baked the ball's path with, so the deck
                // is guaranteed to be under the ball rather than merely near
                // it. With no rider it sits on its rest stop, waiting.
                val rider = ridingBall(mc)
                val tilt = if (rider != null)
                    TrackBuilder.rockerAngle(((rider.s - mc.s0) / (mc.s1 - mc.s0)).coerceIn(0f, 1f))
                else TrackBuilder.ROCK_REST
                mc.phase = tilt
                val hx = fxd * mc.a; val hz = fzd * mc.a
                val dy = sin(tilt) * mc.a
                // plank drawn with thickness, so the ball rests ON something
                for (side in intArrayOf(-1, 1)) {
                    val ox = lxd * 0.05f * side; val oz = lzd * 0.05f * side
                    dyn.line(mc.x - hx + ox, mc.y - dy, mc.z - hz + oz,
                        mc.x + hx + ox, mc.y + dy, mc.z + hz + oz, c[0], c[1], c[2], 0.9f)
                    dyn.line(mc.x - hx + ox, mc.y - dy - 0.035f, mc.z - hz + oz,
                        mc.x + hx + ox, mc.y + dy - 0.035f, mc.z + hz + oz, c[0], c[1], c[2], 0.5f)
                }
                // end caps, so the plank reads as a board and not two wires
                dyn.line(mc.x - hx - lxd * 0.05f, mc.y - dy, mc.z - hz - lzd * 0.05f,
                    mc.x - hx + lxd * 0.05f, mc.y - dy, mc.z - hz + lzd * 0.05f, c[0], c[1], c[2], 0.8f)
                dyn.line(mc.x + hx - lxd * 0.05f, mc.y + dy, mc.z + hz - lzd * 0.05f,
                    mc.x + hx + lxd * 0.05f, mc.y + dy, mc.z + hz + lzd * 0.05f, c[0], c[1], c[2], 0.8f)
                // the trestle it pivots on
                dyn.line(mc.x - lxd * 0.08f, mc.y - 0.16f, mc.z - lzd * 0.08f, mc.x, mc.y, mc.z, 0.7f, 0.7f, 0.8f, 0.7f)
                dyn.line(mc.x + lxd * 0.08f, mc.y - 0.16f, mc.z + lzd * 0.08f, mc.x, mc.y, mc.z, 0.7f, 0.7f, 0.8f, 0.7f)
            }
            M_BUCKET -> {
                // The bucket CATCHES the ball: upright while the ball sits in
                // it, then tips with the counterweight to pour it onward.
                val rider = ridingBall(mc)
                val target = if (rider != null && rider.pauseT > 0f) {
                    val prog = 1f - (rider.pauseT / 0.8f).coerceIn(0f, 1f)
                    if (prog < 0.4f) 0f else (prog - 0.4f) / 0.6f * 0.85f
                } else if (rider != null) 0.85f else 0f
                mc.phase += (target - mc.phase) * (1f - exp(-9f * dt))
                val tip = mc.phase
                val bx = mc.x; val by = mc.y; val bz = mc.z
                // open bucket box, hinged at its forward lip, wrapping the ball
                val s = mc.a; val d = s * 0.7f
                val cT = cos(tip); val sT = sin(tip)
                // back wall top/bottom, rotated about the lip at (bx,by)
                val backTx = bx - fxd * s * cT; val backTy = by + s * sT
                val backBx = bx - fxd * s * cT + fxd * 0f; val backBy = backTy - d * cT
                dyn.line(bx, by, bz, backTx, backTy, bz - fzd * s * (1f - cT), c[0], c[1], c[2], 0.95f)
                dyn.line(bx, by - d, bz, backTx, backBy, bz - fzd * s * (1f - cT), c[0], c[1], c[2], 0.95f)
                dyn.line(bx, by, bz, bx, by - d, bz, c[0], c[1], c[2], 0.95f)
                dyn.line(backTx, backTy, bz - fzd * s * (1f - cT), backTx, backBy, bz - fzd * s * (1f - cT), c[0], c[1], c[2], 0.95f)
                // side rails of the bucket mouth
                dyn.line(bx - lxd * s * 0.5f, by, bz - lzd * s * 0.5f, bx + lxd * s * 0.5f, by, bz + lzd * s * 0.5f, c[0], c[1], c[2], 0.6f)
                // counterweight arm sinks as the bucket tips
                dyn.line(bx, by, bz, bx - fxd * mc.b, by - tip * 0.3f, bz - fzd * mc.b, 0.8f, 0.7f, 0.5f, 0.8f)
                boxAt(bx - fxd * mc.b, by - tip * 0.3f, bz - fzd * mc.b, 0.09f, 0.9f, 0.75f, 0.4f, 0.9f)
            }
            M_ELEV -> {
                mc.phase = (mc.phase + dt * 0.75f) % 0.28f
                val rise = mc.a
                val lx1 = mc.x + lxd * 0.14f; val lz1 = mc.z + lzd * 0.14f
                val lx2 = mc.x - lxd * 0.14f; val lz2 = mc.z - lzd * 0.14f
                dyn.line(lx1, mc.y, lz1, lx1, mc.y + rise, lz1, 0.75f, 0.8f, 0.55f, 0.7f)
                dyn.line(lx2, mc.y, lz2, lx2, mc.y + rise, lz2, 0.75f, 0.8f, 0.55f, 0.7f)
                var ry = mc.phase
                while (ry < rise) {
                    dyn.line(lx1, mc.y + ry, lz1, lx2, mc.y + ry, lz2, 1f, 0.85f, 0.4f, 0.55f)
                    ry += 0.28f
                }
                ringPlane(mc.x, mc.y + rise, mc.z, fxd, fzd, 0.14f, 10, 1f, 0.85f, 0.4f, 0.8f)
                ringPlane(mc.x, mc.y, mc.z, fxd, fzd, 0.14f, 10, 1f, 0.85f, 0.4f, 0.8f)
            }
        }
    }

    private fun pendulum(px: Float, topY: Float, pz: Float, fxd: Float, fzd: Float, swing: Float, c: FloatArray) {
        val ex = px + fxd * sin(swing) * 0.42f
        val ey = topY - cos(swing) * 0.42f
        val ez = pz + fzd * sin(swing) * 0.42f
        // Bifilar suspension, like the real thing — a cradle bob hangs off two
        // splayed wires, which is what keeps it swinging in one plane.
        val sx = fzd * 0.05f; val sz = -fxd * 0.05f
        dyn.line(px + sx, topY, pz + sz, ex, ey, ez, 0.8f, 0.85f, 0.95f, 0.8f)
        dyn.line(px - sx, topY, pz - sz, ex, ey, ez, 0.8f, 0.85f, 0.95f, 0.8f)
        // The bob was a single ring in the HORIZONTAL plane, which is edge-on
        // from almost every camera in this game and collapsed to a line — the
        // end pendulums were swinging all along, invisibly. Three orthogonal
        // rings read as a sphere from any angle.
        val r = BALL_R * 0.72f
        ringXZ(ex, ey, ez, r, 10, c[0], c[1], c[2], 0.9f)
        ringVert(ex, ey, ez, r, 10, 1f, 0f, c[0], c[1], c[2], 0.9f)
        ringVert(ex, ey, ez, r, 10, 0f, 1f, c[0], c[1], c[2], 0.9f)
    }

    /** Circle in a vertical plane whose horizontal direction is (dx,dz). */
    private fun ringVert(cx: Float, cy: Float, cz: Float, r: Float, segs: Int,
                         dx: Float, dz: Float, cr: Float, cg: Float, cb: Float, a: Float) {
        var pxp = cx + dx * r; var pyp = cy; var pzp = cz + dz * r
        for (i in 1..segs) {
            val ang = i * 2f * PI.toFloat() / segs
            val qx = cx + dx * cos(ang) * r
            val qy = cy + sin(ang) * r
            val qz = cz + dz * cos(ang) * r
            dyn.line(pxp, pyp, pzp, qx, qy, qz, cr, cg, cb, a)
            pxp = qx; pyp = qy; pzp = qz
        }
    }

    /**
     * The ball, still drawn as glowing vector line-work rather than a shaded
     * solid, but doing more of what a real sphere does.
     *
     * A wireframe cage of flat-alpha rings reads as a cage. The cue that sells
     * roundness in line-work is the SILHOUETTE: on a real sphere the surface
     * turns away at the rim, so grazing edges pile up and glow while the parts
     * facing you fall away. So every segment's alpha now comes from how
     * edge-on it is to the eye — bright at the limb, dim through the middle.
     * Latitude bands ride the roll axis so the spin is legible as rotation of
     * a body rather than a spinning hoop, a glint sits where a polished ball
     * would catch the key light, and a short ghost trail appears once the ball
     * is genuinely moving.
     */
    private fun drawBall(b: Ball) {
        val m = game.machine
        val r = BALL_R
        val col = TrackBuilder.hsv(b.hue)
        val px = b.pos[0]; val py = b.pos[1] + r * 0.55f; val pz = b.pos[2]
        // rolling basis: tangent, lateral, up' rotated by roll about lateral
        m.tangent(b.s, bt)
        var lx = bt[2]; var lz = -bt[0]
        var ll = hypot(lx, lz)
        if (ll < 0.20f) {
            // Tangent has tipped near-vertical — inside a loop, a helix, a steep
            // drop — and the lateral built from it collapses. Clamping its
            // LENGTH (as this did) stops the divide-by-zero but leaves the
            // direction arbitrary, so the basis span and the ball's rings
            // flipped about at random. Every horizontal direction is genuinely
            // perpendicular to a vertical tangent, so pick the one that follows
            // the cylinder wall: well-defined everywhere and continuous as the
            // ball goes round.
            val rr = hypot(px, pz).coerceAtLeast(1e-3f)
            lx = -pz / rr; lz = px / rr
            ll = 1f
        }
        lx /= ll; lz /= ll
        val ux = bt[1] * lz; val uy = bt[2] * lx - bt[0] * lz; val uz = -bt[1] * lx
        val cR = cos(b.roll); val sR = sin(b.roll)
        // Rotated basis. The sine terms were the other way round, which spun the
        // ball BACKWARDS: a mark on the leading face climbed up and over instead
        // of diving under, so the ball appeared to roll against its travel.
        // Rolling forward carries the front of the ball DOWN toward the contact.
        val t1x = bt[0] * cR - ux * sR; val t1y = bt[1] * cR - uy * sR; val t1z = bt[2] * cR - uz * sR
        val u1x = bt[0] * sR + ux * cR; val u1y = bt[1] * sR + uy * cR; val u1z = bt[2] * sR + uz * cR

        // ghost trail: the sphere a few centimetres back along the rail, twice,
        // shrinking and fading. Only once it is actually travelling, so a ball
        // resting in a bucket or cradle stays clean.
        if (b.v > 1.2f && !b.dropping && b.pauseT <= 0f) {
            val lag = (b.v * 0.016f).coerceAtMost(0.09f)
            for (k in 1..2) {
                val sg = b.s - lag * k
                if (sg < 0f) continue
                m.pos(sg, ballScratch)
                val gr = r * (1f - 0.20f * k)
                val ga = 0.30f / k * ((b.v - 1.2f) / 2.0f).coerceIn(0f, 1f)
                ringRim(ballScratch[0], ballScratch[1] + r * 0.55f, ballScratch[2],
                    t1x, t1y, t1z, u1x, u1y, u1z, gr, col, ga, 10)
            }
        }

        // Far off, the rings converge to a few pixels and the rim term — which
        // dims everything facing the eye — would fade the ball into nothing.
        // There used to be a bright dot at the centre carrying visibility at
        // range; it read as artificial up close, so the rings themselves gain
        // with distance instead. Nothing is added that is not part of the ball.
        val dcam = hypot(hypot(camPos[0] - px, camPos[1] - py), camPos[2] - pz)
        val g = 1f + 1.15f * ((dcam - 1.8f) / 4.5f).coerceIn(0f, 1f)

        // three great circles + two latitude bands about the roll axis
        ringRim(px, py, pz, t1x, t1y, t1z, u1x, u1y, u1z, r, col, 1f * g, 16)   // motion plane (spins!)
        ringRim(px, py, pz, lx, 0f, lz, u1x, u1y, u1z, r, col, 0.7f * g, 16)
        ringRim(px, py, pz, t1x, t1y, t1z, lx, 0f, lz, r, col, 0.7f * g, 16)
        for (s in intArrayOf(-1, 1)) {
            val lat = 0.66f                       // ~ +/-41 degrees
            val cl = cos(lat); val sl = sin(lat) * s
            // band circle: radius r*cos(lat), centre pushed along the roll axis
            ringRim(px + lx * r * sl, py, pz + lz * r * sl,
                t1x, t1y, t1z, u1x, u1y, u1z, r * cl, col, 0.42f * g, 14)
        }

        // Specular highlight, drawn ON the surface rather than as a point
        // sprite. A GL_POINT sits at a fixed pixel size no matter how far away
        // the ball is and never rotates with it, so it read as a sticker
        // floating on top of the sculpture instead of light on a curved
        // surface. A small circle laid on the sphere at the halfway vector
        // shrinks with distance and slides across the surface as the camera
        // moves, the way a real highlight does.
        var hx = KEY_X; var hy = KEY_Y; var hz = KEY_Z
        var vx = camPos[0] - px; var vy = camPos[1] - py; var vz = camPos[2] - pz
        val vl = hypot(hypot(vx, vy), vz).coerceAtLeast(1e-4f); vx /= vl; vy /= vl; vz /= vl
        hx += vx; hy += vy; hz += vz
        val hl = hypot(hypot(hx, hy), hz).coerceAtLeast(1e-4f); hx /= hl; hy /= hl; hz /= hl
        // two vectors spanning the plane perpendicular to h
        var e1x = -hy; var e1y = hx; var e1z = 0f
        if (hypot(hypot(e1x, e1y), e1z) < 1e-3f) { e1x = 1f; e1y = 0f; e1z = 0f }
        val e1l = hypot(hypot(e1x, e1y), e1z); e1x /= e1l; e1y /= e1l; e1z /= e1l
        val e2x = hy * e1z - hz * e1y
        val e2y = hz * e1x - hx * e1z
        val e2z = hx * e1y - hy * e1x
        // Concentric rings with the brightness piled toward the middle: a lone
        // outline circle reads as a hoop resting on the ball, whereas a tight
        // core fading outward reads as light falling on it.
        for (ring in 0..2) {
            val spread = 0.34f - ring * 0.115f
            val ga = 0.30f + ring * 0.34f          // innermost brightest
            val cs = cos(spread); val ss = sin(spread)
            var gpx = 0f; var gpy = 0f; var gpz = 0f
            val segs = 9 - ring * 2
            for (i in 0..segs) {
                val a = i * 2f * PI.toFloat() / segs
                val ca = cos(a); val sa = sin(a)
                val nx = hx * cs + (e1x * ca + e2x * sa) * ss
                val ny = hy * cs + (e1y * ca + e2y * sa) * ss
                val nz = hz * cs + (e1z * ca + e2z * sa) * ss
                val qx = px + nx * r; val qy = py + ny * r; val qz = pz + nz * r
                if (i > 0) dyn.line(gpx, gpy, gpz, qx, qy, qz, 1f, 1f, 0.96f, ga)
                gpx = qx; gpy = qy; gpz = qz
            }
        }
    }

    /**
     * A ring like ring3, but each segment's brightness follows how edge-on it
     * is to the camera — the limb of the sphere glows, the face falls away.
     * That single term is what stops the wireframe reading as a flat cage.
     */
    private fun ringRim(cx: Float, cy: Float, cz: Float,
                        axx: Float, axy: Float, axz: Float,
                        bxx: Float, bxy: Float, bxz: Float,
                        r: Float, c: FloatArray, alpha: Float, segs: Int) {
        var ex = cx - camPos[0]; var ey = cy - camPos[1]; var ez = cz - camPos[2]
        val el = hypot(hypot(ex, ey), ez).coerceAtLeast(1e-4f); ex /= el; ey /= el; ez /= el
        var pxp = cx + axx * r; var pyp = cy + axy * r; var pzp = cz + axz * r
        var pa = 0f
        for (i in 0..segs) {
            val a = i * 2f * PI.toFloat() / segs
            val ca = cos(a); val sa = sin(a)
            val nx = axx * ca + bxx * sa
            val ny = axy * ca + bxy * sa
            val nz = axz * ca + bxz * sa
            // 1 at the silhouette, 0 pointing straight at the eye
            val rim = 1f - abs(nx * ex + ny * ey + nz * ez)
            val av = alpha * (0.18f + 0.82f * rim * rim)
            val qx = cx + nx * r; val qy = cy + ny * r; val qz = cz + nz * r
            if (i > 0) dyn.line(pxp, pyp, pzp, qx, qy, qz, c[0], c[1], c[2], (pa + av) * 0.5f)
            pxp = qx; pyp = qy; pzp = qz; pa = av
        }
    }


    private fun ringXZ(cx: Float, cy: Float, cz: Float, r: Float, segs: Int, cr: Float, cg: Float, cb: Float, a: Float) {
        var pa = 0f
        for (i in 1..segs) {
            val an = i * 2f * PI.toFloat() / segs
            dyn.line(cx + sin(pa) * r, cy, cz + cos(pa) * r, cx + sin(an) * r, cy, cz + cos(an) * r, cr, cg, cb, a)
            pa = an
        }
    }

    private fun ringT(cx: Float, cy: Float, cz: Float, yawDir: Float, r: Float, segs: Int, cr: Float, cg: Float, cb: Float, a: Float) {
        val lx = cos(yawDir); val lz = -sin(yawDir)
        var pa = 0f
        for (i in 1..segs) {
            val an = i * 2f * PI.toFloat() / segs
            dyn.line(cx + lx * sin(pa) * r, cy + cos(pa) * r, cz + lz * sin(pa) * r,
                cx + lx * sin(an) * r, cy + cos(an) * r, cz + lz * sin(an) * r, cr, cg, cb, a)
            pa = an
        }
    }

    private fun ringPlane(cx: Float, cy: Float, cz: Float, fxd: Float, fzd: Float, r: Float, segs: Int, cr: Float, cg: Float, cb: Float, a: Float) {
        var pa = 0f
        for (i in 1..segs) {
            val an = i * 2f * PI.toFloat() / segs
            dyn.line(cx + fxd * sin(pa) * r, cy + cos(pa) * r, cz + fzd * sin(pa) * r,
                cx + fxd * sin(an) * r, cy + cos(an) * r, cz + fzd * sin(an) * r, cr, cg, cb, a)
            pa = an
        }
    }

    private fun boxAt(cx: Float, cy: Float, cz: Float, s: Float, r: Float, g: Float, b: Float, a: Float) {
        dyn.line(cx - s, cy - s, cz, cx + s, cy - s, cz, r, g, b, a)
        dyn.line(cx + s, cy - s, cz, cx + s, cy + s, cz, r, g, b, a)
        dyn.line(cx + s, cy + s, cz, cx - s, cy + s, cz, r, g, b, a)
        dyn.line(cx - s, cy + s, cz, cx - s, cy - s, cz, r, g, b, a)
    }

    // ---------------------------------------------------------- HUD

    private val sink = object : StrokeFont.LineSink {
        var cr = 1f; var cg = 1f; var cb = 1f; var ca = 1f
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            hud.line(x0, y0, 0f, x1, y1, 0f, cr, cg, cb, ca)
        }
    }

    private fun text(s: String, cx: Float, y: Float, scale: Float, r: Float, g: Float, b: Float, a: Float = 1f, center: Boolean = true) {
        val x = if (center) cx - StrokeFont.width(s, scale) / 2f else cx
        sink.cr = r; sink.cg = g; sink.cb = b; sink.ca = a
        StrokeFont.draw(s, x, y, scale, sink)
    }

    /** Axis-aligned HUD rectangle, in the ortho 640x480 frame. */
    private fun box(x0: Float, y0: Float, x1: Float, y1: Float,
                    r: Float, g: Float, b: Float, a: Float) {
        hud.line(x0, y0, 0f, x1, y0, 0f, r, g, b, a)
        hud.line(x1, y0, 0f, x1, y1, 0f, r, g, b, a)
        hud.line(x1, y1, 0f, x0, y1, 0f, r, g, b, a)
        hud.line(x0, y1, 0f, x0, y0, 0f, r, g, b, a)
    }

    /** A pair of hairlines under the title. */
    private fun rule(x0: Float, y0: Float, x1: Float, y1: Float,
                     r: Float, g: Float, b: Float, a: Float) {
        hud.line(x0, y1, 0f, x1, y1, 0f, r, g, b, a)
        hud.line(x0 + 40f, y1 + 5f, 0f, x1 - 40f, y1 + 5f, 0f, r, g, b, a * 0.5f)
    }

    private fun buildHud() {
        hud.reset()
        val pulse = 0.55f + 0.45f * sin(game.time * 4f)
        when (game.state) {
            GameState.TITLE -> {
                // The machine itself is the backdrop, running its attract balls,
                // and it REBUILDS as you swipe — so the sculpture winding away
                // behind these cards is the one you are about to play.
                text("TAPKUGELBAHN", 320f, 86f, 4.2f, 1f, 0.72f, 0.25f)
                rule(120f, 86f, 520f, 96f, 1f, 0.72f, 0.25f, 0.5f)
                text("A KINETIC ROLLING-BALL SCULPTURE", 320f, 118f, 1.3f, 0.55f, 0.68f, 0.82f, 0.9f)

                val sel = game.level
                for (l in 1..Machine.LEVELS) {
                    val cx = 320f + (l - sel) * 176f
                    if (cx < 40f || cx > 600f) continue
                    val on = l == sel
                    // the chosen card sits forward: brighter, taller, named
                    val hw = if (on) 82f else 66f
                    val hh = if (on) 52f else 40f
                    val a = if (on) 1f else 0.32f
                    val cy = 210f
                    box(cx - hw, cy - hh, cx + hw, cy + hh,
                        if (on) 1f else 0.5f, if (on) 0.82f else 0.6f, if (on) 0.4f else 0.7f, a)
                    text(ROMAN[l - 1], cx, cy - hh + 30f, if (on) 2.6f else 1.9f,
                        1f, 0.86f, 0.42f, a)
                    if (on) {
                        text(Machine.NAMES[l - 1], cx, cy + 8f, 1.45f, 0.75f, 0.92f, 1f, 1f)
                        text("${Steps.stepsFor(l).size} STEPS", cx, cy + 30f, 1.15f, 0.6f, 0.72f, 0.85f, 1f)
                        text("${game.machine.length.toInt()} M", cx, cy + 48f, 1.15f, 0.6f, 0.72f, 0.85f, 1f)
                    }
                }
                // which of the three, at a glance
                for (l in 1..Machine.LEVELS) {
                    val dx = 320f + (l - 2) * 22f
                    val on = l == sel
                    box(dx - 5f, 286f, dx + 5f, 296f, 1f, 0.82f, 0.4f, if (on) 1f else 0.28f)
                }

                text("SWIPE TO CHOOSE", 320f, 330f, 1.5f, 0.6f, 0.85f, 1f, 0.95f)
                text("TAP TO START", 320f, 372f, 2.6f, 1f, 1f, 1f, pulse)
                text("TAP DROPS A BALL · SWIPE CHANGES THE VIEW", 320f, 424f, 1.2f, 0.5f, 0.6f, 0.72f, 0.85f)
            }
            GameState.RUN, GameState.COMPLETE, GameState.FINALE -> {
                text("L${game.level} ${Machine.NAMES[game.level - 1]}", 14f, 40f, 1.6f, 0.65f, 0.85f, 1f, 1f, center = false)
                val bc = "BALLS ${game.activeCount()}"
                text(bc, 626f - StrokeFont.width(bc, 1.6f), 40f, 1.6f, 1f, 0.85f, 0.45f, 1f, center = false)
                if (game.viewFlash > 0f)
                    text(Game.VIEW_NAMES[game.view], 320f, 78f, 2.2f, 0.65f, 1f, 0.8f, game.viewFlash.coerceAtMost(1f))
                when (game.state) {
                    GameState.COMPLETE -> {
                        text("LEVEL COMPLETE", 320f, 120f, 3.2f, 0.5f, 1f, 0.6f, 0.6f + 0.4f * pulse)
                        text(if (game.level < Machine.LEVELS) "DOUBLE-TAP FOR THE NEXT MACHINE"
                            else "DOUBLE-TAP FOR THE FINALE", 320f, 165f, 1.6f, 1f, 1f, 1f, pulse)
                    }
                    GameState.FINALE -> {
                        text("DAS GROSSE WERK RUNS FOREVER", 320f, 120f, 2.2f, 1f, 0.75f, 0.3f, 0.7f)
                        text("DOUBLE-TAP TO BEGIN AGAIN", 320f, 158f, 1.5f, 0.8f, 0.8f, 0.85f, pulse)
                    }
                    else -> {
                        val players = game.balls.count { it.dropTime >= 0f }
                        if (players == 0) text("TAP TO DROP A BALL", 320f, 440f, 1.7f, 1f, 1f, 1f, pulse)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------- gl plumbing

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("TapKugelbahn", "link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("TapKugelbahn", "compile: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    inner class Batch(maxVerts: Int) {
        private val fb: FloatBuffer = ByteBuffer.allocateDirect(maxVerts * 7 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val cap = maxVerts
        var count = 0; private set

        fun reset() { fb.position(0); count = 0 }

        fun v(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
            if (count >= cap) return
            fb.put(x); fb.put(y); fb.put(z); fb.put(r); fb.put(g); fb.put(b); fb.put(a); count++
        }

        fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, g: Float, b: Float, a: Float) {
            v(x0, y0, z0, r, g, b, a); v(x1, y1, z1, r, g, b, a)
        }

        fun draw(mode: Int) {
            if (count == 0) return
            fb.position(0)
            GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 28, fb)
            GLES30.glEnableVertexAttribArray(aPos)
            fb.position(3)
            GLES30.glVertexAttribPointer(aColor, 4, GLES30.GL_FLOAT, false, 28, fb)
            GLES30.glEnableVertexAttribArray(aColor)
            GLES30.glDrawArrays(mode, 0, count)
        }
    }

    companion object {
        // Key light, world space, normalised — used only for the ball's glint,
        // so the highlight sits somewhere consistent as the camera swings
        // rather than sliding around with the eye.
        // Chunk goes solid inside MESH_IN, reverts to neon outside MESH_OUT.
        // The gap is the hysteresis that stops boundary flicker.
        private const val MESH_IN = 3.0f
        private const val MESH_OUT = 3.6f

        private const val KEY_X = -0.40f
        private const val KEY_Y = 0.78f
        private const val KEY_Z = -0.48f

        /**
         * Solid shading for an ADDITIVE waveguide, where black is not dark —
         * it is transparent. Three rules follow from that, and they are what
         * keep the mesh from punching see-through holes in the sculpture:
         *
         *  1. Every material starts from an emissive floor, before any light
         *     is added. There is no path from geometry alone to zero.
         *  2. Ambient occlusion tints toward cool instead of multiplying down;
         *     a raw AO multiply would darken creases straight to invisible.
         *  3. A fragment that still ends up below the haze threshold is
         *     DISCARDED rather than dimmed — if it merely dimmed it would keep
         *     writing depth and occlude the glowing line-work behind it,
         *     reading as a black hole in the machine rather than as absence.
         *
         * The fill light exists to make the shadow side a different HUE, not
         * to add brightness; hue separation is the only shading cue that
         * survives on a display that cannot draw dark.
         */
        private const val MESH_VERT = """#version 300 es
        in vec3 aPos;
        in vec3 aNrm;
        in float aMat;
        uniform mat4 uMVP;
        out vec3 vN;
        out vec3 vW;
        flat out int vMat;
        void main() {
            gl_Position = uMVP * vec4(aPos, 1.0);
            vN = aNrm;
            vW = aPos;
            vMat = int(aMat + 0.5);
        }
        """

        private const val MESH_FRAG = """#version 300 es
        precision mediump float;
        in vec3 vN;
        in vec3 vW;
        flat in int vMat;
        uniform vec3 uEye;
        out vec4 fragColor;

        void main() {
            vec3 albedo; float floorE; float shine; float gain;
            if (vMat == 1)      { albedo = vec3(0.62,0.42,0.24); floorE = 0.20; shine = 18.0; gain = 0.09; }
            else if (vMat == 2) { albedo = vec3(0.62,0.48,0.20); floorE = 0.16; shine = 110.0; gain = 1.05; }
            else                { albedo = vec3(0.42,0.47,0.56); floorE = 0.15; shine = 72.0; gain = 0.85; }

            vec3 N = normalize(vN);
            vec3 V = normalize(uEye - vW);
            if (dot(N, V) < 0.0) N = -N;           // tubes are open shells

            vec3 KEY  = normalize(vec3(-0.40, 0.78,-0.48));
            vec3 FILL = normalize(vec3( 0.62, 0.30, 0.72));

            // wrapped diffuse: never reaches zero on the far side
            float dk = max(0.0, (dot(N, KEY)  + 0.30) / 1.30);
            float df = max(0.0, (dot(N, FILL) + 0.55) / 1.55);

            vec3 L = albedo * floorE;                                  // rule 1
            L += albedo * vec3(1.00,0.96,0.88) * dk * 0.85;
            L += albedo * vec3(0.55,0.66,0.95) * df * 0.30;            // hue, not brightness
            float spec = pow(max(0.0, dot(reflect(-KEY, N), V)), shine);
            L += vec3(1.0,0.97,0.90) * spec * gain;
            // rim: grazing angles pile up, which is what says "round" here
            float rim = pow(1.0 - max(0.0, dot(N, V)), 3.0);
            L += albedo * rim * 0.45;

            float lum = max(max(L.r, L.g), L.b);
            if (lum < 0.030) discard;                                  // rule 3
            L *= smoothstep(0.030, 0.085, lum);
            L = L / (1.0 + L / 2.6);                                   // extended Reinhard
            L = pow(L, vec3(1.0/1.9));
            fragColor = vec4(L, lum);
        }
        """

        private const val VERT = """#version 300 es
        in vec3 aPos;
        in vec4 aColor;
        uniform mat4 uMVP;
        uniform float uPointSize;
        out vec4 vColor;
        void main() {
            gl_Position = uMVP * vec4(aPos, 1.0);
            gl_PointSize = uPointSize;
            vColor = aColor;
        }"""

        private const val FRAG = """#version 300 es
        precision mediump float;
        in vec4 vColor;
        uniform float uPoint;
        out vec4 fragColor;
        void main() {
            if (uPoint > 0.5) {
                vec2 d = gl_PointCoord - vec2(0.5);
                float r2 = dot(d, d);
                if (r2 > 0.25) discard;
                fragColor = vec4(vColor.rgb, vColor.a * (1.0 - r2 * 4.0));
            } else {
                fragColor = vColor;
            }
        }"""
    }
}
