package com.velkyra.yarvis

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mensajes programados: «mañana a las 7 mándale a Juan: buenos días».
 * A la hora indicada responde desde la notificación del chat si existe; si no, lo envía por WhatsApp
 * (con la accesibilidad) o SMS. Si el celular está bloqueado, lo envía apenas lo desbloquee.
 */
object Programados {

    data class Item(val id: Int, val quien: String, val texto: String, val cuando: Long, val sms: Boolean, val listo: Boolean = false)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("yarvis", Context.MODE_PRIVATE)

    fun lista(ctx: Context = Yarvis.ctx): List<Item> = try {
        val a = JSONArray(sp(ctx).getString("programados", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Item(o.getInt("i"), o.getString("q"), o.getString("t"), o.getLong("c"), o.optBoolean("s"), o.optBoolean("l"))
        }
    } catch (_: Exception) { emptyList() }

    private fun guardar(ctx: Context, l: List<Item>) {
        val a = JSONArray()
        for (x in l) a.put(JSONObject().put("i", x.id).put("q", x.quien).put("t", x.texto).put("c", x.cuando).put("s", x.sms).put("l", x.listo))
        sp(ctx).edit().putString("programados", a.toString()).apply()
    }

    fun crear(ctx: Context, quien: String, texto: String, cuando: Long, sms: Boolean): Item {
        val id = Prefs.contadorIds + 1
        Prefs.contadorIds = id
        val it = Item(id, quien, texto, cuando, sms)
        guardar(ctx, lista(ctx) + it)
        programar(ctx, it)
        return it
    }

    fun cancelar(ctx: Context, quien: String?): List<Item> {
        val l = lista(ctx)
        val quitar = if (quien.isNullOrBlank()) l else l.filter { Frase.normalizar(it.quien).contains(Frase.normalizar(quien)) }
        for (x in quitar) alarmas(ctx).cancel(intento(ctx, x))
        guardar(ctx, l - quitar.toSet())
        return quitar
    }

    private fun alarmas(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun intento(ctx: Context, i: Item) = PendingIntent.getBroadcast(
        ctx, i.id, Intent(ctx, ReceptorProgramado::class.java).putExtra("id", i.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun programar(ctx: Context, i: Item) {
        val am = alarmas(ctx)
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, i.cuando, intento(ctx, i))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, i.cuando, intento(ctx, i))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, i.cuando, intento(ctx, i))
        }
    }

    fun reprogramarTodos(ctx: Context) {
        for (i in lista(ctx)) if (!i.listo) programar(ctx, i)
    }

    /** Llegó la hora de un mensaje. */
    fun disparar(ctx: Context, id: Int) {
        val i = lista(ctx).firstOrNull { it.id == id } ?: return
        enviar(ctx, i)
    }

    private fun enviar(ctx: Context, i: Item) {
        // 1) Desde la notificación del chat (funciona aunque esté bloqueado)
        if (!i.sms) ServicioNotificaciones.responder(ctx, i.quien, i.texto)?.let { nombre ->
            terminar(ctx, i, "Mensaje programado enviado a $nombre: «${i.texto}»")
            return
        }
        val bloqueado = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!i.sms && bloqueado) {
            // WhatsApp necesita la pantalla desbloqueada: lo dejo listo para cuando desbloquee
            guardar(ctx, lista(ctx).map { if (it.id == i.id) it.copy(listo = true) else it })
            avisar(ctx, i.id, "Mensaje para ${i.quien} listo", "Se enviará apenas desbloquee el celular: «${i.texto}»")
            return
        }
        val r = Yarvis.cerebro.enviarProgramado(i.quien, i.texto, i.sms)
        terminar(ctx, i, r)
    }

    private fun terminar(ctx: Context, i: Item, texto: String) {
        guardar(ctx, lista(ctx).filterNot { it.id == i.id })
        avisar(ctx, i.id, "Mensaje programado", texto)
    }

    /** El usuario desbloqueó el celular: envía los mensajes de WhatsApp que esperaban. */
    fun alDesbloquear(ctx: Context) {
        val pendientes = lista(ctx).filter { it.listo }
        if (pendientes.isEmpty()) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            for (p in pendientes) {
                val r = Yarvis.cerebro.enviarProgramado(p.quien, p.texto, p.sms)
                terminar(ctx, p, r)
            }
        }, 1500)
    }

    private fun avisar(ctx: Context, id: Int, titulo: String, texto: String) {
        if (!ctx.tiene(android.Manifest.permission.POST_NOTIFICATIONS)) return
        val n = Notification.Builder(ctx, Avisos.CANAL_SISTEMA)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(titulo).setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setAutoCancel(true).build()
        ctx.getSystemService(NotificationManager::class.java).notify(5000 + (id and 0xFFF), n)
    }
}

class ReceptorProgramado : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Programados.disparar(ctx, intent.getIntExtra("id", 0))
    }
}
