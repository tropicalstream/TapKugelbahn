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
        var th0 = atan2f(x, z)
        val n = (abs(dTheta) * maxOf(r0, R) / 0.075f).toInt().coerceAtLeast(8)
        val y0 = y
        for (i in 1..n) {
            val t = i.toFloat() / n
            val ease = t * t * (3f - 2f * t)
            val r = r0 + (R - r0) * ease
            val th = th0 + dTheta * t
            emit(sin(th) * r, y0 + dy * t, cos(th) * r)
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
    fun helix(r: Float, turns: Float, drop: Float, clockwise: Boolean = true) {
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
        for (k in 0 until 4) {
            zones.add(Zone(sHelix + helixLen * k / 4f, sHelix + helixLen * (k + 1) / 4f,
                Z_FERRIS, speed = 1.35f + k * 0.5f))
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
        val cx0 = x + sin(yaw) * 1.05f
        val cz0 = z + cos(yaw) * 1.05f
        val topY = y
        val sSpiral = approxS
        // spiral in: radius 1.05 → 0.2 over 2.6 turns, dropping 0.9
        val turns = 2.6f; val n = (turns * 56).toInt()
        val a0 = yaw + PI.toFloat()
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
        notes.add(Note(s0 + 0.6f, S_CLACK, 1f, 1f))
    }

    /** Rocker arm / seesaw. */
    fun rockerArm(hue: Float) {
        val s0 = approxS
        val fx = sin(yaw); val fz = cos(yaw)
        mechs.add(Mech(M_ROCKER, x + fx * 0.7f, y - 0.05f, z + fz * 0.7f, yaw, 0.7f, 0f, hue))
        straight(1.4f, -0.12f)
        zones.add(Zone(s0 + 0.4f, approxS - 0.3f, Z_PAUSE, speed = 1.0f, pause = 0.45f))
        mechs.last().s0 = s0; mechs.last().s1 = approxS
        notes.add(Note(s0 + 0.55f, S_CLACK, 0.8f, 0.9f))
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
        straight(0.5f, -0.05f)
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
        val n = 28
        for (i in 1..n) {
            val t = i.toFloat() / n
            val ph = t * 5.5f * PI.toFloat()
            val wob = sin(ph) * 0.09f                 // carried up the wall...
            val lift = (1f - cos(ph)) * 0.05f         // ...and dropped back
            emit(sx + fx * len * t + lx * wob, sy - 0.16f * t + lift, sz + fz * len * t + lz * wob)
        }
        val sEnd = approxS
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
        return MachineModel(
            pts.toFloatArray(), cum, acc, art.toFloatArray(), art.size / 7,
            mechs, zones.sortedBy { it.s0 }, notes.sortedBy { it.s },
            finishS, intakePos, ctr, rad
        )
    }

    companion object {
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
