package com.tapkugelbahn.engine

import kotlin.math.PI

/**
 * Three machines, built from one catalogue.
 *
 * Each is ONE closed loop winding counterclockwise around a central axis — a
 * panoramic cylindrical route, made to be watched from inside as much as
 * outside. The three differ only in how much of the step catalogue they
 * contain: 70% of it, then 90%, then all of it, always as a prefix of the
 * teaching order, so a machine is the previous one plus more rather than a
 * different selection. Bigger machines get a wider drum, a taller start and
 * another half-turn of winding to hold the extra work.
 */
object Machine {

    const val LEVELS = Steps.LEVELS
    val NAMES = Steps.NAMES

    /** Radius, ceiling and how many turns of the drum each machine occupies. */
    private fun shape(level: Int): Triple<Float, Float, Float> = when (level.coerceIn(1, LEVELS)) {
        1 -> Triple(2.6f, 4.6f, 2.2f)
        2 -> Triple(3.1f, 5.6f, 2.8f)
        else -> Triple(3.6f, 6.6f, 3.4f)
    }

    fun build(level: Int): MachineModel {
        val ids = Steps.stepsFor(level)
        val (r, topY, turns) = shape(level)
        val b = TrackBuilder()
        b.cylStart(r, 0f, topY)
        val ix = b.x; val iy = b.y; val iz = b.z
        b.intakeFunnel(0.08f)

        // Share the drum out evenly between the steps, and the descent with it.
        // The steps bring their own drops; the connectors only need to carry the
        // remainder, so keep them shallow or the machine runs out of height
        // before it runs out of catalogue.
        val dTheta = turns * 2f * PI.toFloat() / ids.size
        val dy = -0.16f
        for (id in ids) {
            b.cylArc(r, dTheta, dy)
            Steps.emit(b, id)
        }
        val fs = b.elevator(ix, iy, iz, 0.14f)
        return b.build(fs, floatArrayOf(ix, iy, iz))
    }
}
