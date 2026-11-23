package com.velkyra.yarvis

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.math.sqrt

/**
 * Reconocimiento de quién habla (sin internet). Guarda la "huella" de la voz del dueño
 * y de las personas que él autoriza, y la compara cada vez que alguien dice la palabra de activación.
 */
object VozDueno {

    data class Perfil(val nombre: String, val dueno: Boolean, val huella: FloatArray)

    private const val MODELO = "campplus.onnx"
    private var extractor: SpeakerEmbeddingExtractor? = null
    @Volatile var error: String? = null
        private set

    fun disponible(ctx: Context): Boolean = try { ctx.assets.open(MODELO).close(); true } catch (_: Exception) { false }

    private fun motor(ctx: Context): SpeakerEmbeddingExtractor? = synchronized(this) {
        extractor ?: try {
            SpeakerEmbeddingExtractor(ctx.applicationContext.assets, SpeakerEmbeddingExtractorConfig(model = MODELO, numThreads = 2)).also {
                extractor = it
                error = null
            }
        } catch (t: Throwable) {
            error = t.javaClass.simpleName + ": " + (t.message ?: "")
            null
        }
    }

    /** Huella de voz (normalizada) de un audio de 16 kHz. Null si el audio es muy corto o falló. */
    fun huella(ctx: Context, pcm: ShortArray): FloatArray? {
        if (pcm.size < 8000) return null
        val e = motor(ctx) ?: return null
        return try {
            synchronized(this) {
                val f = FloatArray(pcm.size) { pcm[it] / 32768f }
                val st = e.createStream()
                st.acceptWaveform(f, 16000)
                st.inputFinished()
                val v = if (e.isReady(st)) e.compute(st) else null
                st.release()
                v?.let { normalizar(it) }
            }
        } catch (t: Throwable) {
            error = t.javaClass.simpleName
            null
        }
    }

    fun normalizar(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        val r = sqrt(n).coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / r }
    }

    fun promedio(lista: List<FloatArray>): FloatArray {
        val r = FloatArray(lista[0].size)
        for (v in lista) for (i in r.indices) r[i] += v[i]
        return normalizar(r)
    }

    fun similitud(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
        return s
    }

    // --- Perfiles --------------------------------------------------------------

    fun perfiles(): List<Perfil> = try {
        val a = JSONArray(Prefs.perfilesVoz)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val h = o.getJSONArray("h")
            Perfil(o.getString("n"), o.getBoolean("d"), FloatArray(h.length()) { h.getDouble(it).toFloat() })
        }
    } catch (_: Exception) { emptyList() }

    private fun guardarTodos(l: List<Perfil>) {
        val a = JSONArray()
        for (p in l) a.put(JSONObject().put("n", p.nombre).put("d", p.dueno).put("h", JSONArray(p.huella.map { it.toDouble() })))
        Prefs.perfilesVoz = a.toString()
    }

    fun guardar(nombre: String, dueno: Boolean, huella: FloatArray) {
        val resto = perfiles().filter { if (dueno) !it.dueno else !(it.nombre.equals(nombre, true) && !it.dueno) }
        guardarTodos(resto + Perfil(nombre, dueno, huella))
    }

    fun quitar(nombre: String): Boolean {
        val l = perfiles()
        val n = l.filterNot { !it.dueno && Frase.normalizar(it.nombre) == Frase.normalizar(nombre) }
        guardarTodos(n)
        return n.size != l.size
    }

    fun hayDueno() = perfiles().any { it.dueno }
    fun invitados() = perfiles().filter { !it.dueno }

    /** El perfil más parecido y su similitud. */
    fun identificar(h: FloatArray): Pair<Perfil?, Float> {
        var mejor: Perfil? = null
        var max = -1f
        for (p in perfiles()) {
            val s = similitud(h, p.huella)
            if (s > max) { max = s; mejor = p }
        }
        return mejor to max
    }

    val modoInvitado get() = System.currentTimeMillis() < Prefs.invitadoHasta

    /** Graba [ms] milisegundos del micrófono (para registrar voces). Llamar en un hilo secundario. */
    fun grabar(ms: Int, alNivel: ((Float) -> Unit)? = null): ShortArray {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
        val total = 16 * ms
        val out = ShortArray(total)
        var pos = 0
        try {
            ar.startRecording()
            val buf = ShortArray(1600)
            while (pos < total) {
                val n = ar.read(buf, 0, minOf(buf.size, total - pos))
                if (n <= 0) break
                System.arraycopy(buf, 0, out, pos, n)
                pos += n
                if (alNivel != null) {
                    var e = 0.0
                    for (i in 0 until n) e += buf[i] * buf[i].toDouble()
                    alNivel((sqrt(e / n) / 600.0).toFloat().coerceIn(0f, 10f))
                }
            }
        } finally {
            try { ar.stop() } catch (_: Exception) {}
            ar.release()
        }
        return recortarSilencio(out.copyOf(pos))
    }

    /** Quita el silencio del inicio y del final. */
    fun recortarSilencio(pcm: ShortArray): ShortArray {
        val bloque = 320
        fun energia(i: Int): Double { var e = 0.0; for (k in i until minOf(i + bloque, pcm.size)) e += pcm[k] * pcm[k].toDouble(); return e / bloque }
        var maxE = 0.0
        var i = 0
        while (i < pcm.size) { maxE = maxOf(maxE, energia(i)); i += bloque }
        val umbral = maxE * 0.02
        var ini = 0
        while (ini < pcm.size && energia(ini) < umbral) ini += bloque
        var fin = pcm.size
        while (fin > ini && energia((fin - bloque).coerceAtLeast(0)) < umbral) fin -= bloque
        ini = (ini - 3200).coerceAtLeast(0)
        fin = (fin + 3200).coerceAtMost(pcm.size)
        return if (fin - ini < 8000) pcm else pcm.copyOfRange(ini, fin)
    }
}

/**
 * Captura continua del micrófono para la palabra de activación: alimenta al reconocedor sin internet
 * y guarda los últimos segundos de audio para saber QUIÉN dijo la palabra.
 */
class Captura(
    private val rec: Recognizer,
    /** true = con música/video sonando: usa el micrófono con cancelación de eco del sistema. */
    val conEco: Boolean = false,
    private val alMorir: (() -> Unit)? = null,
    private val alTexto: (String, Captura) -> Unit
) {
    private val anillo = ShortArray(16000 * 3)
    private var pos = 0
    private var total = 0L
    @Volatile var activa = true
    private val ar: AudioRecord
    private val hilo: Thread

    init {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val fuente = if (conEco) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        @Suppress("MissingPermission")
        var a = AudioRecord(fuente, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
        if (a.state != AudioRecord.STATE_INITIALIZED && conEco) {
            a.release()
            @Suppress("MissingPermission")
            a = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
        }
        if (a.state != AudioRecord.STATE_INITIALIZED) { a.release(); throw IllegalStateException("micrófono no disponible") }
        ar = a
        if (conEco) {
            try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(ar.audioSessionId)?.enabled = true } catch (_: Throwable) {}
            try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(ar.audioSessionId)?.enabled = true } catch (_: Throwable) {}
        }
        ar.startRecording()
        hilo = Thread({ bucle() }, "yarvis-captura").apply { start() }
    }

    private fun bucle() {
        val buf = ShortArray(1600)
        try {
            while (activa) {
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                synchronized(anillo) {
                    for (i in 0 until n) { anillo[pos] = buf[i]; pos = (pos + 1) % anillo.size }
                    total += n
                }
                val fin = rec.acceptWaveForm(buf, n)
                val json = if (fin) rec.getResult() else rec.getPartialResult()
                if (activa) alTexto(json, this)
            }
        } catch (_: Throwable) {
        }
        // Si el micrófono se cortó (otra app lo tomó, el sistema lo cerró…), avisar para volver a abrirlo
        if (activa) alMorir?.invoke()
    }

    /** Los últimos [ms] milisegundos de audio. */
    fun ultimos(ms: Int): ShortArray = synchronized(anillo) {
        val n = minOf(16 * ms, minOf(total, anillo.size.toLong()).toInt())
        val r = ShortArray(n)
        var p = (pos - n + anillo.size) % anillo.size
        for (i in 0 until n) { r[i] = anillo[p]; p = (p + 1) % anillo.size }
        r
    }

    fun detener() {
        activa = false
        try { ar.stop() } catch (_: Exception) {}
        if (Thread.currentThread() !== hilo) try { hilo.join(1000) } catch (_: Exception) {}
        try { ar.release() } catch (_: Exception) {}
    }
}
