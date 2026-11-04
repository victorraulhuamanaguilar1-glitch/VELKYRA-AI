package com.velkyra.yarvis

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Voces premium muy humanas de Google Gemini (necesitan internet; cupo gratuito limitado).
 * Si el cupo se agota o falla la conexión, YARVIS sigue hablando con la voz del celular.
 */
object VozPremium {

    data class VozG(val id: String, val nombre: String, val hombre: Boolean, val estilo: String)

    val VOCES = listOf(
        VozG("Charon", "Carlos", true, "Grave e informativo · ideal para YARVIS"),
        VozG("Orus", "Óscar", true, "Firme y seguro"),
        VozG("Algenib", "Gabriel", true, "Voz grave y rasposa"),
        VozG("Iapetus", "Iván", true, "Claro y sereno"),
        VozG("Sadaltager", "Samuel", true, "Culto y pausado · narrador"),
        VozG("Kore", "Karina", false, "Firme y segura"),
        VozG("Aoede", "Andrea", false, "Fresca y natural"),
        VozG("Sulafat", "Sofía", false, "Cálida · narradora"),
        VozG("Despina", "Daniela", false, "Suave y elegante"),
        VozG("Leda", "Lucía", false, "Joven y amable")
    )

    private val MODELOS = listOf("gemini-2.5-flash-preview-tts", "gemini-3.1-flash-tts-preview", "gemini-2.5-pro-preview-tts")
    private const val TASA = 24000

    private val principal = Handler(Looper.getMainLooper())
    private val generacion = AtomicInteger(0)
    @Volatile private var pista: AudioTrack? = null
    @Volatile private var modeloBueno: String? = null

    /** Hasta cuándo no usarla (cupo agotado). */
    @Volatile var agotadaHasta = 0L
        private set
    @Volatile var ultimoError: String? = null
        private set

    fun voz(id: String = Prefs.vozPremium) = VOCES.firstOrNull { it.id == id } ?: VOCES[0]

    fun disponible(): Boolean = Prefs.vozPremium.isNotBlank() && Prefs.claveIA.isNotBlank() &&
        System.currentTimeMillis() > agotadaHasta && CerebroIA.hayInternet()

    /**
     * Habla con la voz premium. Si falla en algún momento, llama a [alFallar] con el texto que faltó decir
     * (para que lo diga la voz del celular).
     */
    fun hablar(texto: String, vozId: String, alEmpezar: () -> Unit, alTerminar: () -> Unit, alFallar: (String) -> Unit) {
        detener()
        val miGen = generacion.incrementAndGet()
        val partes = dividir(texto)
        val cola = LinkedBlockingQueue<Any>(3)
        val FIN = Any()

        // Pide el audio parte por parte (mientras suena una, prepara la siguiente)
        Thread {
            var i = 0
            try {
                while (i < partes.size && generacion.get() == miGen) {
                    val audio = sintetizar(partes[i], vozId)
                    if (generacion.get() != miGen) break
                    cola.put(audio)
                    i++
                }
            } catch (e: Throwable) {
                ultimoError = CerebroIA.detalle(e as? Exception ?: Exception(e))
                if (e is CerebroIA.ErrorIA && e.codigo == 429) agotadaHasta = System.currentTimeMillis() + 3 * 3_600_000L
                if (i < partes.size) cola.put("FALLA:" + partes.drop(i).joinToString(" "))
            } finally {
                try { cola.put(FIN) } catch (_: Throwable) {}
            }
        }.start()

        // Reproduce
        Thread {
            var t: AudioTrack? = null
            var faltante: String? = null
            var empezo = false
            try {
                val minimo = AudioTrack.getMinBufferSize(TASA, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(TASA).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(minimo, TASA))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                pista = t
                var escritos = 0L
                while (generacion.get() == miGen) {
                    val x = cola.take()
                    if (x === FIN) break
                    if (x is String && x.startsWith("FALLA:")) { faltante = x.removePrefix("FALLA:"); continue }
                    val pcm = x as ByteArray
                    if (!empezo) {
                        t.play()
                        empezo = true
                        principal.post { if (generacion.get() == miGen) alEmpezar() }
                    }
                    var pos = 0
                    while (pos < pcm.size && generacion.get() == miGen) {
                        val n = t.write(pcm, pos, pcm.size - pos, AudioTrack.WRITE_BLOCKING)
                        if (n <= 0) break
                        pos += n
                    }
                    escritos += pcm.size / 2
                }
                if (generacion.get() == miGen && empezo) {
                    val limite = System.currentTimeMillis() + 20000
                    while (generacion.get() == miGen && t.playbackHeadPosition < escritos && System.currentTimeMillis() < limite) Thread.sleep(40)
                }
            } catch (_: Throwable) {
            } finally {
                try { t?.stop() } catch (_: Throwable) {}
                try { t?.release() } catch (_: Throwable) {}
                if (pista === t) pista = null
                if (generacion.get() == miGen) {
                    val f = faltante
                    principal.post {
                        if (generacion.get() != miGen) return@post
                        if (f != null) alFallar(f) else alTerminar()
                    }
                }
            }
        }.start()
    }

    fun detener() {
        generacion.incrementAndGet()
        pista?.let { try { it.pause(); it.flush() } catch (_: Throwable) {} }
    }

    /** Pide a Gemini el audio de un texto. Devuelve PCM de 16 bits a 24 kHz. */
    private fun sintetizar(texto: String, vozId: String): ByteArray {
        val instruccion = "Lee el siguiente texto en español latinoamericano, con voz natural, cálida y expresiva, " +
            "como un narrador profesional, sin agregar nada: "
        val cuerpo = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruccion + texto)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", vozId)))))
        var ultimo: Exception? = null
        val modelos = listOfNotNull(modeloBueno) + MODELOS.filter { it != modeloBueno }
        for (m in modelos) {
            try {
                val pcm = pedir(m, cuerpo)
                modeloBueno = m
                return pcm
            } catch (e: CerebroIA.ErrorIA) {
                ultimo = e
                if (e.codigo == 404 || e.codigo == 400) continue
                throw e
            }
        }
        throw ultimo ?: CerebroIA.ErrorIA(404, "sin modelo de voz")
    }

    private fun pedir(modelo: String, cuerpo: JSONObject): ByteArray {
        val con = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15000
            con.readTimeout = 60000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            con.setRequestProperty("x-goog-api-key", Prefs.claveIA)
            con.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
            val codigo = con.responseCode
            val flujo = if (codigo in 200..299) con.inputStream else con.errorStream
            val txt = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (codigo !in 200..299) throw CerebroIA.ErrorIA(codigo, txt.take(600))
            val datos = JSONObject(txt).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?.optJSONArray("parts")?.optJSONObject(0)?.optJSONObject("inlineData")?.optString("data")
            if (datos.isNullOrBlank()) throw CerebroIA.ErrorIA(204, "sin audio")
            return Base64.decode(datos, Base64.DEFAULT)
        } finally {
            con.disconnect()
        }
    }

    /** Partes de hasta ~600 caracteres (la primera más corta para empezar a hablar rápido). */
    private fun dividir(texto: String): List<String> {
        val oraciones = Regex("(?<=[.!?…])\\s+").split(texto.trim()).filter { it.isNotBlank() }
        val r = mutableListOf<String>()
        val sb = StringBuilder()
        for (o in oraciones) {
            val max = if (r.isEmpty()) 220 else 600
            if (sb.length + o.length > max && sb.isNotEmpty()) { r.add(sb.toString()); sb.clear() }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(o)
        }
        if (sb.isNotEmpty()) r.add(sb.toString())
        return r.ifEmpty { listOf(texto) }
    }
}
