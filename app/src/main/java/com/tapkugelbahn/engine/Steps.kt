package com.tapkugelbahn.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The catalogue of swappable steps.
 *
 * The machine is becoming ONE editable loop rather than four fixed ones, so
 * every mechanism has to be addressable by name, emittable into a slot, and —
 * the part that actually decides the design — MEASURABLE.
 *
 * A slot can only accept a step whose footprint matches, because the loop has
 * to close: the ball must leave a slot at the height and heading the next slot
 * expects, or the track cannot come back round to the elevator. Rather than
 * guess those numbers, `measure()` runs each generator against a real
 * TrackBuilder from a known cursor and reports what it actually did. The
 * grouping falls out of the measurements instead of being asserted.
 */
object Steps {

    /** What a step does to the cursor, in the frame it started in. */
    data class Footprint(
        val name: String,
        val fwd: Float,      // advance along the entry heading
        val lat: Float,      // sideways displacement
        val dy: Float,       // height change (negative = descends)
        val arc: Float,      // track length the ball travels
        val dYaw: Float      // heading change, degrees
    )

    /** id → (display name, emitter). Order is the palette order. */
    val CATALOGUE: List<Triple<Int, String, TrackBuilder.() -> Unit>> = listOf(
        Triple(0,  "GRAVITY FUNNEL") { gravityFunnel(0.55f) },
        Triple(1,  "XYLOPHONE")      { xylophone(0.12f, 6) },
        Triple(2,  "SNAKE")          { snake(0.3f, 3) },
        Triple(3,  "PACHINKO")       { pachinko(0.85f, 5) },
        Triple(4,  "CRADLE")         { newtonsCradle(0.6f) },
        Triple(5,  "SEE-SAW")        { rockerArm(0.45f) },
        Triple(6,  "TIPPING BUCKET") { tippingBucket(0.98f) },
        Triple(7,  "GAUSS CANNON")   { gaussCannon(0.13f) },
        Triple(8,  "FERRIS WHEEL")   { ferrisWheel(0.7f) },
        Triple(9,  "DOUBLE SPIRAL")  { doubleSpiral(0.75f) },
        Triple(10, "SCREW")          { archimedesScrew(0.55f, 0.85f) },
        Triple(11, "TROMMEL")        { trommel(0.32f) },
        Triple(12, "LOOP")           { loop(0.55f) },
        Triple(13, "CORKSCREW")      { helix(0.85f, 2f, 1.0f) },
    )

    /**
     * Teaching order. Each machine takes a prefix of this list, so a step is
     * always introduced alongside everything before it and the sequence builds
     * rather than jumping about: the plain gravity tricks first, then the ones
     * that strike or hold the ball, then the big rotating machinery.
     */
    private val PROGRESSION = intArrayOf(
        0,  // gravity funnel — the whirl
        1,  // xylophone     — it sings
        12, // loop          — the first spectacle
        2,  // snake         — weaving
        13, // corkscrew     — winding descent
        3,  // pachinko      — caroms
        5,  // see-saw       — it teeters
        6,  // tipping bucket— it carries
        4,  // cradle        — it strikes
        11, // trommel       — it tumbles
        7,  // gauss cannon  — it fires
        8,  // ferris wheel  — it lifts and delivers
        9,  // double spiral — the long fall
        10  // screw         — the climb
    )

    const val LEVELS = 3
    val NAMES = arrayOf("ERSTE BAHN", "PENDELWERK", "DAS GROSSE WERK")

    /**
     * Which steps a level contains: 70% of the catalogue, then 90%, then all
     * of it. Always a prefix of PROGRESSION, so every machine is the previous
     * one plus more rather than a different selection.
     */
    fun stepsFor(level: Int): IntArray {
        val frac = when (level.coerceIn(1, LEVELS)) {
            1 -> 0.70f
            2 -> 0.90f
            else -> 1.0f
        }
        val n = Math.round(PROGRESSION.size * frac).coerceIn(1, PROGRESSION.size)
        return PROGRESSION.copyOfRange(0, n)
    }

    fun nameOf(id: Int): String = CATALOGUE.first { it.first == id }.second

    /** Emit one step, by id, into a builder at its current cursor. */
    fun emit(b: TrackBuilder, id: Int) {
        CATALOGUE.first { it.first == id }.third.invoke(b)
    }

    /**
     * Run every generator in isolation and report what it did to the cursor.
     * Measured against the real builder, so it cannot drift from the code the
     * machine is actually made of.
     */
    fun measure(): List<Footprint> = CATALOGUE.map { (_, name, emit) ->
        val b = TrackBuilder()
        b.cylStart(3.0f, 0f, 5f)
        val x0 = b.x; val y0 = b.y; val z0 = b.z; val yaw0 = b.yaw
        b.emit()
        // decompose the displacement in the frame the step started in
        val dx = b.x - x0; val dz = b.z - z0
        val fx = sin(yaw0); val fz = cos(yaw0)
        val lx = cos(yaw0); val lz = -sin(yaw0)
        var dYaw = Math.toDegrees(((b.yaw - yaw0).toDouble())).toFloat()
        while (dYaw > 180f) dYaw -= 360f
        while (dYaw < -180f) dYaw += 360f
        val model = b.build(0f, floatArrayOf(x0, y0, z0))
        Footprint(
            name,
            fwd = dx * fx + dz * fz,
            lat = dx * lx + dz * lz,
            dy = b.y - y0,
            arc = model.length,
            dYaw = dYaw
        )
    }

    /** What each machine is made of — checked against the 70/90/100 split. */
    fun levelReport(): String {
        val sb = StringBuilder("LEVELS\n")
        for (l in 1..LEVELS) {
            val ids = stepsFor(l)
            sb.append(String.format("  L%d %-16s %2d/%2d steps (%.0f%%): ",
                l, NAMES[l - 1], ids.size, PROGRESSION.size,
                100f * ids.size / PROGRESSION.size))
            sb.append(ids.joinToString(", ") { nameOf(it) })
            sb.append('\n')
        }
        return sb.toString()
    }

    /** One-line-per-step dump, for reading footprints off the device log. */
    fun report(): String {
        val sb = StringBuilder("STEP FOOTPRINTS (fwd / lat / dy / arc / dYaw)\n")
        for (f in measure()) {
            sb.append(
                String.format(
                    "  %-15s fwd %6.2f  lat %6.2f  dy %6.2f  arc %6.2f  dYaw %7.1f\n",
                    f.name, f.fwd, f.lat, f.dy, f.arc, f.dYaw
                )
            )
        }
        return sb.toString()
    }
}
