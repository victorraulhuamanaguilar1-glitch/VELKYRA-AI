package com.velkyra.yarvis

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.abs

/**
 * «Odín, ¿dónde estás?» o dos aplausos: el celular suena fuerte (aunque esté en silencio),
 * vibra y parpadea la linterna hasta que lo encuentre.
 */
object Localizador {

    private val principal = Handler(Looper.getMainLooper())
    private var reproductor: MediaPlayer? = null
    private var volumenAntes = -1
    private var receptor: BroadcastReceiver? = null
    private var linternaOn = false
    @Volatile var sonando = false
        private set
    private const val ID_NOTIF = 4100
    const val ACCION_PARAR = "com.velkyra.yarvis.PARAR_LOCALIZADOR"

    /** [antirrobo] = solo se detiene al desbloquear el celular (no al encender la pantalla) y dura 3 minutos. */
    fun sonar(ctx: Context = Yarvis.ctx, antirrobo: Boolean = false) {
        if (sonando) return
        sonando = true
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            volumenAntes = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            reproductor = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}
        try {
            val v = ctx.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0))
        } catch (_: Exception) {}
        parpadear(ctx)
        // Se detiene al encender o desbloquear la pantalla, desde la notificación o al minuto
        receptor = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { parar(c) }
        }
        try {
            ctx.registerReceiver(receptor, IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                if (!antirrobo) { addAction(Intent.ACTION_SCREEN_ON); addAction(ACCION_PARAR) }
            }, Context.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {}
        if (!antirrobo) notificar(ctx)
        principal.postDelayed({ parar(ctx) }, if (antirrobo) 180_000 else 60_000)
    }

    private fun parpadear(ctx: Context) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        } catch (_: Exception) { null } ?: return
        val r = object : Runnable {
            override fun run() {
                if (!sonando) { try { cm.setTorchMode(id, false) } catch (_: Exception) {}; return }
                linternaOn = !linternaOn
                try { cm.setTorchMode(id, linternaOn) } catch (_: Exception) {}
                principal.postDelayed(this, 450)
            }
        }
        principal.post(r)
    }

    private fun notificar(ctx: Context) {
        if (!ctx.tiene(android.Manifest.permission.POST_NOTIFICATIONS)) return
        val pi = PendingIntent.getBroadcast(ctx, 41, Intent(ACCION_PARAR).setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, Avisos.CANAL_SISTEMA)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("¡Aquí estoy!")
            .setContentText("Toque para dejar de sonar.")
            .setContentIntent(pi)
            .addAction(Notification.Action.Builder(null, "LO ENCONTRÉ", pi).build())
            .setOngoing(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_NOTIF, n)
    }

    fun parar(ctx: Context = Yarvis.ctx) {
        if (!sonando) return
        sonando = false
        principal.removeCallbacksAndMessages(null)
        try { reproductor?.stop() } catch (_: Exception) {}
        try { reproductor?.release() } catch (_: Exception) {}
        reproductor = null
        try { ctx.getSystemService(Vibrator::class.java)?.cancel() } catch (_: Exception) {}
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (volumenAntes >= 0) try { am.setStreamVolume(AudioManager.STREAM_ALARM, volumenAntes, 0) } catch (_: Exception) {}
        volumenAntes = -1
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }?.let { cm.setTorchMode(it, false) }
        } catch (_: Exception) {}
        receptor?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        receptor = null
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_NOTIF)
    }
}

/**
 * Detecta dos aplausos seguidos en el audio del micrófono (golpes secos y cortos, separados entre 0,2 y 0,8 s).
 * Se alimenta con el mismo audio de la palabra de activación, sin gastar más batería.
 */
class DetectorAplausos(private val alDetectar: () -> Unit) {
    private var fondo = 300.0            // nivel de ruido de fondo (promedio)
    private var ultimoGolpe = 0L          // instante del último golpe (en muestras)
    private var golpes = 0
    private var muestras = 0L
    private var picoPrevio = 0
    private var esperandoCaida = 0L       // muestra en la que hay que verificar que el sonido cayó
    private var picoGolpe = 0

    fun procesar(buf: ShortArray, n: Int) {
        val trama = 160 // 10 ms
        var i = 0
        while (i + trama <= n) {
            var pico = 0
            var suma = 0.0
            for (k in i until i + trama) { val v = abs(buf[k].toInt()); if (v > pico) pico = v; suma += v }
            val media = suma / trama
            muestras += trama
            // ¿El golpe anterior fue seco? (a los 80 ms el sonido tiene que haber bajado mucho)
            if (esperandoCaida in 1..muestras) {
                esperandoCaida = 0
                if (pico < picoGolpe * 0.35) registrarGolpe() else golpes = 0
            }
            val fuerte = pico > 14000 && pico > fondo * 9 && picoPrevio < pico * 0.4
            if (fuerte && esperandoCaida == 0L) {
                esperandoCaida = muestras + 1280 // 80 ms después
                picoGolpe = pico
            }
            if (!fuerte) fondo = fondo * 0.98 + media * 0.02
            picoPrevio = pico
            i += trama
        }
    }

    private fun registrarGolpe() {
        val dist = muestras - ultimoGolpe
        golpes = if (dist in 3200..12800) golpes + 1 else 1   // 0,2 s … 0,8 s
        ultimoGolpe = muestras
        if (golpes >= 2) {
            golpes = 0
            alDetectar()
        }
    }
}
