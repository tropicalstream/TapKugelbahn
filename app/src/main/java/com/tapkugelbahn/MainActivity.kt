package com.tapkugelbahn

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.tapkugelbahn.audio.Sfx
import com.tapkugelbahn.engine.Game
import com.tapkugelbahn.engine.GameHost
import com.tapkugelbahn.gl.GLRenderer
import kotlin.math.max

/**
 * TapKugelbahn — the kinetic rolling-ball sculpture.
 * TAP drops a ball · SWIPE cycles the view (auto-director first) ·
 * DOUBLE-TAP advances once the level is complete.
 */
class MainActivity : Activity(), GameHost {

    private lateinit var store: SettingsStore
    private lateinit var sfx: Sfx
    private lateinit var game: Game
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GLRenderer
    private val main = Handler(Looper.getMainLooper())

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastKeyTap = 0L

    // tap sequencing: a second tap inside the window = double-tap
    private var pendingSingle: Runnable? = null
    private var lastTapUp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        sfx = Sfx(this).also { it.loadAsync() }
        game = Game(store, this)
        renderer = GLRenderer(game).also { it.sbs = store.sbs }

        glView = object : GLSurfaceView(this) {}.apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        game.boot()
    }

    // ------------------------------------------------------------ GameHost

    override fun sfx(id: Int, pitch: Float, vol: Float) = sfx.play(id, pitch, vol)
    override fun rolling(on: Boolean, rate: Float, vol: Float) = sfx.rolling(on, rate, vol)

    // --------------------------------------------------------------- input

    private fun tapUp() {
        val now = SystemClock.uptimeMillis()
        val sincePrev = now - lastTapUp
        lastTapUp = now
        val pending = pendingSingle
        if (pending != null && sincePrev in 40..330) {
            main.removeCallbacks(pending)
            pendingSingle = null
            glView.queueEvent { game.doubleTap() }
            return
        }
        val r = Runnable {
            pendingSingle = null
            glView.queueEvent { game.tap() }
        }
        pendingSingle = r
        main.postDelayed(r, 340L)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isTap = event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_SPACE
        if (isTap) {
            if (event.action == KeyEvent.ACTION_UP) {
                val now = SystemClock.uptimeMillis()
                if (now - lastKeyTap >= 60) { lastKeyTap = now; tapUp() }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // left temple pad is system volume — ignore it
        if (ev.device?.name?.contains("cyttsp6", ignoreCase = true) == true) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downT = SystemClock.uptimeMillis() }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX; val dy = ev.y - downY
                val dist = kotlin.math.hypot(dx, dy)
                val thresh = max(48f, 0.09f * resources.displayMetrics.widthPixels)
                if (dist >= thresh) {
                    glView.queueEvent { game.swipe() }
                } else if (SystemClock.uptimeMillis() - downT <= 320) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastKeyTap >= 60) { lastKeyTap = now; tapUp() }
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView.onResume()
    }

    override fun onPause() {
        sfx.rolling(false, 1f, 0f)
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        sfx.release()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
