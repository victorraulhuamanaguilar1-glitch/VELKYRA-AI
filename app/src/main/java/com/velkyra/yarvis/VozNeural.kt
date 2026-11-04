package com.velkyra.yarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Voz neuronal de narrador que funciona DENTRO del celular (sin internet, sin límites).
 * Genera el audio oración por oración y lo va reproduciendo mientras prepara la siguiente.
 */
object VozNeural {

    data class Narrador(val id: String, val nombre: String, val descripcion: String)

    val NARRADORES = listOf(
        Narrador("es_MX-ald", "Narrador latino", "Voz neuronal masculina · acento latinoamericano"),
        Narrador("es_ES-davefx", "Narrador español", "Voz neuronal masculina · acento de España")
    )

    private const val CARPETA = "voces"
    private val principal = Handler(Looper.getMainLooper())

    @Volatile private var motor: OfflineTts? = null
    @Volatile private var motorId = ""
    @Volatile var lista = false
        private set
    @Volatile var error: String? = null
        private set
    private var preparando = false

    private val generacion = AtomicInteger(0)
    @Volatile private var pista: AudioTrack? = null

    /** Prepara la voz elegida (copia los archivos la primera vez y carga el modelo). */
    fun preparar(ctx: Context, alTerminar: ((Boolean) -> Unit)? = null) {
        val id = Prefs.vozNeural
        if (lista && motorId == id) { alTerminar?.invoke(true); return }
        if (preparando) return
        preparando = true
        val app = ctx.applicationContext
        Thread {
            val ok = try {
                val base = copiarArchivos(app) ?: throw IllegalStateException("Las voces no están dentro de la app")
                val dir = File(base, id)
                val cfg = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = File(dir, "model.onnx").absolutePath,
                            tokens = File(dir, "tokens.txt").absolutePath,
                            dataDir = File(base, "espeak-ng-data").absolutePath
                        ),
                        numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                        debug = false,
                        provider = "cpu"
                    ),
                    maxNumSentences = 1
                )
                val nuevo = OfflineTts(null, cfg)
                motor?.let { viejo -> try { viejo.release() } catch (_: Throwable) {} }
                motor = nuevo
                motorId = id
                error = null
                true
            } catch (e: Throwable) {
                error = e.javaClass.simpleName + ": " + (e.message ?: "")
                false
            }
            principal.post {
                preparando = false
                lista = ok
                alTerminar?.invoke(ok)
            }
        }.start()
    }

    /** Copia modelos y datos de pronunciación al almacenamiento interno (solo la primera vez). */
    private fun copiarArchivos(ctx: Context): File? {
        val version = try { ctx.assets.open("$CARPETA/VERSION").bufferedReader().use { it.readText().trim() } } catch (_: Exception) { return null }
        val destino = File(ctx.filesDir, CARPETA)
        val marca = File(destino, "VERSION")
        if (marca.exists() && marca.readText().trim() == version) return destino
        destino.deleteRecursively()
        copiar(ctx, CARPETA, destino)
        return if (marca.exists()) destino else null
    }

    private fun copiar(ctx: Context, ruta: String, destino: File) {
        val hijos = ctx.assets.list(ruta)
        if (hijos.isNullOrEmpty()) {
            destino.parentFile?.mkdirs()
            try {
                ctx.assets.open(ruta).use { e -> destino.outputStream().use { s -> e.copyTo(s, 64 * 1024) } }
            } catch (_: Exception) {
                destino.mkdirs()
            }
            return
        }
        destino.mkdirs()
        for (h in hijos) copiar(ctx, "$ruta/$h", File(destino, h))
    }

    /**
     * Habla el texto. [alEmpezar] se llama cuando suena el primer audio; [alTerminar] al final
     * (no se llama si se detuvo). Devuelve false si la voz neuronal no está lista.
     */
    fun hablar(texto: String, alEmpezar: () -> Unit, alTerminar: () -> Unit): Boolean {
        val m = motor ?: return false
        if (!lista) return false
        detener()
        val miGen = generacion.incrementAndGet()
        val oraciones = dividir(texto)
        val cola = LinkedBlockingQueue<FloatArray>(3)
        val FIN = FloatArray(0)
        val velocidad = Prefs.velocidad.coerceIn(0.7f, 1.3f)

        // Hilo que genera el audio oración por oración
        Thread {
            try {
                for (o in oraciones) {
                    if (generacion.get() != miGen) break
                    val audio = synchronized(this) { m.generate(o, 0, velocidad) }
                    if (generacion.get() != miGen) break
                    cola.put(audio.samples)
                }
            } catch (_: Throwable) {
            } finally {
                try { cola.put(FIN) } catch (_: Throwable) {}
            }
        }.start()

        // Hilo que reproduce
        Thread {
            val tasa = try { m.sampleRate() } catch (_: Throwable) { 22050 }
            val minimo = AudioTrack.getMinBufferSize(tasa, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(tasa)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minimo, tasa * 4 / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            pista = t
            try {
                // El tono (grave/agudo) se aplica al reproducir
                try { t.playbackParams = PlaybackParams().setPitch(Prefs.tonoNeural.coerceIn(0.7f, 1.3f)).setSpeed(1f) } catch (_: Throwable) {}
                var empezo = false
                var escritos = 0L
                while (generacion.get() == miGen) {
                    val trozo = cola.take()
                    if (trozo === FIN) break
                    if (!empezo) {
                        t.play()
                        empezo = true
                        principal.post { if (generacion.get() == miGen) alEmpezar() }
                    }
                    var pos = 0
                    while (pos < trozo.size && generacion.get() == miGen) {
                        val n = t.write(trozo, pos, trozo.size - pos, AudioTrack.WRITE_BLOCKING)
                        if (n <= 0) break
                        pos += n
                    }
                    escritos += trozo.size
                }
                if (generacion.get() == miGen && empezo) {
                    // Esperar a que termine de sonar lo que queda en el búfer
                    val limite = System.currentTimeMillis() + 15000
                    while (generacion.get() == miGen && t.playbackHeadPosition < escritos && System.currentTimeMillis() < limite) {
                        Thread.sleep(40)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { t.stop() } catch (_: Throwable) {}
                try { t.release() } catch (_: Throwable) {}
                if (pista === t) pista = null
                if (generacion.get() == miGen) principal.post { alTerminar() }
            }
        }.start()
        return true
    }

    fun detener() {
        generacion.incrementAndGet()
        pista?.let {
            try { it.pause(); it.flush() } catch (_: Throwable) {}
        }
    }

    /** Divide el texto en oraciones cortas para empezar a hablar rápido. */
    private fun dividir(texto: String): List<String> {
        val r = mutableListOf<String>()
        for (o in Regex("(?<=[.!?…;:])\\s+").split(texto.trim())) {
            if (o.isBlank()) continue
            if (o.length <= 220) { r.add(o); continue }
            // Oraciones muy largas: cortar en comas
            val sb = StringBuilder()
            for (p in o.split(Regex("(?<=,)\\s+"))) {
                if (sb.length + p.length > 220 && sb.isNotEmpty()) { r.add(sb.toString()); sb.clear() }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(p)
            }
            if (sb.isNotEmpty()) r.add(sb.toString())
        }
        return r.ifEmpty { listOf(texto) }
    }
}
