package com.tapkugelbahn.engine

/**
 * The four machines, each ONE closed loop winding counterclockwise around a
 * central axis — a panoramic cylindrical route, made to be watched from
 * inside as much as outside. Every level introduces at least five new
 * mechanisms and is longer and taller than the last; the finale wraps more
 * than three full revolutions floor-to-ceiling with excursions to an inner
 * ring — the room-spanning sculpture.
 *
 * Traversal ≈ length / ~1.5 m/s: level 1 ≈ one minute, growing from there.
 */
object Machine {

    const val LEVELS = 4
    val NAMES = arrayOf("ERSTE BAHN", "PENDELWERK", "DREHWERK", "DAS GROSSE WERK")

    fun build(level: Int): MachineModel {
        val b = TrackBuilder()
        when (level.coerceIn(1, LEVELS)) {
            1 -> {
                // R 2.0, ~2.1 turns — funnel, xylophone, snake, loop, corkscrew.
                val r = 2.0f
                b.cylStart(r, 0f, 3.3f)
                val ix = b.x; val iy = b.y; val iz = b.z
                b.intakeFunnel(0.08f)
                b.cylArc(r, 1.2f, -0.25f)
                b.gravityFunnel(0.55f)
                b.cylArc(r, 1.3f, -0.2f)
                b.xylophone(0.12f)
                b.cylArc(r, 1.1f, -0.2f)
                b.snake(0.3f, 3)
                b.cylArc(r, 0.8f, -0.3f)           // ramp
                b.loop(0.55f)
                b.propeller(0.62f)
                b.cylArc(r, 1.3f, -0.25f)
                b.helix(0.8f, 2f, 0.85f)          // corkscrew bulge
                b.cylArc(r, 1.4f, -0.25f)
                b.snake(0.3f, 2)
                b.cylArc(r, 1.2f, -0.2f)
                val fs = b.elevator(ix, iy, iz, 0.14f)
                return b.build(fs, floatArrayOf(ix, iy, iz))
            }
            2 -> {
                // R 2.4, ~2.4 turns — + pachinko, rocker, bucket, cradle, double spiral.
                val r = 2.4f
                b.cylStart(r, 0f, 4.3f)
                val ix = b.x; val iy = b.y; val iz = b.z
                b.intakeFunnel(0.08f)
                b.cylArc(r, 1.1f, -0.2f)
                b.xylophone(0.12f, 5)
                b.cylArc(r, 1.2f, -0.2f)
                b.pachinko(0.85f)
                b.cylArc(r, 1.1f, -0.15f)
                b.rockerArm(0.45f)
                b.cylArc(r, 1.2f, -0.25f)
                b.tippingBucket(0.98f)
                b.cylArc(r, 1.2f, -0.2f)
                b.newtonsCradle(0.6f)
                b.cylArc(r, 0.9f, -0.3f)
                b.loop(0.5f)
                b.propeller(0.2f)
                b.cylArc(r, 1.3f, -0.25f)
                b.doubleSpiral(0.75f)
                b.cylArc(r, 1.4f, -0.25f)
                b.snake(0.3f, 3)
                b.cylArc(r, 1.3f, -0.2f)
                val fs = b.elevator(ix, iy, iz, 0.14f)
                return b.build(fs, floatArrayOf(ix, iy, iz))
            }
            3 -> {
                // R 2.9, ~2.7 turns — + ferris wheel, gauss, screw, trommel.
                val r = 2.9f
                b.cylStart(r, 0f, 5.3f)
                val ix = b.x; val iy = b.y; val iz = b.z
                b.intakeFunnel(0.08f)
                b.cylArc(r, 1.1f, -0.25f)
                b.gravityFunnel(0.5f)
                b.cylArc(r, 1.2f, -0.2f)
                b.ferrisWheel(0.7f)
                b.cylArc(r, 1.1f, -0.15f)
                b.gaussCannon(0.13f)
                b.cylArc(r, 1.2f, -0.25f)
                b.pachinko(0.85f, 4)
                b.cylArc(r, 1.0f, -0.15f)
                b.trommel(0.32f)
                b.cylArc(r, 1.2f, -0.2f)
                b.xylophone(0.1f, 6)
                b.cylArc(r, 1.0f, -0.1f)
                b.archimedesScrew(0.55f, 0.85f)     // climbs!
                b.cylArc(r, 0.45f, -0.5f)
                b.loop(0.6f)
                b.propeller(0.62f); b.propeller(0.9f)
                b.cylArc(r, 1.3f, -0.3f)
                b.helix(0.85f, 2f, 1.1f)
                b.cylArc(r, 1.35f, -0.3f)
                b.newtonsCradle(0.6f)
                b.cylArc(r, 1.3f, -0.25f)
                val fs = b.elevator(ix, iy, iz, 0.14f)
                return b.build(fs, floatArrayOf(ix, iy, iz))
            }
            else -> {
                // R 3.6 with excursions to an inner ring — ~3.4 turns, floor to
                // ceiling: the room-spanning finale with everything in play.
                val r = 3.6f; val rIn = 2.2f
                b.cylStart(r, 0f, 6.5f)
                val ix = b.x; val iy = b.y; val iz = b.z
                b.intakeFunnel(0.08f)
                // -- revolution one: percussion band, outer wall --
                b.cylArc(r, 0.5f, -0.22f)
                b.xylophone(0.12f, 8)
                b.cylArc(r, 0.55f, -0.2f)
                b.pachinko(0.85f, 6)
                b.cylArc(r, 0.5f, -0.2f)
                b.newtonsCradle(0.6f)
                b.cylArc(r, 0.55f, -0.25f)
                b.helix(0.9f, 2f, 0.95f)
                // -- dive to the inner ring: rotation band --
                b.cylArc(rIn, 0.8f, -0.35f)
                b.gaussCannon(0.13f)
                b.cylArc(rIn, 0.5f, -0.2f)
                b.ferrisWheel(0.7f)
                b.cylArc(rIn, 0.5f, -0.15f)
                b.trommel(0.32f)
                b.cylArc(rIn, 0.55f, -0.2f)
                b.doubleSpiral(0.75f)
                b.cylArc(rIn, 0.5f, -0.15f)
                b.rockerArm(0.45f)
                // -- back out to the wall: the drop finale --
                b.cylArc(r, 0.9f, -0.35f)
                b.snake(0.3f, 4)
                b.cylArc(r, 0.5f, -0.2f)
                b.gravityFunnel(0.55f)
                b.cylArc(r, 0.45f, -0.15f)
                b.tippingBucket(0.98f)
                b.cylArc(r, 0.4f, -0.4f)
                b.loop(0.62f)
                b.propeller(0.62f); b.propeller(0.9f); b.propeller(0.2f)
                b.cylArc(r, 0.5f, -0.1f)
                b.archimedesScrew(0.55f, 1.0f)
                b.cylArc(r, 0.45f, -0.45f)
                b.loop(0.5f)
                b.cylArc(r, 0.6f, -0.3f)
                b.helix(1.0f, 2f, 1.4f)
                b.cylArc(r, 0.6f, -0.25f)
                b.xylophone(0.55f, 6)
                b.cylArc(r, 0.7f, -0.25f)
                val fs = b.elevator(ix, iy, iz, 0.14f)
                return b.build(fs, floatArrayOf(ix, iy, iz))
            }
        }
    }
}
