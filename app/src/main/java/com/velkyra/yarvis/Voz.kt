package com.velkyra.yarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** La voz de YARVIS (texto a voz del celular, preferentemente el motor de Google). */
object Voz : TextToSpeech.OnInitListener {

    data class OpcionVoz(val voz: Voice, val etiqueta: String, val masculina: Boolean)

    private var tts: TextToSpeech? = null
    private lateinit var ctx: Context
    private lateinit var audio: AudioManager
    private var foco: AudioFocusRequest? = null
    private val principal = Handler(Looper.getMainLooper())
    private val contador = AtomicInteger()
    private val alTerminar = ConcurrentHashMap<String, () -> Unit>()
    private val pendientes = mutableListOf<Pair<String, (() -> Unit)?>>()

    var lista = false
        private set
    var hablando = false
        private set
    var alCambiar: ((Boolean) -> Unit)? = null

    // Voces de Google en español que suenan masculinas
    private val pistasMasculinas = listOf("esd", "eed", "eef", "esc-", "male", "hombre", "masculin", "#male")

    fun init(c: Context) {
        ctx = c.applicationContext
        audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(ctx, this, "com.google.android.tts")
    }

    override fun onInit(status: Int) {
        val motor = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            // Si no está el motor de Google, se usa el que tenga el celular
            tts = TextToSpeech(ctx) { s -> if (s == TextToSpeech.SUCCESS) configurar() }
            return
        }
        if (motor === tts) configurar()
    }

    private fun configurar() {
        val motor = tts ?: return
        val r = motor.setLanguage(Locale.forLanguageTag("es-US"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            motor.setLanguage(Locale.forLanguageTag("es"))
        }
        motor.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                principal.post { hablando = true; alCambiar?.invoke(true) }
            }

            override fun onDone(id: String?) = fin(id)

            @Deprecated("Requerido por Android")
            override fun onError(id: String?) = fin(id)

            override fun onError(id: String?, errorCode: Int) = fin(id)

            override fun onStop(id: String?, interrupted: Boolean) {
                if (id != null) alTerminar.remove(id)
                principal.post { hablando = false; alCambiar?.invoke(false); soltarFoco() }
            }
        })
        aplicarVoz()
        lista = true
        val copia = pendientes.toList()
        pendientes.clear()
        for ((t, d) in copia) hablar(t, d)
    }

    private fun fin(id: String?) {
        val accion = if (id != null) alTerminar.remove(id) else null
        principal.post {
            if (!(tts?.isSpeaking ?: false)) {
                hablando = false
                alCambiar?.invoke(false)
                soltarFoco()
            }
            accion?.invoke()
        }
    }

    fun opciones(): List<OpcionVoz> {
        val voces = try { tts?.voices } catch (_: Exception) { null } ?: return emptyList()
        val es = voces.filter { it.locale.language == "es" }
            .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        val regiones = mapOf(
            "US" to "Latinoamérica", "MX" to "México", "ES" to "España", "PE" to "Perú",
            "AR" to "Argentina", "CO" to "Colombia", "CL" to "Chile", "419" to "Latinoamérica"
        )
        return es.sortedWith(compareByDescending<Voice> { esMasculina(it) }
            .thenBy { it.isNetworkConnectionRequired }
            .thenByDescending { it.quality }
            .thenBy { if (it.locale.country == "US") 0 else if (it.locale.country == "ES") 2 else 1 }
            .thenBy { it.name })
            .mapIndexed { i, v ->
                val m = esMasculina(v)
                val region = regiones[v.locale.country] ?: v.locale.displayCountry
                val tipo = if (m) "Masculina" else "Voz"
                val red = if (v.isNetworkConnectionRequired) " · en línea" else ""
                OpcionVoz(v, "$tipo ${i + 1} · $region$red", m)
            }
    }

    private fun esMasculina(v: Voice): Boolean {
        val n = v.name.lowercase(Locale.ROOT)
        val f = v.features.joinToString(" ").lowercase(Locale.ROOT)
        if (f.contains("female") || n.contains("female")) return false
        return pistasMasculinas.any { n.contains(it) || f.contains(it) }
    }

    /** La voz guardada, o la mejor voz masculina disponible. */
    fun aplicarVoz() {
        val motor = tts ?: return
        val todas = opciones()
        val elegida = todas.firstOrNull { it.voz.name == Prefs.voz }?.voz
            ?: todas.firstOrNull { it.masculina && !it.voz.isNetworkConnectionRequired }?.voz
            ?: todas.firstOrNull()?.voz
        if (elegida != null) {
            try { motor.voice = elegida } catch (_: Exception) {}
        }
        motor.setPitch(Prefs.tono)
        motor.setSpeechRate(Prefs.velocidad)
    }

    fun vozActual(): String = try { tts?.voice?.name.orEmpty() } catch (_: Exception) { "" }

    fun probarVoz(v: Voice, texto: String) {
        val motor = tts ?: return
        try { motor.voice = v } catch (_: Exception) {}
        motor.setPitch(Prefs.tono)
        motor.setSpeechRate(Prefs.velocidad)
        hablar(texto)
    }

    fun hablar(texto: String, despues: (() -> Unit)? = null) {
        val motor = tts
        if (!lista || motor == null) {
            pendientes.add(texto to despues)
            return
        }
        // Los textos largos (cuentos) se dividen en partes para que el motor de voz no los corte
        val partes = dividir(limpiar(texto), 1500)
        pedirFoco()
        var ok = true
        partes.forEachIndexed { i, parte ->
            val id = "y" + contador.incrementAndGet()
            if (i == partes.lastIndex && despues != null) alTerminar[id] = despues
            val modo = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (motor.speak(parte, modo, null, id) != TextToSpeech.SUCCESS) ok = false
        }
        if (!ok || partes.isEmpty()) {
            alTerminar.clear()
            soltarFoco()
            despues?.let { principal.post(it) }
        }
    }

    private fun dividir(t: String, max: Int): List<String> {
        if (t.length <= max) return listOf(t)
        val r = mutableListOf<String>()
        val sb = StringBuilder()
        for (oracion in Regex("(?<=[.!?…])\\s+").split(t)) {
            if (sb.length + oracion.length + 1 > max && sb.isNotEmpty()) { r.add(sb.toString()); sb.clear() }
            if (oracion.length > max) { oracion.chunked(max).forEach { r.add(it) } } else { if (sb.isNotEmpty()) sb.append(' '); sb.append(oracion) }
        }
        if (sb.isNotEmpty()) r.add(sb.toString())
        return r
    }

    fun detener() {
        alTerminar.clear()
        tts?.stop()
        hablando = false
        soltarFoco()
    }

    private fun pedirFoco() {
        if (foco != null) return
        val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            ).build()
        foco = f
        audio.requestAudioFocus(f)
    }

    private fun soltarFoco() {
        foco?.let { audio.abandonAudioFocusRequest(it) }
        foco = null
    }

    private fun limpiar(s: String): String =
        s.replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*#_`>|]"), "")
            .replace(Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
