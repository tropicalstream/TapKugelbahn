package com.tapkugelbahn.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.tapkugelbahn.R

/**
 * Real machine sounds (see SOUND_CREDITS.md — public-domain / CC recordings,
 * fetched, trimmed and normalized). SoundPool's rate control turns the one
 * xylophone strike into the whole scale and varies every clack so the
 * machine never sounds looped. One stream loops as the rolling-ball bed,
 * its rate and volume riding the lead ball's speed.
 */
class Sfx(private val context: Context) {

    companion object {
        const val ROLL = 0      // looped rolling bed
        const val CLACK = 1     // ball-ball / wood knock
        const val XYLO = 2      // xylophone strike (pitched per step)
        const val RATCHET = 3   // elevator / screw tick
        const val DING = 4      // gauss cannon / win chime
        const val DROP = 5      // ball released into the intake
        private const val COUNT = 6
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(10)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val ids = IntArray(COUNT)
    @Volatile private var loaded = false
    @Volatile var volume = 0.9f
    private var rollStream = 0
    private var rollOn = false

    fun loadAsync() {
        Thread {
            ids[ROLL] = pool.load(context, R.raw.roll_loop, 1)
            ids[CLACK] = pool.load(context, R.raw.clack, 1)
            ids[XYLO] = pool.load(context, R.raw.xylo, 1)
            ids[RATCHET] = pool.load(context, R.raw.ratchet, 1)
            ids[DING] = pool.load(context, R.raw.ding, 1)
            ids[DROP] = pool.load(context, R.raw.drop, 1)
            loaded = true
        }.start()
    }

    fun play(id: Int, pitch: Float, vol: Float) {
        if (!loaded || id !in 0 until COUNT) return
        val v = (vol * volume).coerceIn(0f, 1f)
        pool.play(ids[id], v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
    }

    /** The rolling bed: on/off with live rate + volume. */
    fun rolling(on: Boolean, rate: Float, vol: Float) {
        if (!loaded) return
        if (on && !rollOn) {
            rollStream = pool.play(ids[ROLL], vol * volume, vol * volume, 0, -1, rate.coerceIn(0.5f, 2f))
            rollOn = rollStream != 0
        } else if (on && rollOn) {
            pool.setVolume(rollStream, vol * volume, vol * volume)
            pool.setRate(rollStream, rate.coerceIn(0.5f, 2f))
        } else if (!on && rollOn) {
            pool.stop(rollStream); rollOn = false
        }
    }

    fun release() {
        if (rollOn) pool.stop(rollStream)
        pool.release()
    }
}
