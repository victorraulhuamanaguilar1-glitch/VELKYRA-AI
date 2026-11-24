package com.velkyra.yarvis

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.sqrt

/** Tipo de cambio del día (dólar, euro… a soles). */
object Divisas {
    private var cache: JSONObject? = null
    private var cacheHora = 0L

    private fun tasas(): JSONObject {
        cache?.let { if (System.currentTimeMillis() - cacheHora < 3 * 3_600_000L) return it }
        val c = URL("https://open.er-api.com/v6/latest/PEN").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("rates")
        c.disconnect()
        cache = j; cacheHora = System.currentTimeMillis()
        return j
    }

    val MONEDAS = mapOf(
        "dolar" to ("USD" to "dólar"), "dolares" to ("USD" to "dólar"), "euro" to ("EUR" to "euro"), "euros" to ("EUR" to "euro"),
        "peso chileno" to ("CLP" to "peso chileno"), "peso argentino" to ("ARS" to "peso argentino"), "peso colombiano" to ("COP" to "peso colombiano"),
        "peso mexicano" to ("MXN" to "peso mexicano"), "boliviano" to ("BOB" to "boliviano"), "bolivianos" to ("BOB" to "boliviano"),
        "real" to ("BRL" to "real"), "reales" to ("BRL" to "real"), "libra" to ("GBP" to "libra"), "yen" to ("JPY" to "yen"), "yuan" to ("CNY" to "yuan")
    )

    fun moneda(t: String): Pair<String, String>? =
        MONEDAS.entries.sortedByDescending { it.key.length }.firstOrNull { Regex("\\b${it.key}\\b").containsMatchIn(t) }?.value

    /** Cuántos soles vale 1 unidad de [codigo]. */
    fun enSoles(codigo: String): Double = 1.0 / tasas().getDouble(codigo)

    fun redondo(x: Double) = if (x >= 100) String.format(java.util.Locale.US, "%,.0f", x).replace(',', ' ') else String.format(java.util.Locale.US, "%.2f", x)
}

/**
 * Modo antirrobo: con el celular bloqueado, si lo desconectan del cargador o lo mueven, suena una alarma.
 * Se desactiva solo cuando el dueño desbloquea el celular (con su clave o huella).
 */
object Antirrobo {
    @Volatile var armado = false
        private set
    private var armadoEn = 0L
    private var receptor: BroadcastReceiver? = null
    private var sensores: SensorEventListener? = null
    private var ultimo: FloatArray? = null
    private val principal = Handler(Looper.getMainLooper())

    fun armar(ctx: Context) {
        if (armado) return
        armado = true
        armadoEn = System.currentTimeMillis()
        receptor = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_USER_PRESENT -> desarmar(c, porDueno = true)
                    Intent.ACTION_POWER_DISCONNECTED -> disparar(c, "Desconectaron el cargador")
                }
            }
        }
        ctx.registerReceiver(receptor, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        val sm = ctx.getSystemService(SensorManager::class.java)
        val acel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensores = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // 8 segundos de gracia para dejar el celular quieto
                if (System.currentTimeMillis() - armadoEn < 8000) { ultimo = e.values.clone(); return }
                val u = ultimo
                ultimo = e.values.clone()
                if (u == null) return
                val d = sqrt((e.values[0] - u[0]).let { it * it } + (e.values[1] - u[1]).let { it * it } + (e.values[2] - u[2]).let { it * it })
                if (d > 3.0f) disparar(ctx, "Movieron el celular")
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (acel != null) sm.registerListener(sensores, acel, SensorManager.SENSOR_DELAY_NORMAL)
        notificar(ctx, "🛡️ Modo antirrobo activado", "Si lo mueven o desconectan el cargador, sonará una alarma. Se desactiva al desbloquear el celular.")
    }

    private fun disparar(ctx: Context, motivo: String) {
        if (!armado || Localizador.sonando) return
        Localizador.sonar(ctx, antirrobo = true)
        notificar(ctx, "🚨 ¡Alerta! $motivo", "Desbloquee el celular para detener la alarma.")
    }

    fun desarmar(ctx: Context, porDueno: Boolean = false) {
        if (!armado) return
        armado = false
        receptor?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        receptor = null
        sensores?.let { ctx.getSystemService(SensorManager::class.java).unregisterListener(it) }
        sensores = null
        ultimo = null
        val sonaba = Localizador.sonando
        Localizador.parar(ctx)
        ctx.getSystemService(NotificationManager::class.java).cancel(4500)
        if (porDueno) principal.postDelayed({
            Yarvis.anunciar(if (sonaba) "Bienvenido, ${Prefs.trato}. Detuve la alarma; alguien movió su celular mientras no estaba." else "Desactivé el modo antirrobo, ${Prefs.trato}.")
        }, 1200)
    }

    private fun notificar(ctx: Context, t: String, x: String) {
        if (!ctx.tiene(android.Manifest.permission.POST_NOTIFICATIONS)) return
        val n = Notification.Builder(ctx, Avisos.CANAL_SISTEMA).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(t).setContentText(x).setOngoing(true).build()
        ctx.getSystemService(NotificationManager::class.java).notify(4500, n)
    }
}

/** Widget de pantalla de inicio: un toque y ODÍN le escucha. */
class OdinWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, am: AppWidgetManager, ids: IntArray) {
        val pi = PendingIntent.getActivity(ctx, 4600,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("escuchar", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_odin)
            v.setTextViewText(R.id.widget_texto, "Hablar con ${ctx.getString(R.string.app_name)}")
            v.setOnClickPendingIntent(R.id.widget_raiz, pi)
            am.updateAppWidget(id, v)
        }
    }
}

/** Botón en los ajustes rápidos (la cortina de notificaciones). */
class TileOdin : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = android.service.quicksettings.Tile.STATE_INACTIVE; label = "ODÍN"; updateTile() }
    }

    override fun onClick() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("escuchar", true)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 4700, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
