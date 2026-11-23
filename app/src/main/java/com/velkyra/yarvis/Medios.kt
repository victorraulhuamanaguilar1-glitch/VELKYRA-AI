package com.velkyra.yarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Pausa la música o el video de otras apps mientras ODÍN le escucha y le responde,
 * y los deja seguir al terminar (salvo que el usuario haya pedido pausarlos).
 */
object Medios {

    private val principal = Handler(Looper.getMainLooper())
    private var foco: AudioFocusRequest? = null

    private fun am() = Yarvis.ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun sonando(): Boolean = try { am().isMusicActive } catch (_: Exception) { false }

    /** Al activarse: si algo suena, toma el foco de audio para que esa app se pause. */
    fun retener() {
        if (foco != null || !sonando()) return
        val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            ).setOnAudioFocusChangeListener { }
            .build()
        try {
            if (am().requestAudioFocus(f) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) foco = f
        } catch (_: Exception) {}
    }

    /** Al terminar: devuelve el foco y la música/video sigue donde estaba. */
    fun soltar() {
        foco?.let { try { am().abandonAudioFocusRequest(it) } catch (_: Exception) {} }
        foco = null
    }

    fun tecla(codigo: Int) {
        val am = am()
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, codigo, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, codigo, 0))
    }

    /** «Para el video»: lo pausa de verdad, para que no vuelva a sonar al soltar el foco. */
    fun pausar() {
        tecla(KeyEvent.KEYCODE_MEDIA_PAUSE)
        soltar()
        // Por si la app volvió a sonar al recuperar el foco
        principal.postDelayed({ if (sonando() && foco == null) tecla(KeyEvent.KEYCODE_MEDIA_PAUSE) }, 900)
    }

    /** «Continúa»: suelta el foco y da play. */
    fun reanudar() {
        soltar()
        principal.postDelayed({ if (!sonando()) tecla(KeyEvent.KEYCODE_MEDIA_PLAY) }, 400)
    }
}
