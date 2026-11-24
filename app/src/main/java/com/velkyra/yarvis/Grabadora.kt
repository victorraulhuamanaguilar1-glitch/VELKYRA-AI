package com.velkyra.yarvis

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Odín, graba la reunión»: graba con el mismo micrófono de la palabra de activación (así sigue
 * escuchándole para «termina la grabación»), comprime en AAC y al terminar transcribe y redacta el acta.
 */
object Grabadora {

    @Volatile var activa = false
        private set
    var inicio = 0L
        private set
    private var titulo = "Reunión"
    private var archivo: File? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var pista = -1
    private var muxerIniciado = false
    private var muestras = 0L
    private val candado = Any()
    private const val ID_NOTIF = 4300

    fun duracionTexto(): String {
        val s = (System.currentTimeMillis() - inicio) / 1000
        val h = s / 3600; val m = (s % 3600) / 60
        return when {
            h > 0 -> "$h hora${if (h > 1) "s" else ""} y $m minutos"
            m > 0 -> "$m minuto${if (m > 1) "s" else ""}"
            else -> "${s} segundos"
        }
    }

    fun iniciar(ctx: Context, nombre: String): Boolean = synchronized(candado) {
        if (activa) return true
        try {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
            val f = File(dir, "reunion_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".m4a")
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 16000, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 32000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            muxer = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            pista = -1
            muxerIniciado = false
            muestras = 0
            archivo = f
            titulo = nombre
            inicio = System.currentTimeMillis()
            activa = true
            notificar(ctx, "🔴 Grabando: $nombre", "Diga «${Prefs.palabraActivacion}, termina la grabación» para terminar y hacer el acta.", true)
            true
        } catch (_: Exception) {
            liberar()
            false
        }
    }

    /** Recibe el audio del micrófono (16 kHz, mono, 16 bits). Se llama desde el hilo de captura. */
    fun escribir(buf: ShortArray, n: Int) {
        if (!activa || n <= 0) return
        synchronized(candado) {
            val c = codec ?: return
            var off = 0
            while (off < n) {
                val idx = try { c.dequeueInputBuffer(10_000) } catch (_: Exception) { return }
                if (idx < 0) break
                val ib = c.getInputBuffer(idx) ?: break
                ib.clear()
                ib.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val cabe = minOf(n - off, ib.remaining() / 2)
                for (k in 0 until cabe) ib.putShort(buf[off + k])
                val pts = muestras * 1_000_000L / 16000
                c.queueInputBuffer(idx, 0, cabe * 2, pts, 0)
                muestras += cabe
                off += cabe
            }
            drenar(false)
        }
    }

    private fun drenar(fin: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        val info = MediaCodec.BufferInfo()
        var intentos = 0
        while (true) {
            val idx = try { c.dequeueOutputBuffer(info, if (fin) 10_000 else 0) } catch (_: Exception) { return }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!fin || ++intentos > 100) return else continue
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    pista = m.addTrack(c.outputFormat)
                    m.start()
                    muxerIniciado = true
                }
                idx >= 0 -> {
                    val ob = c.getOutputBuffer(idx)
                    if (ob != null && info.size > 0 && muxerIniciado && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        m.writeSampleData(pista, ob, info)
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Termina la grabación y devuelve el archivo (null si no se grabó nada). */
    fun detener(): File? = synchronized(candado) {
        if (!activa) return null
        activa = false
        val f = archivo
        try {
            val c = codec
            if (c != null) {
                val idx = c.dequeueInputBuffer(50_000)
                if (idx >= 0) c.queueInputBuffer(idx, 0, 0, muestras * 1_000_000L / 16000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drenar(true)
            }
        } catch (_: Exception) {}
        val ok = muxerIniciado && muestras > 16000
        liberar()
        if (ok) f else { f?.delete(); null }
    }

    private fun liberar() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { if (muxerIniciado) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        codec = null; muxer = null; muxerIniciado = false
    }

    /** Termina, guarda el audio, transcribe y redacta el acta. Se llama desde un hilo secundario. */
    fun procesar(ctx: Context): String {
        val tr = Prefs.trato
        val duracion = duracionTexto()
        val f = detener() ?: return "No se grabó audio, $tr."
        val nombre = titulo
        ServicioEscucha.actualizar(ctx)
        notificar(ctx, "Preparando el acta…", "Transcribiendo $duracion de grabación. Le aviso cuando esté lista.", true)
        val audioUri = copiarA(ctx, f, "audio/mp4", Environment.DIRECTORY_RECORDINGS + "/ODIN", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        val texto = try { Transcriptor.transcribir(f, "audio/mp4") } catch (e: Exception) {
            notificar(ctx, "No pude transcribir la grabación", "El audio quedó guardado en Grabaciones › ODIN. ${CerebroIA.detalle(e)}", false, audioUri)
            return "Guardé el audio en Grabaciones, carpeta ODIN, pero no pude transcribirlo ahora, $tr. ${if (!CerebroIA.hayInternet()) "Necesito internet." else ""}".trim()
        }
        if (texto.isBlank()) return "La grabación quedó guardada, $tr, pero no se entendió ninguna voz."
        val fecha = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, h:mm a", Locale.forLanguageTag("es-PE")).format(Date(inicio))
        val acta = try {
            CerebroIA.preguntar(texto.take(90_000), buscar = false, recordar = false, sistemaFijo = """
                Eres el secretario de ${Prefs.nombre.ifBlank { "el usuario" }}, en Perú. Con la transcripción de una reunión, redacta un ACTA formal en español:
                ACTA DE REUNIÓN — fecha: $fecha — duración: $duracion.
                1. Participantes (los que se mencionan; si no se sabe, "No se identificaron").
                2. Temas tratados (breve).
                3. Acuerdos (numerados, claros, con responsables y fechas si se dijeron).
                4. Tareas pendientes.
                5. Resumen general (un párrafo).
                No inventes nada que no esté en la transcripción. Texto plano, sin markdown ni asteriscos.
            """.trimIndent())
        } catch (_: Exception) { "" }
        val contenido = buildString {
            if (acta.isNotBlank()) { appendLine(acta.trim()); appendLine(); appendLine("────────────────────────") }
            appendLine("TRANSCRIPCIÓN COMPLETA — $nombre — $fecha")
            appendLine()
            appendLine(texto.trim())
        }
        val tmp = File(ctx.cacheDir, "Acta_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(inicio)) + ".txt").apply { writeText(contenido) }
        val actaUri = copiarA(ctx, tmp, "text/plain", Environment.DIRECTORY_DOCUMENTS + "/ODIN", MediaStore.Files.getContentUri("external"))
        ultimaActa = contenido
        ultimaActaUri = actaUri
        notificar(ctx, "✅ Acta lista: $nombre", "Toque para abrirla. Está en Documentos › ODIN.", false, actaUri, "text/plain")
        val resumen = Regex("(?is)resumen general[^\\n]*\\n(.+)").find(acta)?.groupValues?.get(1)?.trim()?.split(Regex("(?<=[.!?])\\s+"))?.take(3)?.joinToString(" ")
        return "El acta está lista, $tr. La guardé en Documentos, carpeta ODIN, con la transcripción completa." +
            (resumen?.let { " En resumen: $it" } ?: "")
    }

    @Volatile var ultimaActa: String? = null
    @Volatile var ultimaActaUri: Uri? = null

    /** Copia un archivo a una carpeta pública (Grabaciones, Documentos). */
    private fun copiarA(ctx: Context, f: File, mime: String, carpeta: String, coleccion: Uri): Uri? = try {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, carpeta)
        }
        val uri = ctx.contentResolver.insert(coleccion, v)
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        uri
    } catch (_: Exception) { null }

    fun compartirActa(ctx: Context): Boolean {
        val t = ultimaActa ?: return false
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t)
        ctx.startActivity(Intent.createChooser(i, "Compartir acta").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun notificar(ctx: Context, titulo: String, texto: String, fija: Boolean, abrir: Uri? = null, mime: String = "audio/mp4") {
        if (!ctx.tiene(android.Manifest.permission.POST_NOTIFICATIONS)) return
        val b = Notification.Builder(ctx, Avisos.CANAL_SISTEMA)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(titulo).setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setOngoing(fija).setAutoCancel(!fija)
        if (abrir != null) b.setContentIntent(PendingIntent.getActivity(ctx, 43,
            Intent(Intent.ACTION_VIEW).setDataAndType(abrir, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        ctx.getSystemService(NotificationManager::class.java).notify(ID_NOTIF, b.build())
    }
}
