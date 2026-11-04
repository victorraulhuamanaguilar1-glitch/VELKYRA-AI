package com.velkyra.yarvis

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/**
 * Servicio en segundo plano que escucha la palabra «Yarvis» SIN internet (Vosk).
 * Nada de lo que escucha sale del celular.
 */
class ServicioEscucha : Service(), RecognitionListener {

    companion object {
        private const val ID_NOTIF = 7
        private const val ACC_PAUSA = "com.velkyra.yarvis.PAUSA"
        private const val ACC_REANUDA = "com.velkyra.yarvis.REANUDA"
        private const val ACC_HABLAR = "com.velkyra.yarvis.HABLAR"

        @Volatile
        var instancia: ServicioEscucha? = null
            private set

        val activo get() = instancia != null
        val escuchandoPalabra get() = instancia?.speech != null

        /** Variantes de cómo el reconocedor puede escribir «Yarvis». */
        private val palabraJunta = Regex("(y|ll|j|h|g|i|sh)ar(v|b)i(s|z|ss|ces)")
        private val palabrasSueltas = setOf(
            "yarvis", "jarvis", "yarbis", "jarbis", "llarvis", "llarbis", "harvis", "harbis", "garvis", "yavis",
            "yabis", "javis", "jabis", "yarvi", "jarvi", "yarvic", "jervis", "yervis"
        )

        fun contienePalabra(texto: String): Boolean {
            val n = Frase.normalizar(texto)
            if (n.isBlank()) return false
            if (n.split(" ").any { it in palabrasSueltas }) return true
            return palabraJunta.containsMatchIn(n.replace(" ", ""))
        }

        fun iniciar(ctx: Context): Boolean {
            if (!ctx.tiene(Manifest.permission.RECORD_AUDIO)) return false
            return try {
                ctx.startForegroundService(Intent(ctx, ServicioEscucha::class.java))
                true
            } catch (e: Exception) {
                false
            }
        }

        fun detenerServicio(ctx: Context) {
            ctx.stopService(Intent(ctx, ServicioEscucha::class.java))
        }

        /** Aplica los cambios de ajustes (activar/desactivar, modo de pantalla). */
        fun actualizar(ctx: Context) {
            if (Prefs.escuchaActiva) {
                if (instancia == null) iniciar(ctx) else instancia?.revisar()
            } else {
                instancia?.revisar()
            }
        }

        fun pausar() { instancia?.pausaTemporal(true) }
        fun reanudar() { instancia?.pausaTemporal(false) }
    }

    private val principal = Handler(Looper.getMainLooper())
    private var modelo: Model? = null
    private var reconocedor: Recognizer? = null
    var speech: SpeechService? = null
        private set
    private var pausado = false
    private var pantallaEncendida = true
    private var cargando = false
    private var ultimaActivacion = 0L
    private var reanudadoEn = 0L

    private val receptorPantalla = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            pantallaEncendida = i.action != Intent.ACTION_SCREEN_OFF
            revisar()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
        pantallaEncendida = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (!enPrimerPlano()) {
            stopSelf()
            return
        }
        registerReceiver(receptorPantalla, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        cargarModelo()
    }

    private fun enPrimerPlano(): Boolean = try {
        startForeground(ID_NOTIF, notificacion(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        true
    } catch (e: Exception) {
        instancia = null
        Avisos.pedirActivacion(this)
        false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACC_PAUSA -> { Prefs.escuchaActiva = false; revisar() }
            ACC_REANUDA -> { Prefs.escuchaActiva = true; revisar() }
            ACC_HABLAR -> Yarvis.activar(false)
        }
        return START_STICKY
    }

    private fun cargarModelo() {
        if (modelo != null || cargando) return
        cargando = true
        actualizarNotificacion()
        Thread {
            val m = try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val ruta = ModeloVoz.preparar(this)
                if (ruta == null) null else Model(ruta)
            } catch (e: Throwable) {
                null
            }
            principal.post {
                cargando = false
                modelo = m
                revisar()
            }
        }.start()
    }

    fun revisar() {
        val debe = modelo != null && !pausado && Prefs.escuchaActiva &&
            (Prefs.modoEscucha == "siempre" || pantallaEncendida) &&
            tiene(Manifest.permission.RECORD_AUDIO)
        if (debe) iniciarEscucha() else detenerEscucha()
        actualizarNotificacion()
    }

    fun pausaTemporal(p: Boolean) {
        pausado = p
        if (!p) reanudadoEn = SystemClock.elapsedRealtime()
        revisar()
    }

    private fun iniciarEscucha() {
        if (speech != null) return
        val m = modelo ?: return
        try {
            val rec = Recognizer(m, 16000f)
            val s = SpeechService(rec, 16000f)
            s.startListening(this)
            reconocedor = rec
            speech = s
        } catch (e: Exception) {
            detenerEscucha()
        }
    }

    private fun detenerEscucha() {
        speech?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.shutdown() } catch (_: Exception) {}
        }
        speech = null
        reconocedor?.let { try { it.close() } catch (_: Exception) {} }
        reconocedor = null
    }

    private fun revisarTexto(json: String?) {
        if (json.isNullOrBlank()) return
        val texto = try {
            val o = JSONObject(json)
            o.optString("partial").ifBlank { o.optString("text") }
        } catch (_: Exception) { "" }
        if (texto.isBlank()) return
        val ahora = SystemClock.elapsedRealtime()
        if (ahora - ultimaActivacion < 2500 || ahora - reanudadoEn < 700) return
        if (contienePalabra(texto)) {
            ultimaActivacion = ahora
            detenerEscucha()
            Yarvis.activar(true)
        }
    }

    // --- Vosk -------------------------------------------------------------
    override fun onPartialResult(hypothesis: String?) = revisarTexto(hypothesis)
    override fun onResult(hypothesis: String?) = revisarTexto(hypothesis)
    override fun onFinalResult(hypothesis: String?) = revisarTexto(hypothesis)
    override fun onError(exception: Exception?) {
        detenerEscucha()
        principal.postDelayed({ revisar() }, 2000)
    }
    override fun onTimeout() {
        detenerEscucha()
        revisar()
    }

    // --- Notificación fija ----------------------------------------------------
    private fun notificacion(): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hablar = PendingIntent.getService(
            this, 3, Intent(this, ServicioEscucha::class.java).setAction(ACC_HABLAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val activa = Prefs.escuchaActiva
        val alternar = PendingIntent.getService(
            this, 4, Intent(this, ServicioEscucha::class.java).setAction(if (activa) ACC_PAUSA else ACC_REANUDA),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val texto = when {
            cargando -> "Preparando el reconocimiento de voz…"
            !activa -> "Escucha en pausa. Toque «Reanudar» para activarla."
            modelo == null -> "No se pudo cargar el modelo de voz."
            speech != null -> "Diga «Yarvis» para activarme."
            Prefs.modoEscucha == "pantalla" && !pantallaEncendida -> "En espera hasta que encienda la pantalla."
            else -> "Atendiendo su solicitud…"
        }
        return Notification.Builder(this, Avisos.CANAL_ESCUCHA)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("YARVIS en línea")
            .setContentText(texto)
            .setContentIntent(abrir)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Hablar ahora", hablar).build())
            .addAction(Notification.Action.Builder(null, if (activa) "Pausar" else "Reanudar", alternar).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun actualizarNotificacion() {
        if (instancia !== this) return
        try {
            getSystemService(android.app.NotificationManager::class.java).notify(ID_NOTIF, notificacion())
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        detenerEscucha()
        try { unregisterReceiver(receptorPantalla) } catch (_: Exception) {}
        modelo?.let { try { it.close() } catch (_: Exception) {} }
        modelo = null
        if (instancia === this) instancia = null
        super.onDestroy()
    }
}

/** Copia el modelo de voz en español desde la app al almacenamiento interno (solo la primera vez). */
object ModeloVoz {
    private const val CARPETA = "modelo-es"

    fun preparar(ctx: Context): String? {
        val am = ctx.assets
        val version = try { am.open("$CARPETA/VERSION").bufferedReader().use { it.readText().trim() } } catch (_: Exception) { return null }
        val destino = File(ctx.filesDir, CARPETA)
        val marca = File(destino, "VERSION")
        if (marca.exists() && marca.readText().trim() == version) return destino.absolutePath
        destino.deleteRecursively()
        copiar(ctx, CARPETA, destino)
        return if (marca.exists()) destino.absolutePath else null
    }

    private fun copiar(ctx: Context, ruta: String, destino: File) {
        val hijos = ctx.assets.list(ruta)
        if (hijos.isNullOrEmpty()) {
            destino.parentFile?.mkdirs()
            try {
                ctx.assets.open(ruta).use { e -> destino.outputStream().use { s -> e.copyTo(s) } }
            } catch (_: Exception) {
                destino.mkdirs()
            }
            return
        }
        destino.mkdirs()
        for (h in hijos) copiar(ctx, "$ruta/$h", File(destino, h))
    }
}

class ReceptorArranque : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Prefs.configurado) return
        Recordatorios.reprogramarTodos(ctx)
        if (Prefs.escuchaActiva && !ServicioEscucha.iniciar(ctx)) Avisos.pedirActivacion(ctx)
    }
}
