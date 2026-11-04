package com.velkyra.yarvis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** El oído de YARVIS para entender las órdenes completas (reconocimiento de voz de Google). */
object Oido {

    interface Escucha {
        fun parcial(texto: String)
        fun nivel(rms: Float)
        fun resultado(texto: String?)
        fun error(mensaje: String)
    }

    private var reconocedor: SpeechRecognizer? = null
    private var idioma = "es-PE"
    private var reintentoIdioma = false

    fun disponible(ctx: Context) = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun escuchar(ctx: Context, oyente: Escucha) {
        cancelar()
        if (!disponible(ctx)) {
            oyente.error("No encuentro el servicio de reconocimiento de voz. Instale o actualice la aplicación de Google.")
            return
        }
        val rec = SpeechRecognizer.createSpeechRecognizer(ctx.applicationContext)
        reconocedor = rec
        rec.setRecognitionListener(object : RecognitionListener {
            private var terminado = false

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = oyente.nivel(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!p.isNullOrBlank()) oyente.parcial(p)
            }

            override fun onResults(results: Bundle?) {
                if (terminado) return
                terminado = true
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                liberar(rec)
                oyente.resultado(t)
            }

            override fun onError(error: Int) {
                if (terminado) return
                terminado = true
                liberar(rec)
                if ((error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) && !reintentoIdioma) {
                    reintentoIdioma = true
                    idioma = "es-US"
                    escuchar(ctx, oyente)
                    return
                }
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> oyente.resultado(null)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        oyente.error("No tengo conexión para entender su voz en este momento.")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> oyente.error("Necesito permiso para usar el micrófono.")
                    else -> oyente.resultado(null)
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, idioma)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            liberar(rec)
            oyente.error("No pude usar el micrófono.")
        }
    }

    private fun liberar(rec: SpeechRecognizer) {
        try { rec.destroy() } catch (_: Exception) {}
        if (reconocedor === rec) reconocedor = null
    }

    fun cancelar() {
        reconocedor?.let {
            try { it.cancel() } catch (_: Exception) {}
            liberar(it)
        }
    }
}
