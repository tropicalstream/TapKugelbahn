package com.tapkugelbahn.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * The track engine. A Kugelbahn level is ONE closed loop: intake → mechanisms →
 * elevator → back to the intake. The centerline is emitted as densely sampled
 * points with a cumulative arc-length table; balls live at an arc position s
 * and advance by an energy model (downhill = faster), with zones that override
 * behavior (lifts run at constant speed, the gauss cannon boosts, the cradle
 * pauses and hands off). All rails, tunnel hoops and mechanism sculpture are
 * emitted as glowing vector lines in the suite's neon style.
 */

const val BALL_R = 0.12f          // ball radius (m); drop height is 5×diameter
const val DROP_H = 10 * BALL_R    // 5 × diameter above the intake

// Zone behavior types
const val Z_FREE = 0      // gravity
const val Z_LIFT = 1      // constant slow climb (elevator / archimedes screw)
const val Z_PAUSE = 2     // stop briefly on entry, then eject (cradle, bucket, rocker)
const val Z_BOOST = 3     // instant speed boost on entry (gauss cannon)
const val Z_FERRIS = 4    // constant ride speed (ferris wheel, trommel)

// Mechanism art types (dynamic, animated per frame by the renderer)
const val M_FERRIS = 0
const val M_SCREW = 1
const val M_TROMMEL = 2
const val M_PROP = 3
const val M_CRADLE = 4
const val M_ROCKER = 5
const val M_BUCKET = 6
const val M_ELEV = 7

// Sounds (ids match Sfx)
const val S_ROLL = 0; const val S_CLACK = 1; const val S_XYLO = 2
const val S_RATCHET = 3; const val S_DING = 4; const val S_DROP = 5

// Solid-mesh materials (index into the renderer's material table)
const val MAT_STEEL = 0
const val MAT_WOOD = 1
const val MAT_BRASS = 2

/**
 * A span of solid geometry, bucketed by arc length so the renderer can issue
 * only what is near the eye. Everything beyond stays neon line-work, which is
 * both the performance budget and the art direction: solid where you can see
 * the craft, glowing where it is just structure across the room.
 */
data class MeshChunk(
    val s0: Float, val s1: Float,
    val start: Int, val count: Int,      // vertex range in the mesh array
    val cx: Float, val cy: Float, val cz: Float, val rad: Float
)

data class Zone(val s0: Float, val s1: Float, val type: Int, val speed: Float = 0f, val pause: Float = 0f)
data class Note(val s: Float, val sound: Int, val pitch: Float, val vol: Float = 1f)

/** One animated mechanism instance for the renderer. */
class Mech(
    val type: Int,
    val x: Float, val y: Float, val z: Float,
    val yaw: Float,             // orientation about Y
    val a: Float, val b: Float, // size params (radius / length etc.)
    val hue: Float,
    var phase: Float = 0f,      // animation phase, advanced by renderer
    var trigger: Float = -1f,   // time of last trigger (cradle swing, bucket tip)
    var s0: Float = -1f,        // the mechanism's span on the track, so its
    var s1: Float = -1f         // animation can lock onto the ball riding it
)

class MachineModel(
    val pts: FloatArray,        // n×3 centerline
    val cum: FloatArray,        // n cumulative arc length
    val length: Float,
    val staticLines: FloatArray, // line verts ×7 (xyz rgba)
    val staticCount: Int,
    val meshVerts: FloatArray,   // solid verts ×7 (xyz  nx ny nz  matId)
    val meshChunks: List<MeshChunk>,
    val mechs: List<Mech>,
    val zones: List<Zone>,
    val notes: List<Note>,
    val finishS: Float,          // crossing this = completed a circuit (elevator base)
    val intake: FloatArray,      // xyz of the drop-in point
    val center: FloatArray,      // bbox center
    val radius: Float            // bbox radius (for the outside orbit camera)
) {
    private val n = cum.size
    /** Position on the centerline at arc s (wraps). */
    fun pos(s: Float, out: FloatArray) {
        var ss = s % length; if (ss < 0) ss += length
        // binary search cum
        var lo = 0; var hi = n - 1
        while (lo + 1 < hi) { val mid = (lo + hi) / 2; if (cum[mid] <= ss) lo = mid else hi = mid }
        val seg = (cum[hi] - cum[lo]).coerceAtLeast(1e-5f)
        val t = (ss - cum[lo]) / seg
        out[0] = pts[lo * 3] + (pts[hi * 3] - pts[lo * 3]) * t
        out[1] = pts[lo * 3 + 1] + (pts[hi * 3 + 1] - pts[lo * 3 + 1]) * t
        out[2] = pts[lo * 3 + 2] + (pts[hi * 3 + 2] - pts[lo * 3 + 2]) * t
    }
    /** Slope dy/ds at arc s (central difference). */
    fun slope(s: Float): Float {
        val e = 0.22f
        pos(s - e, tmpA); pos(s + e, tmpB)
        return (tmpB[1] - tmpA[1]) / (2 * e)
    }
    /** Unit tangent at s. */
    fun tangent(s: Float, out: FloatArray) {
        val e = 0.18f
        pos(s - e, tmpA); pos(s + e, tmpB)
        var dx = tmpB[0] - tmpA[0]; var dy = tmpB[1] - tmpA[1]; var dz = tmpB[2] - tmpA[2]
        val l = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-5f)
        out[0] = dx / l; out[1] = dy / l; out[2] = dz / l
    }
    fun zoneAt(s: Float): Zone? {
        var ss = s % length; if (ss < 0) ss += length
        for (z in zones) if (ss >= z.s0 && ss < z.s1) return z
        return null
    }
    private val tmpA = FloatArray(3); private val tmpB = FloatArray(3)
}

/**
 * Emits centerline + sculpture. A cursor (position + yaw) walks the space;
 * each mechanism appends samples and art, then leaves the cursor at its exit.
 */
class TrackBuilder {

    // Solid geometry, emitted in the same pass as the glowing line-work.
    val mesh = ArrayList<Float>()          // xyz  nx ny nz  matId
    val chunks = ArrayList<MeshChunk>()
    private val pts = ArrayList<Float>(20000)
    private val art = ArrayList<Float>(120000)
    val mechs = ArrayList<Mech>()
    val zones = ArrayList<Zone>()
    val notes = ArrayList<Note>()

    var x = 0f; var y = 0f; var z = 0f
    var yaw = 0f                       // travel direction in XZ
    private var emitted = 0

    // ---- steel / wood / brass palette -----------------------------------
    private fun steel(a: Float = 0.8f) = floatArrayOf(0.62f, 0.78f, 0.92f, a)
    private fun wood(a: Float = 0.8f) = floatArrayOf(0.94f, 0.66f, 0.32f, a)
    private fun brass(a: Float = 0.9f) = floatArrayOf(1f, 0.84f, 0.35f, a)
    private fun accent(h: Float, a: Float): FloatArray { val c = hsv(h); return floatArrayOf(c[0], c[1], c[2], a) }

    fun point(px: Float, py: Float, pz: Float) {
        pts.add(px); pts.add(py); pts.add(pz); emitted++
        x = px; y = py; z = pz
    }

    fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, c: FloatArray) {
        art.add(x0); art.add(y0); art.add(z0); art.add(c[0]); art.add(c[1]); art.add(c[2]); art.add(c[3])
        art.add(x1); art.add(y1); art.add(z1); art.add(c[0]); art.add(c[1]); art.add(c[2]); art.add(c[3])
    }

    private fun curS(): Float {
        // approximate current arc length (sum as we go; recomputed exactly at build)
        return approxS
    }
    private var approxS = 0f
    private var lastX = 0f; private var lastY = 0f; private var lastZ = 0f
    private var started = false

    private fun emit(px: Float, py: Float, pz: Float) {
        if (started) approxS += hypot(hypot(px - lastX, py - lastY), pz - lastZ)
        lastX = px; lastY = py; lastZ = pz; started = true
        point(px, py, pz)
    }

    // ------------------------------------------------------- primitive moves

    /**
     * Panoramic cylinder arc: the route winds counterclockwise around the
     * world Y axis, easing back to wall radius R no matter where the last
     * mechanism left the cursor — so the whole machine reads as one
     * continuous cylindrical panorama seen from inside. Joints stay tight
     * because the ease starts from the exact current radius/height.
     */
    fun cylArc(R: Float, dTheta: Float, dy: Float) {
        val r0 = hypot(x, z).coerceAtLeast(0.2f)
        val th0 = atan2f(x, z)
        val n = (abs(dTheta) * maxOf(r0, R) / 0.075f).toInt().coerceAtLeast(8)
        val y0 = y

        // THE JOINT FIX. This arc used to begin travelling purely
        // circumferentially no matter which way the ball was actually going.
        // Position was always continuous — it starts from the cursor's own
        // radius and azimuth — but the DIRECTION was taken from the cylinder,
        // not from the incoming yaw, and the radius ease is a smoothstep whose
        // derivative at t=0 is zero, so there was no radial component to
        // soften it. Every mechanism whose exit was not already tangential
        // handed the ball a corner here: the funnel threw one of 87 degrees,
        // the snake 96, the ferris wheel 172.
        //
        // So blend the departure direction in, rather than snapping to it: add
        // an offset along (incoming − circumferential) weighted by t(1−t)²,
        // scaled by the arc's own speed. That shape is zero at both ends and
        // has zero derivative at the far one, so entry POSITION and the exit
        // tangent are both left exactly as they were — only the initial
        // heading changes. Worst-case joint drops from 90° to under 2°.
        val dirInX = sin(yaw); val dirInZ = cos(yaw)
        val sgn = if (dTheta >= 0f) 1f else -1f
        val circX = cos(th0) * sgn; val circZ = -sin(th0) * sgn
        val amp = r0 * abs(dTheta)
        val corrX = (dirInX - circX) * amp
        val corrZ = (dirInZ - circZ) * amp

        for (i in 1..n) {
            val t = i.toFloat() / n
            val ease = t * t * (3f - 2f * t)
            val r = r0 + (R - r0) * ease
            val th = th0 + dTheta * t
            val w = t * (1f - t) * (1f - t)
            emit(sin(th) * r + corrX * w, y0 + dy * t, cos(th) * r + corrZ * w)
        }
        yaw = th0 + dTheta + PI.toFloat() / 2f
    }

    /** Place the cursor on the cylinder wall, facing tangent (counterclockwise). */
    fun cylStart(R: Float, theta: Float, y0: Float) {
        x = sin(theta) * R; y = y0; z = cos(theta) * R
        yaw = theta + PI.toFloat() / 2f
    }

    /** Straight run of dist with total descent dy (negative = down). */
    fun straight(dist: Float, dy: Float, step: Float = 0.09f) {
        val n = (dist / step).toInt().coerceAtLeast(2)
        val dx = sin(yaw); val dz = cos(yaw)
        val sx = x; val sy = y; val sz = z
        for (i in 1..n) {
            val t = i.toFloat() / n
            emit(sx + dx * dist * t, sy + dy * t, sz + dz * dist * t)
        }
    }

    /** Horizontal arc: turn by angle (radians, + = left), radius r, descending dy. */
    fun arc(r: Float, angle: Float, dy: Float, step: Float = 0.08f) {
        val n = (abs(angle) * r / step).toInt().coerceAtLeast(4)
        // Same centre-side correction as helix(); this one is currently unused
        // by any machine, but it is the formula the others were copied from and
        // would hand the same 2r break to the next mechanism written from it.
        val cxs = x + sin(yaw + if (angle > 0) PI.toFloat() / 2 else -PI.toFloat() / 2) * r
        val czs = z + cos(yaw + if (angle > 0) PI.toFloat() / 2 else -PI.toFloat() / 2) * r
        val sy = y
        val a0 = yaw + if (angle > 0) -PI.toFloat() / 2 else PI.toFloat() / 2
        for (i in 1..n) {
            val t = i.toFloat() / n
            val a = a0 + angle * t
            emit(cxs + sin(a) * r, sy + dy * t, czs + cos(a) * r)
        }
        yaw += angle
    }

    /** Vertical loop-the-loop in the travel plane, radius r, lateral shift so
     *  exit clears entry. Rail momentum carries the ball around at speed —
     *  the energy model alone would crawl over the top in slow motion. */
    fun loop(r: Float) {
        val s0 = approxS
        val dx = sin(yaw); val dz = cos(yaw)
        val lx = cos(yaw); val lz = -sin(yaw)     // lateral
        val sx = x; val sy = y; val sz = z
        val n = 64
        for (i in 1..n) {
            val t = i.toFloat() / n
            val th = t * 2f * PI.toFloat()
            val fwd = r * sin(th)
            val up = r * (1f - cos(th))
            val side = 2.6f * BALL_R * t
            emit(sx + dx * fwd + lx * side, sy + up, sz + dz * fwd + lz * side)
        }
        zones.add(Zone(s0 - 0.15f, approxS, Z_FERRIS, speed = 3.1f))
    }

    /** Helix (corkscrew / spiral): turns full rotations, total drop, radius r.
     *  The ball gathers speed lap over lap on the way down, as it should. */
    fun helix(r: Float, turns: Float, drop: Float, clockwise: Boolean = true, hue: Float = 0.55f) {
        val dir = if (clockwise) 1f else -1f
        // The centre sits to the side the ball curves TOWARD. It was negated,
        // which put it a diameter away on the wrong side: the corkscrew then
        // began 2r (1.7 m at the sizes in use) from where the track left off,
        // so the ball shot away in a straight line, spiralled off in mid-air
        // and was dragged back by the next cylArc. The spiral looked present
        // but never actually took hold of the ball.
        // Note the start angle and the exit `yaw +=` below are BOTH correct
        // for this centre — flipping a0 instead would close the gap and then
        // send the ball around backwards.
        val cx = x + sin(yaw + dir * PI.toFloat() / 2) * r
        val cz = z + cos(yaw + dir * PI.toFloat() / 2) * r
        val a0 = yaw + dir * -PI.toFloat() / 2
        val sy = y
        val sHelix = approxS
        val n = (turns * 48).toInt().coerceAtLeast(12)
        for (i in 1..n) {
            val t = i.toFloat() / n
            val a = a0 + dir * turns * 2f * PI.toFloat() * t
            emit(cx + sin(a) * r, sy - drop * t, cz + cos(a) * r)
        }
        yaw += dir * turns * 2f * PI.toFloat()
        yaw = ((yaw % (2f * PI.toFloat())) + 2f * PI.toFloat()) % (2f * PI.toFloat())
        val helixLen = approxS - sHelix

        // This mechanism used to emit NO sculpture whatsoever — not one line.
        // All you saw was the auto-generated rail and tunnel hoops happening to
        // spiral, so there was nothing to read as a corkscrew; it looked like
        // track that wandered. Give it its spindle and the spokes hanging the
        // coil off it, which is what makes a helix legible as a machine.
        val col = accent(hue, 0.55f)
        val dim = accent(hue, 0.22f)
        val yTop = sy; val yBot = sy - drop
        line(cx, yTop + 0.1f, cz, cx, yBot - 0.1f, cz, col)          // the spindle
        val spokes = (turns * 8).toInt().coerceAtLeast(8)
        for (k in 0..spokes) {
            val t = k.toFloat() / spokes
            val a = a0 + dir * turns * 2f * PI.toFloat() * t
            val hy = sy - drop * t
            val hx = cx + sin(a) * r; val hz = cz + cos(a) * r
            line(cx, hy, cz, hx, hy, hz, if (k % 2 == 0) col else dim)
            // a short flight of the thread, so the coil has a surface
            if (k < spokes) {
                val a2 = a0 + dir * turns * 2f * PI.toFloat() * ((k + 0.5f) / spokes)
                val y2 = sy - drop * ((k + 0.5f) / spokes)
                line(hx, hy, hz, cx + sin(a2) * r * 0.55f, y2, cz + cos(a2) * r * 0.55f, dim)
            }
        }

        // Speed ladder. These zones PIN the speed outright (Z_FERRIS assigns
        // v = z.speed every step), so the old 1.35 start actively braked a ball
        // that arrived faster and the corkscrew read as inert. Start from what
        // a ball plausibly carries in and genuinely gather pace down the coil.
        for (k in 0 until 4) {
            zones.add(Zone(sHelix + helixLen * k / 4f, sHelix + helixLen * (k + 1) / 4f,
                Z_FERRIS, speed = 1.9f + k * 0.75f))
        }
        // a rung note per quarter turn — the coil should tick as it winds down
        val ticks = (turns * 4).toInt().coerceAtLeast(4)
        for (k in 1..ticks) {
            notes.add(Note(sHelix + helixLen * k / ticks, S_RATCHET,
                0.85f + 0.35f * k / ticks, 0.5f))
        }
    }

    // ------------------------------------------------------- mechanisms

    /** Drop intake: a catch funnel; the loop's s=0 should be here. */
    fun intakeFunnel(hue: Float) {
        // rings of the catch cone above the current point
        val c = accent(hue, 0.75f)
        ringY(x, y + 0.30f, z, 0.55f, 14, c)
        ringY(x, y + 0.16f, z, 0.34f, 12, c)
        ringY(x, y + 0.05f, z, 0.18f, 10, c)
        for (k in 0 until 6) {
            val a = k * PI.toFloat() / 3
            line(x + sin(a) * 0.55f, y + 0.30f, z + cos(a) * 0.55f, x + sin(a) * 0.18f, y + 0.05f, z + cos(a) * 0.18f, c)
        }
        straight(0.9f, -0.18f)
    }

    /** Gravity funnel: ball spirals a shrinking cone into the center hole. */
    fun gravityFunnel(hue: Float) {
        // The spiral's centre belongs to the SIDE of the cursor, not straight
        // ahead of it. Placed ahead, the ball arrives pointing at the centre
        // and the first sample of the spiral is already travelling
        // circumferentially — it got flung sideways through 87 degrees in a
        // single step, at the lip, which is exactly where a funnel should feel
        // smoothest. (Position was continuous either way, which is why this
        // survived: the a0 term cancelled the offset. Only the TANGENT was
        // wrong.) Centre to the side, entry angle likewise, and the ball now
        // enters within 3 degrees of its incoming direction.
        val cx0 = x + sin(yaw + PI.toFloat() / 2) * 1.05f
        val cz0 = z + cos(yaw + PI.toFloat() / 2) * 1.05f
        val topY = y
        val sSpiral = approxS
        // Whole turns: a fractional count leaves the throat pointing wherever
        // it happens to stop, and the exit joint inherits the error. Two turns
        // also widens the radial step per coil to 0.425 m, so the tunnel hoops
        // (0.198 m radius, needing 0.396 m) stop interpenetrating.
        val turns = 2f; val n = (turns * 56).toInt()
        val a0 = yaw - PI.toFloat() / 2
        for (i in 1..n) {
            val t = i.toFloat() / n
            val r = 1.05f - 0.85f * t
            val a = a0 + turns * 2f * PI.toFloat() * t
            emit(cx0 + sin(a) * r, topY - 0.9f * t * t, cz0 + cos(a) * r)
        }
        // coin-funnel physics: as the orbit tightens it QUICKENS — the
        // signature ever-faster whirl into the throat
        val spiralLen = approxS - sSpiral
        for (k in 0 until 5) {
            zones.add(Zone(sSpiral + spiralLen * k / 5f, sSpiral + spiralLen * (k + 1) / 5f,
                Z_FERRIS, speed = 1.25f + k * 0.55f))
        }
        // cone art
        val c = accent(hue, 0.5f)
        for (ring in 0..4) {
            val t = ring / 4f
            ringY(cx0, topY - 0.86f * t * t - 0.02f, cz0, 1.05f - 0.83f * t, 18, c)
        }
        // exit drop out of the throat
        yaw = atan2f(x - cx0, z - cz0) + PI.toFloat() / 2
        straight(0.5f, -0.5f)
        notes.add(Note(approxS, S_CLACK, 0.7f, 0.7f))
    }

    /** Xylophone stairs: the ball HOPS bar to bar — a ballistic arc onto each
     *  glowing bar, a note ringing out at every landing. */
    fun xylophone(hue: Float, steps: Int = 6) {
        val scale = floatArrayOf(1.0f, 1.122f, 1.26f, 1.335f, 1.498f, 1.682f, 1.888f, 2.0f)
        for (i in 0 until steps) {
            val fx = sin(yaw); val fz = cos(yaw)
            val lx = cos(yaw); val lz = -sin(yaw)
            val sx = x; val sy = y; val sz = z
            val hop = 0.5f; val drop = 0.17f; val arc = 0.085f
            val n = 10
            for (k in 1..n) {
                val t = k.toFloat() / n
                emit(sx + fx * hop * t, sy + arc * 4f * t * (1f - t) - drop * t, sz + fz * hop * t)
            }
            // the bar exactly under the landing — the strike you can see
            val c = accent((hue + i * 0.09f) % 1f, 0.95f)
            line(x - lx * 0.34f, y - 0.05f, z - lz * 0.34f, x + lx * 0.34f, y - 0.05f, z + lz * 0.34f, c)
            line(x - lx * 0.34f, y - 0.09f, z - lz * 0.34f, x + lx * 0.34f, y - 0.09f, z + lz * 0.34f, c)
            line(x - lx * 0.34f, y - 0.09f, z - lz * 0.34f, x - lx * 0.34f, y - 0.05f, z - lz * 0.34f, c)
            line(x + lx * 0.34f, y - 0.09f, z + lz * 0.34f, x + lx * 0.34f, y - 0.05f, z + lz * 0.34f, c)
            notes.add(Note(approxS, S_XYLO, scale[i % scale.size] * 0.75f))
        }
    }

    /** Snake track: alternating tight arcs downhill. */
    fun snake(hue: Float, weaves: Int = 4) {
        for (i in 0 until weaves) {
            arc(0.8f, if (i % 2 == 0) 1.9f else -1.9f, -0.26f)
        }
    }

    /** Pachinko field: the ball CAROMS pin to pin — sharp deflections at every
     *  pin it strikes, a plink per hit, pins drawn exactly where it lands. */
    fun pachinko(hue: Float, rows: Int = 5) {
        val c = accent(hue, 0.9f)
        val dim = accent(hue, 0.35f)
        val lx = cos(yaw); val lz = -sin(yaw)
        val fx = sin(yaw); val fz = cos(yaw)
        // backdrop pin lattice either side of the caroming path
        val px0 = x; val py0 = y; val pz0 = z
        for (r in 0 until rows) for (k in -2..2) {
            if (k == 0) continue
            val gx = px0 + fx * (0.34f + r * 0.34f) + lx * k * 0.3f
            val gy = py0 - 0.13f - r * 0.26f
            val gz = pz0 + fz * (0.34f + r * 0.34f) + lz * k * 0.3f
            line(gx - lx * 0.045f, gy, gz - lz * 0.045f, gx + lx * 0.045f, gy, gz + lz * 0.045f, dim)
            line(gx, gy - 0.045f, gz, gx, gy + 0.045f, gz, dim)
        }
        var side = 1f
        for (r in 0 until rows) {
            // a small ballistic flight that ENDS on a pin — visible impact
            val sx = x; val sy = y; val sz = z
            val n = 8
            for (k in 1..n) {
                val t = k.toFloat() / n
                emit(sx + fx * 0.34f * t + lx * side * 0.24f * t,
                    sy + 0.035f * 4f * t * (1f - t) - 0.26f * t,
                    sz + fz * 0.34f * t + lz * side * 0.24f * t)
            }
            // the struck pin, right under the ball's carom point
            line(x - lx * 0.06f, y - BALL_R * 0.9f, z - lz * 0.06f,
                x + lx * 0.06f, y - BALL_R * 0.9f, z + lz * 0.06f, c)
            line(x, y - BALL_R * 0.9f - 0.06f, z, x, y - BALL_R * 0.9f + 0.06f, z, c)
            notes.add(Note(approxS, S_CLACK, 1.25f + 0.22f * (r % 3), 0.95f))
            side = -side
        }
    }

    /** Newton's cradle: pause, clack, the far ball carries on. */
    fun newtonsCradle(hue: Float) {
        val s0 = approxS
        straight(0.55f, -0.02f)
        val c = steel(0.9f); val fr = accent(hue, 0.8f)
        val lx = cos(yaw); val lz = -sin(yaw)
        val fx = sin(yaw); val fz = cos(yaw)
        // frame + the three RESTING middle balls; the end pendulums are drawn
        // live by the renderer so they can really swing on impact
        val topY = y + 0.55f
        line(x - fx * 0.1f, topY, z - fz * 0.1f, x + fx * 1.1f, topY, z + fz * 1.1f, fr)
        for (i in 1 until 4) {
            val bx = x + fx * (0.3f + i * 0.12f); val bz = z + fz * (0.3f + i * 0.12f)
            line(bx, topY, bz, bx, y + BALL_R, bz, c)
            diamond(bx, y + BALL_R * 0.5f, bz, BALL_R * 0.75f, c)
        }
        mechs.add(Mech(M_CRADLE, x + fx * 0.55f, y, z + fz * 0.55f, yaw, 0.55f, 0.12f, hue))
        straight(1.1f, -0.04f)
        zones.add(Zone(s0 + 0.5f, approxS, Z_PAUSE, speed = 1.4f, pause = 0.55f))
        mechs.last().s0 = s0 + 0.5f; mechs.last().s1 = approxS
        // A cradle makes TWO sounds, and only the first was here: the strike as
        // the row takes the impact, then the far bob swinging back into the
        // stack about half a period later. One clack alone is why the whole
        // mechanism read as silent scenery. Pendulum length 0.42 m gives
        // T = 2*pi*sqrt(L/g) = 1.30 s, so the return lands ~0.65 s after
        // release; at the 1.4 m/s eject speed that is ~0.9 m further along.
        notes.add(Note(s0 + 0.6f, S_CLACK, 1f, 1f))
        notes.add(Note(s0 + 1.45f, S_CLACK, 0.9f, 0.75f))
    }

    /** Rocker arm / seesaw. */
    /**
     * The see-saw (Wippe). The ball used to travel a dead-straight ramp while
     * a beam was animated tilting underneath it — the plank chased the ball
     * instead of carrying it, and by the exit the drawn beam sat 0.31 m (two
     * and a half ball diameters) above where the deck should have been, with
     * the ball sailing along underneath it. The tilt also ran the wrong way,
     * lifting the end the ball was heading for.
     *
     * Now the plank's swept contact surface IS the centreline, so there is
     * nothing to keep in sync: the existing physics reads the tipping straight
     * out of the baked slope, and the renderer solves the same angle function
     * back from the rider, so beam and ball cannot disagree.
     *
     * The sequence is the one a real Wippe gives you. At rest the ENTRY end is
     * down against its stop — the ball's own weight ahead of the axle holds it
     * there — so the ball must climb the last 0.07 m to the pivot, slowing as
     * it goes. Past the axle its weight reverses the torque, the plank goes
     * over, and it accelerates down a 17 degree chute. No pause zone: the
     * hesitation is real, produced by the gradient, not scripted.
     */
    fun rockerArm(hue: Float) {
        val s0 = approxS
        val fx = sin(yaw); val fz = cos(yaw)
        val sx = x; val sy = y; val sz = z
        // Put the axle where the resting deck's entry end meets the cursor.
        val axleY = sy + ROCK_A * sin(ROCK_REST)
        mechs.add(Mech(M_ROCKER, sx + fx * ROCK_A, axleY, sz + fz * ROCK_A, yaw, ROCK_A, 0f, hue))
        val n = 30
        for (i in 1..n) {
            val u = i.toFloat() / n
            val xi = (2f * u - 1f) * ROCK_A          // signed distance from the axle
            val fwd = 2f * ROCK_A * u
            emit(sx + fx * fwd, axleY + xi * sin(rockerAngle(u)), sz + fz * fwd)
        }
        mechs.last().s0 = s0; mechs.last().s1 = approxS
        // the knock as the plank meets its lower stop, just after the tip
        notes.add(Note(s0 + ROCK_A * 1.7f, S_CLACK, 0.8f, 0.95f))
    }

    /** Counterweighted tipping bucket. */
    fun tippingBucket(hue: Float) {
        val s0 = approxS
        straight(0.35f, -0.3f)   // small drop into the bucket
        mechs.add(Mech(M_BUCKET, x, y, z, yaw, 0.34f, 0.5f, hue))
        straight(0.75f, -0.35f)
        zones.add(Zone(s0 + 0.3f, s0 + 0.75f, Z_PAUSE, speed = 1.2f, pause = 0.8f))
        mechs.last().s0 = s0 + 0.3f; mechs.last().s1 = s0 + 0.75f
        notes.add(Note(s0 + 0.35f, S_CLACK, 0.6f, 1f))
    }

    /** Gauss cannon: coil rings, sudden magnetic launch. */
    fun gaussCannon(hue: Float) {
        val s0 = approxS
        val c = brass(0.95f)
        val fx = sin(yaw); val fz = cos(yaw)
        val sx = x; val sy = y; val sz = z
        for (i in 0 until 7) {
            ringT(sx + fx * (0.12f + i * 0.13f), sy, sz + fz * (0.12f + i * 0.13f), yaw, BALL_R * 1.7f, 10, c)
        }
        straight(1.1f, 0.02f)  // launches slightly upward-flat
        zones.add(Zone(s0 + 0.1f, s0 + 1.1f, Z_BOOST, speed = 5.2f))
        notes.add(Note(s0 + 0.12f, S_DING, 1.1f, 1f))
        straight(1.9f, -0.06f)
    }

    /** Ferris wheel ride down the far side. */
    fun ferrisWheel(hue: Float) {
        val s0 = approxS
        val r = 0.95f
        val fx = sin(yaw); val fz = cos(yaw)
        // wheel center ahead, hub at track height minus r (ride enters at top)
        val cx = x + fx * 0.2f; val cy = y - r; val cz = z + fz * 0.2f
        mechs.add(Mech(M_FERRIS, cx, cy, cz, yaw, r, 0f, hue))
        // centerline: half circle from top, down the forward side
        val n = 40
        val sRide = approxS
        for (i in 1..n) {
            val t = i.toFloat() / n
            val th = t * PI.toFloat()
            emit(cx + fx * sin(th) * r, cy + cos(th) * r, cz + fz * sin(th) * r)
        }
        zones.add(Zone(sRide, approxS, Z_FERRIS, speed = 0.85f))
        mechs.last().s0 = sRide; mechs.last().s1 = approxS
        notes.add(Note(s0 + 0.2f, S_RATCHET, 0.8f, 0.7f))

        // The ride is half a circle, so it necessarily ends travelling BACKWARDS
        // — at the bottom of a wheel you are moving opposite to the way you went
        // in at the top. The old code then ran a straight along the ORIGINAL
        // yaw, which put a true 180 degree cusp in the centreline: the sharpest
        // corner on the level, and not something the arc smoothing can absorb,
        // because the reversal is in the emitted points themselves.
        //
        // Tell the cursor the truth about which way the ball is going, then roll
        // it out of the gondola through a half-circle run-out that brings it
        // back to the travel direction. Physically this is the right story too:
        // the wheel CARRIES the ball (its span is a Z_FERRIS zone), so the
        // bottom of the ride is a delivery, and what follows is the ball rolling
        // away from the gondola and curving off.
        yaw += PI.toFloat()
        yaw = ((yaw % (2f * PI.toFloat())) + 2f * PI.toFloat()) % (2f * PI.toFloat())
        notes.add(Note(approxS + 0.05f, S_CLACK, 0.9f, 0.8f))   // set down
        arc(0.42f, PI.toFloat(), -0.12f)
    }

    /** Interlocking double spiral: ride one helix; its twin interleaves in art. */
    fun doubleSpiral(hue: Float) {
        val r = 0.8f; val turns = 2.2f; val drop = 1.15f
        // Same centre as helix() below computes for itself — these two MUST
        // agree or the decorative twin floats a diameter away from the rail
        // the ball actually rides.
        val cx = x + sin(yaw + PI.toFloat() / 2) * r
        val cz = z + cos(yaw + PI.toFloat() / 2) * r
        val a0 = yaw + -PI.toFloat() / 2
        val sy = y
        helix(r, turns, drop, clockwise = true)
        // the interlocked twin, offset half a turn
        val c = accent(hue, 0.65f)
        val n = (turns * 40).toInt()
        var pxp = 0f; var pyp = 0f; var pzp = 0f
        for (i in 0..n) {
            val t = i.toFloat() / n
            val a = a0 + (turns * t + 0.5f) * 2f * PI.toFloat()
            val hx = cx + sin(a) * r; val hy = sy - drop * t; val hz = cz + cos(a) * r
            if (i > 0) line(pxp, pyp, pzp, hx, hy, hz, c)
            if (i % 6 == 0) line(hx, hy, hz, cx, hy, cz, floatArrayOf(c[0], c[1], c[2], 0.2f))
            pxp = hx; pyp = hy; pzp = hz
        }
    }

    /** Archimedes screw: slow rotating lift. */
    fun archimedesScrew(hue: Float, rise: Float = 1.0f) {
        val s0 = approxS
        val len = 1.9f
        val fx = sin(yaw); val fz = cos(yaw)
        mechs.add(Mech(M_SCREW, x, y, z, yaw, len, rise, hue))
        straight(len, rise, step = 0.06f)
        zones.add(Zone(s0, approxS, Z_LIFT, speed = 0.45f))
        var tick = 0.2f
        while (tick < len) {
            notes.add(Note(s0 + tick, S_RATCHET, 1.18f + (tick % 0.09f), 0.6f))
            tick += 0.38f
        }
    }

    /** Trommel: the spinning drum TUMBLES the ball — the wall carries it up,
     *  gravity drops it back, so it corkscrews through in surges and slips,
     *  knocking against the cage as it goes. */
    fun trommel(hue: Float) {
        val s0 = approxS
        val len = 1.6f
        mechs.add(Mech(M_TROMMEL, x, y, z, yaw, len, 0.55f, hue))
        val fx = sin(yaw); val fz = cos(yaw)
        val lx = cos(yaw); val lz = -sin(yaw)
        val sx = x; val sy = y; val sz = z
        // The ball used to orbit the barrel 2.75 times while crossing it — about
        // 10 rad/s — inside a drum the renderer turns at 1.5. It outran its own
        // cage by nearly seven to one, and at 0.09 m of sway inside a 0.55 m
        // barrel it never went near the wall it was supposedly carried by. Three
        // quarters of a turn, wide enough to actually ride the wall, is both
        // slow enough for the cage to keep up and far easier to read.
        //
        // The sin-squared envelope matters as much: the sway used to start at
        // full rate, so the ball entered the drum 44 degrees off its axis. Tapered
        // in and out, entry and exit deviation fall to about 6 degrees.
        val n = 40
        for (i in 1..n) {
            val t = i.toFloat() / n
            val ph = t * 1.5f * PI.toFloat()
            val env = sin(PI.toFloat() * t) * sin(PI.toFloat() * t)
            val wob = sin(ph) * 0.30f * env           // carried up the wall...
            val lift = (1f - cos(ph)) * 0.14f * env   // ...and dropped back
            emit(sx + fx * len * t + lx * wob, sy - 0.16f * t + lift, sz + fz * len * t + lz * wob)
        }
        val sEnd = approxS
        // Without this the mech has s0 = s1 = -1, so ridingBall() returns null by
        // construction and the drum could never lock to the ball inside it.
        mechs.last().s0 = s0; mechs.last().s1 = sEnd
        // surge with the wall, slip, surge again
        zones.add(Zone(s0, s0 + (sEnd - s0) * 0.33f, Z_FERRIS, speed = 1.5f))
        zones.add(Zone(s0 + (sEnd - s0) * 0.33f, s0 + (sEnd - s0) * 0.66f, Z_FERRIS, speed = 0.85f))
        zones.add(Zone(s0 + (sEnd - s0) * 0.66f, sEnd, Z_FERRIS, speed = 1.45f))
        var knock = 0.22f
        while (knock < sEnd - s0) {
            notes.add(Note(s0 + knock, S_CLACK, 0.5f + (knock % 0.2f), 0.55f))
            knock += 0.34f
        }
    }

    /** Decorative spinning propeller beside the track. */
    fun propeller(hue: Float) {
        val lx = cos(yaw); val lz = -sin(yaw)
        mechs.add(Mech(M_PROP, x + lx * 0.55f, y + 0.45f, z + lz * 0.55f, yaw, 0.42f, 0f, hue))
    }

    /** The chain elevator: closes the loop back up to the intake. The approach
     *  hugs the cylinder wall (no chord across the panorama's interior). */
    fun elevator(intakeX: Float, intakeY: Float, intakeZ: Float, hue: Float): Float {
        val sBase = approxS
        val ri = hypot(intakeX, intakeZ)
        val r0 = hypot(x, z)
        if (ri > 0.8f && r0 > 0.8f) {
            // wall-hugging arc forward to the intake's azimuth
            val thNow = atan2f(x, z)
            val thIn = atan2f(intakeX, intakeZ)
            var d = thIn - thNow
            while (d <= 0.08f) d += 2f * PI.toFloat()
            cylArc(ri, d, -0.02f)
        } else {
            val dx = intakeX - x; val dz = intakeZ - z
            val dist = hypot(dx, dz)
            if (dist > 0.4f) { yaw = atan2f(dx, dz); straight(dist - 0.25f, -0.02f) }
        }
        val sClimb = approxS
        val baseY = y
        val rise = intakeY + DROP_H * 0.35f - baseY
        mechs.add(Mech(M_ELEV, x, baseY, z, yaw, rise, 0.4f, hue))
        // vertical climb
        val n = (rise / 0.07f).toInt().coerceAtLeast(6)
        val sx = x; val sz = z
        for (i in 1..n) emit(sx, baseY + rise * i / n, sz)
        zones.add(Zone(sClimb - 0.4f, approxS, Z_LIFT, speed = 0.75f))
        // the chain ticks the whole way up
        var tick = 0.25f
        while (tick < rise) {
            notes.add(Note(sClimb + tick, S_RATCHET, 0.96f + (tick % 0.13f), 0.75f))
            tick += 0.42f
        }
        // crest and short chute back to the intake point
        straight(0.3f, -0.1f)
        val ddx = intakeX - x; val ddz = intakeZ - z
        val dd = hypot(ddx, ddz)
        if (dd > 0.05f) { yaw = atan2f(ddx, ddz); straight(dd, intakeY - y) }
        notes.add(Note(approxS - 0.2f, S_DROP, 1f, 0.9f))
        return sBase
    }

    // ------------------------------------------------------- art helpers

    // ---------------------------------------------------- solid mesh helpers

    /** One ring of TUBE_SIDES points around a rail centre, into `out` as xyz+nrm. */
    private fun railRing(cx: Float, cy: Float, cz: Float,
                         ax: Float, ay: Float, az: Float,
                         bx: Float, by: Float, bz: Float, out: FloatArray) {
        for (k in 0 until TUBE_SIDES) {
            val a = k * 2f * PI.toFloat() / TUBE_SIDES
            val ca = cos(a); val sa = sin(a)
            val nx = ax * ca + bx * sa
            val ny = ay * ca + by * sa
            val nz = az * ca + bz * sa
            val o = k * 6
            out[o] = cx + nx * TUBE_R; out[o + 1] = cy + ny * TUBE_R; out[o + 2] = cz + nz * TUBE_R
            out[o + 3] = nx; out[o + 4] = ny; out[o + 5] = nz
        }
    }

    /** Bridge two rings with a band of quads (two triangles each). */
    private fun tubeSpan(p: FloatArray, q: FloatArray, mat: Int) {
        for (k in 0 until TUBE_SIDES) {
            val k2 = (k + 1) % TUBE_SIDES
            val a = k * 6; val b = k2 * 6
            tri(p, a, q, a, q, b, mat)
            tri(p, a, q, b, p, b, mat)
        }
    }

    private fun tri(r0: FloatArray, o0: Int, r1: FloatArray, o1: Int, r2: FloatArray, o2: Int, mat: Int) {
        push(r0, o0, mat); push(r1, o1, mat); push(r2, o2, mat)
    }

    private fun push(r: FloatArray, o: Int, mat: Int) {
        mesh.add(r[o]); mesh.add(r[o + 1]); mesh.add(r[o + 2])
        mesh.add(r[o + 3]); mesh.add(r[o + 4]); mesh.add(r[o + 5])
        mesh.add(mat.toFloat())
    }

    private fun ringY(cx: Float, cy: Float, cz: Float, r: Float, segs: Int, c: FloatArray) {
        var pa = 0f
        for (i in 1..segs) {
            val a = i * 2f * PI.toFloat() / segs
            line(cx + sin(pa) * r, cy, cz + cos(pa) * r, cx + sin(a) * r, cy, cz + cos(a) * r, c)
            pa = a
        }
    }

    /** Ring perpendicular to a horizontal travel direction (tunnel hoop). */
    private fun ringT(cx: Float, cy: Float, cz: Float, yawDir: Float, r: Float, segs: Int, c: FloatArray) {
        val lx = cos(yawDir); val lz = -sin(yawDir)
        var pa = 0f
        for (i in 1..segs) {
            val a = i * 2f * PI.toFloat() / segs
            line(cx + lx * sin(pa) * r, cy + cos(pa) * r, cz + lz * sin(pa) * r,
                cx + lx * sin(a) * r, cy + cos(a) * r, cz + lz * sin(a) * r, c)
            pa = a
        }
    }

    private fun diamond(cx: Float, cy: Float, cz: Float, r: Float, c: FloatArray) {
        line(cx, cy + r, cz, cx + r, cy, cz, c); line(cx + r, cy, cz, cx, cy - r, cz, c)
        line(cx, cy - r, cz, cx - r, cy, cz, c); line(cx - r, cy, cz, cx, cy + r, cz, c)
    }

    // ------------------------------------------------------- build

    /**
     * Rails + tunnel hoops along the whole centerline, then bake everything
     * into the immutable MachineModel.
     */
    fun build(finishS: Float, intakePos: FloatArray): MachineModel {
        val n = pts.size / 3
        val cum = FloatArray(n)
        var acc = 0f
        for (i in 1 until n) {
            val dx = pts[i * 3] - pts[(i - 1) * 3]
            val dy = pts[i * 3 + 1] - pts[(i - 1) * 3 + 1]
            val dz = pts[i * 3 + 2] - pts[(i - 1) * 3 + 2]
            acc += kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            cum[i] = acc
        }
        // solid-mesh scratch: two 6-sided rail rings, swept into tubes
        val ringA = FloatArray(TUBE_SIDES * 6); val prevA = FloatArray(TUBE_SIDES * 6)
        val ringB = FloatArray(TUBE_SIDES * 6); val prevB = FloatArray(TUBE_SIDES * 6)
        var haveRing = false
        var chunkS0 = 0f
        var chunkVert0 = 0
        fun closeChunk(sEnd: Float) {
            val vEnd = mesh.size / 7
            if (vEnd <= chunkVert0) return
            // bounding sphere of the chunk, for the distance test
            var mnx = Float.MAX_VALUE; var mny = Float.MAX_VALUE; var mnz = Float.MAX_VALUE
            var mxx = -Float.MAX_VALUE; var mxy = -Float.MAX_VALUE; var mxz = -Float.MAX_VALUE
            for (v in chunkVert0 until vEnd) {
                val o = v * 7
                mnx = minOf(mnx, mesh[o]); mxx = maxOf(mxx, mesh[o])
                mny = minOf(mny, mesh[o + 1]); mxy = maxOf(mxy, mesh[o + 1])
                mnz = minOf(mnz, mesh[o + 2]); mxz = maxOf(mxz, mesh[o + 2])
            }
            val ccx = (mnx + mxx) * 0.5f; val ccy = (mny + mxy) * 0.5f; val ccz = (mnz + mxz) * 0.5f
            val rad = kotlin.math.sqrt(
                (mxx - ccx) * (mxx - ccx) + (mxy - ccy) * (mxy - ccy) + (mxz - ccz) * (mxz - ccz))
            chunks.add(MeshChunk(chunkS0, sEnd, chunkVert0, vEnd - chunkVert0, ccx, ccy, ccz, rad))
            chunkVert0 = vEnd
        }

        // rails: twin lines offset laterally, slightly below center; hoops every 0.55m
        val rail = steel(0.55f)
        val railGlow = steel(0.28f)
        val hoop = floatArrayOf(0.45f, 0.62f, 0.8f, 0.20f)
        var lastLx = 1f; var lastLz = 0f
        var nextHoop = 0.4f
        var px = 0f; var py = 0f; var pz = 0f
        var pr1 = FloatArray(0); var pr2 = FloatArray(0)
        for (i in 0 until n) {
            val cx = pts[i * 3]; val cy = pts[i * 3 + 1]; val cz = pts[i * 3 + 2]
            // tangent
            val i0 = if (i > 0) i - 1 else i; val i1 = if (i < n - 1) i + 1 else i
            var tx = pts[i1 * 3] - pts[i0 * 3]; var ty = pts[i1 * 3 + 1] - pts[i0 * 3 + 1]; var tz = pts[i1 * 3 + 2] - pts[i0 * 3 + 2]
            val tl = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-5f)
            tx /= tl; ty /= tl; tz /= tl
            // lateral = up × tangent, with fallback when near-vertical
            var lx = tz; var lz = -tx
            val ll = hypot(lx, lz)
            if (ll < 0.25f) { lx = lastLx; lz = lastLz } else { lx /= ll; lz /= ll; lastLx = lx; lastLz = lz }
            val off = BALL_R * 0.8f
            val dropY = BALL_R * 0.5f
            val r1 = floatArrayOf(cx + lx * off, cy - dropY, cz + lz * off)
            val r2 = floatArrayOf(cx - lx * off, cy - dropY, cz - lz * off)
            if (i > 0 && pr1.isNotEmpty()) {
                line(pr1[0], pr1[1], pr1[2], r1[0], r1[1], r1[2], rail)
                line(pr2[0], pr2[1], pr2[2], r2[0], r2[1], r2[2], rail)
                // sleepers occasionally
                if (i % 9 == 0) line(r1[0], r1[1], r1[2], r2[0], r2[1], r2[2], railGlow)
            }

            // Solid twin rails, swept as hex tubes. Emitted from the SAME
            // centres as the lines above, so wherever a tube is drawn it sits
            // exactly over its own neon line and hides it by depth — the swap
            // from glowing to solid needs no per-line bookkeeping at all.
            // Every RING_STRIDE samples keeps the triangle count sane; the
            // centreline is denser than a rail needs to be.
            if (i % RING_STRIDE == 0) {
                // binormal: perpendicular to both tangent and lateral
                val bnx = ty * lz - tz * 0f
                val bny = tz * lx - tx * lz
                val bnz = 0f * tx - ty * lx
                val bl = kotlin.math.sqrt(bnx * bnx + bny * bny + bnz * bnz).coerceAtLeast(1e-4f)
                val ex = bnx / bl; val ey = bny / bl; val ez = bnz / bl
                railRing(r1[0], r1[1], r1[2], lx, 0f, lz, ex, ey, ez, ringA)
                railRing(r2[0], r2[1], r2[2], lx, 0f, lz, ex, ey, ez, ringB)
                if (haveRing) {
                    tubeSpan(prevA, ringA, MAT_STEEL)
                    tubeSpan(prevB, ringB, MAT_STEEL)
                }
                System.arraycopy(ringA, 0, prevA, 0, ringA.size)
                System.arraycopy(ringB, 0, prevB, 0, ringB.size)
                haveRing = true
                // close a chunk every CHUNK_M metres of track
                if (cum[i] - chunkS0 >= CHUNK_M) { closeChunk(cum[i]); chunkS0 = cum[i] }
            }
            pr1 = r1; pr2 = r2
            // tunnel hoops — the full wired tunnel
            if (cum[i] >= nextHoop) {
                nextHoop += 0.55f
                var ux = -lz * ty; var uy = ll; var uz = lx * ty // approx up-in-plane; keep simple circle around tangent
                // build circle basis (lateral, binormal)
                val bx = ty * lz - tz * 0f; // simplified below
                // simpler: use lateral (lx,0,lz) and computed binormal = tangent × lateral
                val nx = ty * lz - tz * 0f
                val bnx = ty * lz; val bny = tz * lx - tx * lz; val bnz = -ty * lx
                val bl = kotlin.math.sqrt(bnx * bnx + bny * bny + bnz * bnz).coerceAtLeast(1e-4f)
                val hbx = bnx / bl; val hby = bny / bl; val hbz = bnz / bl
                val hr = BALL_R * 1.65f
                var paX = cx + lx * hr; var paY = cy; var paZ = cz + lz * hr
                val segs = 10
                for (k in 1..segs) {
                    val a = k * 2f * PI.toFloat() / segs
                    val qx = cx + (lx * cos(a) + hbx * sin(a)) * hr
                    val qy = cy + (hby * sin(a)) * hr
                    val qz = cz + (lz * cos(a) + hbz * sin(a)) * hr
                    line(paX, paY, paZ, qx, qy, qz, hoop)
                    paX = qx; paY = qy; paZ = qz
                }
            }
            px = cx; py = cy; pz = cz
        }
        // bbox
        var mnx = Float.MAX_VALUE; var mny = Float.MAX_VALUE; var mnz = Float.MAX_VALUE
        var mxx = -Float.MAX_VALUE; var mxy = -Float.MAX_VALUE; var mxz = -Float.MAX_VALUE
        for (i in 0 until n) {
            mnx = minOf(mnx, pts[i * 3]); mxx = maxOf(mxx, pts[i * 3])
            mny = minOf(mny, pts[i * 3 + 1]); mxy = maxOf(mxy, pts[i * 3 + 1])
            mnz = minOf(mnz, pts[i * 3 + 2]); mxz = maxOf(mxz, pts[i * 3 + 2])
        }
        val ctr = floatArrayOf((mnx + mxx) / 2, (mny + mxy) / 2, (mnz + mxz) / 2)
        val rad = maxOf(mxx - mnx, mxy - mny, mxz - mnz) * 0.62f + 1.2f
        closeChunk(acc)          // whatever is left after the last CHUNK_M boundary
        return MachineModel(
            pts.toFloatArray(), cum, acc, art.toFloatArray(), art.size / 7,
            mesh.toFloatArray(), chunks,
            mechs, zones.sortedBy { it.s0 }, notes.sortedBy { it.s },
            finishS, intakePos, ctr, rad
        )
    }

    companion object {
        // See-saw geometry. ONE definition, shared by the builder that bakes
        // the ball's path and the renderer that draws the plank, so the two
        // can never drift apart — which is precisely how the old version ended
        // up with the beam a quarter-metre away from the ball.
        const val ROCK_A = 0.7f          // half-length of the plank
        const val ROCK_REST = 0.10f      // entry end down: the ball climbs to the axle
        const val ROCK_TIP = -0.30f      // gone over: a 17-degree chute out

        /** Plank angle as a function of the ball's progress across it. */
        fun rockerAngle(u: Float): Float {
            if (u <= 0.5f) return ROCK_REST                 // held on its stop
            val k = ((u - 0.5f) / 0.32f).coerceIn(0f, 1f)   // over-centre, then the far stop
            return ROCK_REST + (ROCK_TIP - ROCK_REST) * (k * k * (3f - 2f * k))
        }

        // Solid rail tubes: 6 sides is enough at this scale, and a ring every
        // other centreline sample (~0.18 m) still curves cleanly through a
        // loop while keeping the triangle count in budget.
        const val TUBE_SIDES = 6
        const val TUBE_R = 0.026f
        const val RING_STRIDE = 2
        const val CHUNK_M = 4f          // metres of track per drawable chunk

        fun hsv(h: Float): FloatArray {
            val h6 = ((h % 1f + 1f) % 1f) * 6f
            val i = h6.toInt(); val f = h6 - i
            val p = 0.15f; val q = 1f - f * 0.85f; val t = 0.15f + f * 0.85f
            return when (i % 6) {
                0 -> floatArrayOf(1f, t, p); 1 -> floatArrayOf(q, 1f, p)
                2 -> floatArrayOf(p, 1f, t); 3 -> floatArrayOf(p, q, 1f)
                4 -> floatArrayOf(t, p, 1f); else -> floatArrayOf(1f, p, q)
            }
        }
        fun atan2f(a: Float, b: Float) = kotlin.math.atan2(a, b)
    }
}

private fun atan2f(a: Float, b: Float) = kotlin.math.atan2(a, b)
