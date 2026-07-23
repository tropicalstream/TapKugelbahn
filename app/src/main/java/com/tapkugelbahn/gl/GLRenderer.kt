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

    // camera state (smoothed)
    private val camPos = floatArrayOf(0f, 3f, -7f)
    private val camLook = floatArrayOf(0f, 2f, 0f)
    private var orbit = 0f
    private var autoSub = 1          // director's current vantage (1..4)
    private var autoTimer = 0f
    private var lastView = -1

    private val bp = FloatArray(3)
    private val bt = FloatArray(3)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram(VERT, FRAG)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aColor = GLES30.glGetAttribLocation(program, "aColor")
        uMVP = GLES30.glGetUniformLocation(program, "uMVP")
        uPointSize = GLES30.glGetUniformLocation(program, "uPointSize")
        uPoint = GLES30.glGetUniformLocation(program, "uPoint")
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        lastNanos = 0L
        uploadedRevision = -1   // context loss: re-upload
        staticVbo = 0
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

        updateCamera(dt)
        buildDynamic(dt)
        buildHud()

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
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

        val tx: Float; val ty: Float; val tz: Float          // target eye
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

    private fun drawMech(mc: Mech, dt: Float) {
        val fxd = sin(mc.yaw); val fzd = cos(mc.yaw)
        val lxd = cos(mc.yaw); val lzd = -sin(mc.yaw)
        val c = TrackBuilder.hsv(mc.hue)
        when (mc.type) {
            M_FERRIS -> {
                mc.phase += dt * 0.55f
                val r = mc.a
                ringPlane(mc.x, mc.y, mc.z, fxd, fzd, r, 20, c[0], c[1], c[2], 0.75f)
                ringPlane(mc.x, mc.y, mc.z, fxd, fzd, r * 0.12f, 8, c[0], c[1], c[2], 0.9f)
                for (k in 0 until 8) {
                    val a = mc.phase + k * PI.toFloat() / 4f
                    val px = mc.x + fxd * sin(a) * r; val py = mc.y + cos(a) * r; val pz = mc.z + fzd * sin(a) * r
                    dyn.line(mc.x, mc.y, mc.z, px, py, pz, c[0], c[1], c[2], 0.55f)
                    // gondola
                    dyn.line(px, py, pz, px, py - 0.14f, pz, 1f, 1f, 1f, 0.7f)
                    dyn.line(px - lxd * 0.05f, py - 0.14f, pz - lzd * 0.05f, px + lxd * 0.05f, py - 0.14f, pz + lzd * 0.05f, 1f, 1f, 1f, 0.7f)
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
                mc.phase += dt * 1.5f
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
                mc.phase += dt * 3.4f
                val sw = sin(mc.phase)
                val topY = mc.y + 0.55f
                // the two end pendulums trade the swing, cabinet-of-motion style
                val aSwing = if (sw > 0) sw * 0.7f else 0f
                val bSwing = if (sw < 0) sw * 0.7f else 0f
                val ax = mc.x - fxd * mc.a * 0.5f; val az = mc.z - fzd * mc.a * 0.5f
                val bx = mc.x + fxd * mc.a * 0.5f; val bz = mc.z + fzd * mc.a * 0.5f
                pendulum(ax, topY, az, fxd, fzd, aSwing, c)
                pendulum(bx, topY, bz, fxd, fzd, bSwing, c)
            }
            M_ROCKER -> {
                mc.phase += dt * 1.1f
                val tilt = sin(mc.phase) * 0.16f
                val hx = fxd * mc.a; val hz = fzd * mc.a
                dyn.line(mc.x - hx, mc.y - sin(tilt) * mc.a, mc.z - hz,
                    mc.x + hx, mc.y + sin(tilt) * mc.a, mc.z + hz, c[0], c[1], c[2], 0.9f)
                dyn.line(mc.x - lxd * 0.08f, mc.y - 0.16f, mc.z - lzd * 0.08f, mc.x, mc.y, mc.z, 0.7f, 0.7f, 0.8f, 0.7f)
                dyn.line(mc.x + lxd * 0.08f, mc.y - 0.16f, mc.z + lzd * 0.08f, mc.x, mc.y, mc.z, 0.7f, 0.7f, 0.8f, 0.7f)
            }
            M_BUCKET -> {
                mc.phase += dt * 0.9f
                val tip = (sin(mc.phase) * 0.5f + 0.5f) * 0.55f
                val bx = mc.x; val by = mc.y; val bz = mc.z
                // bucket square tipping about its lip
                val s = mc.a
                val e1x = fxd * s * cos(tip); val e1y = -s * sin(tip)
                dyn.line(bx, by, bz, bx + e1x, by + e1y, bz + fzd * s * cos(tip), c[0], c[1], c[2], 0.9f)
                dyn.line(bx, by - s * 0.7f, bz, bx + e1x, by + e1y - s * 0.7f, bz + fzd * s * cos(tip), c[0], c[1], c[2], 0.9f)
                dyn.line(bx, by, bz, bx, by - s * 0.7f, bz, c[0], c[1], c[2], 0.9f)
                // counterweight arm opposite
                dyn.line(bx, by, bz, bx - fxd * mc.b, by + tip * 0.4f, bz - fzd * mc.b, 0.8f, 0.7f, 0.5f, 0.8f)
                boxAt(bx - fxd * mc.b, by + tip * 0.4f, bz - fzd * mc.b, 0.09f, 0.9f, 0.75f, 0.4f, 0.9f)
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
        val len = topY - (topY - 0.42f)
        val ex = px + fxd * sin(swing) * 0.42f
        val ey = topY - cos(swing) * 0.42f
        val ez = pz + fzd * sin(swing) * 0.42f
        dyn.line(px, topY, pz, ex, ey, ez, 0.8f, 0.85f, 0.95f, 0.85f)
        // little wireframe ball
        ringXZ(ex, ey, ez, BALL_R * 0.7f, 8, c[0], c[1], c[2], 0.9f)
    }

    private fun drawBall(b: Ball) {
        val m = game.machine
        val r = BALL_R
        val col = TrackBuilder.hsv(b.hue)
        val px = b.pos[0]; val py = b.pos[1] + r * 0.55f; val pz = b.pos[2]
        // rolling basis: tangent, lateral, up' rotated by roll about lateral
        m.tangent(b.s, bt)
        var lx = bt[2]; var lz = -bt[0]
        val ll = hypot(lx, lz).coerceAtLeast(0.05f); lx /= ll; lz /= ll
        val ux = bt[1] * lz; val uy = bt[2] * lx - bt[0] * lz; val uz = -bt[1] * lx
        val cR = cos(b.roll); val sR = sin(b.roll)
        // rotated basis vectors t' and u'
        val t1x = bt[0] * cR + ux * sR; val t1y = bt[1] * cR + uy * sR; val t1z = bt[2] * cR + uz * sR
        val u1x = -bt[0] * sR + ux * cR; val u1y = -bt[1] * sR + uy * cR; val u1z = -bt[2] * sR + uz * cR
        // three orthogonal rings = the vector sphere
        ring3(px, py, pz, t1x, t1y, t1z, u1x, u1y, u1z, r, col, 1f)          // motion plane (spins!)
        ring3(px, py, pz, lx, 0f, lz, u1x, u1y, u1z, r, col, 0.55f)
        ring3(px, py, pz, t1x, t1y, t1z, lx, 0f, lz, r, col, 0.55f)
        fx.v(px, py, pz, col[0], col[1], col[2], 1f)
        // objective halo: still-clean player balls wear a white ring
        if (!b.touched && b.dropTime >= 0f && !b.finished) {
            ringXZ(px, py + r * 1.7f, pz, r * 0.55f, 8, 1f, 1f, 1f, 0.7f)
        }
    }

    /** Circle in the plane spanned by unit vectors A and B around center. */
    private fun ring3(cx: Float, cy: Float, cz: Float,
                      axx: Float, axy: Float, axz: Float,
                      bxx: Float, bxy: Float, bxz: Float,
                      r: Float, c: FloatArray, alpha: Float) {
        val segs = 12
        var pxp = cx + axx * r; var pyp = cy + axy * r; var pzp = cz + axz * r
        for (i in 1..segs) {
            val a = i * 2f * PI.toFloat() / segs
            val qx = cx + (axx * cos(a) + bxx * sin(a)) * r
            val qy = cy + (axy * cos(a) + bxy * sin(a)) * r
            val qz = cz + (axz * cos(a) + bxz * sin(a)) * r
            dyn.line(pxp, pyp, pzp, qx, qy, qz, c[0], c[1], c[2], alpha)
            pxp = qx; pyp = qy; pzp = qz
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

    private fun buildHud() {
        hud.reset()
        val pulse = 0.55f + 0.45f * sin(game.time * 4f)
        when (game.state) {
            GameState.TITLE -> {
                text("TAPKUGELBAHN", 320f, 120f, 4.2f, 1f, 0.72f, 0.25f)
                text("LEVEL ${game.level} · ${Machine.NAMES[game.level - 1]}", 320f, 175f, 1.9f, 0.6f, 0.85f, 1f)
                text("TAP TO START", 320f, 260f, 2.4f, 1f, 1f, 1f, pulse)
                text("TAP DROP BALL · SWIPE VIEW · DOUBLE-TAP NEXT", 320f, 320f, 1.35f, 0.6f, 0.7f, 0.8f, 0.9f)
                text("TWO BALLS MUST RUN TOGETHER AND NEVER TOUCH", 320f, 348f, 1.35f, 0.6f, 0.7f, 0.8f, 0.9f)
            }
            GameState.RUN, GameState.COMPLETE, GameState.FINALE -> {
                text("L${game.level} ${Machine.NAMES[game.level - 1]}", 14f, 40f, 1.6f, 0.65f, 0.85f, 1f, 1f, center = false)
                val bc = "BALLS ${game.activeCount()}"
                text(bc, 626f - StrokeFont.width(bc, 1.6f), 40f, 1.6f, 1f, 0.85f, 0.45f, 1f, center = false)
                if (game.viewFlash > 0f)
                    text(Game.VIEW_NAMES[game.view], 320f, 78f, 2.2f, 0.65f, 1f, 0.8f, game.viewFlash.coerceAtMost(1f))
                if (game.touchFlash > 0f)
                    text("TOUCH!", 320f, 150f, 3.4f, 1f, 0.35f, 0.3f, game.touchFlash.coerceAtMost(1f))
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
                        else if (players == 1) text("DROP A SECOND BALL WHILE IT RUNS", 320f, 440f, 1.6f, 0.7f, 1f, 0.75f, pulse)
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
