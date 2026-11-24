package com.velkyra.yarvis

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Cumpleaños: los de la agenda de contactos y los que el usuario le dicta («el cumpleaños de Juan es el 5 de mayo»).
 * Cada mañana avisa y ofrece mandar un saludo por WhatsApp escrito con su estilo.
 */
object Cumpleanos {

    data class Cumple(val nombre: String, val dia: Int, val mes: Int) // mes 1..12

    val MESES = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

    private fun sp(ctx: Context) = ctx.getSharedPreferences("yarvis", Context.MODE_PRIVATE)

    private fun propios(ctx: Context): List<Cumple> = try {
        val a = JSONArray(sp(ctx).getString("cumples", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Cumple(o.getString("n"), o.getInt("d"), o.getInt("m")) }
    } catch (_: Exception) { emptyList() }

    fun agregar(ctx: Context, c: Cumple) {
        val l = propios(ctx).filterNot { Frase.normalizar(it.nombre) == Frase.normalizar(c.nombre) } + c
        val a = JSONArray()
        for (x in l) a.put(JSONObject().put("n", x.nombre).put("d", x.dia).put("m", x.mes))
        sp(ctx).edit().putString("cumples", a.toString()).apply()
    }

    /** Todos: los dictados y los de los contactos. */
    fun todos(ctx: Context): List<Cumple> {
        val l = propios(ctx).toMutableList()
        if (ctx.tiene(Manifest.permission.READ_CONTACTS)) try {
            ctx.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE),
                "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Event.TYPE}=?",
                arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val f = c.getString(1) ?: continue
                    // Formatos: 1990-05-23, --05-23, 05/23
                    val m = Regex("(\\d{1,2})[-/](\\d{1,2})$").find(f) ?: continue
                    val mes = m.groupValues[1].toInt(); val dia = m.groupValues[2].toInt()
                    if (mes in 1..12 && dia in 1..31 && l.none { Frase.normalizar(it.nombre) == Frase.normalizar(n) }) l.add(Cumple(n, dia, mes))
                }
            }
        } catch (_: Exception) {}
        return l
    }

    fun deHoy(ctx: Context): List<Cumple> {
        val c = Calendar.getInstance()
        return todos(ctx).filter { it.dia == c.get(Calendar.DAY_OF_MONTH) && it.mes == c.get(Calendar.MONTH) + 1 }
    }

    /** Próximos [dias] días (sin hoy), con cuántos días faltan. */
    fun proximos(ctx: Context, dias: Int): List<Pair<Cumple, Int>> {
        val hoy = Calendar.getInstance()
        return todos(ctx).mapNotNull { x ->
            val c = Calendar.getInstance().apply { set(Calendar.MONTH, x.mes - 1); set(Calendar.DAY_OF_MONTH, x.dia) }
            var d = Tiempo.diasEntre(hoy, c)
            if (d < 0) { c.add(Calendar.YEAR, 1); d = Tiempo.diasEntre(hoy, c) }
            if (d in 1..dias) x to d else null
        }.sortedBy { it.second }
    }

    fun de(ctx: Context, nombre: String): Cumple? {
        val q = Frase.normalizar(nombre).replace(Regex("^(mi |el |la |de )"), "")
        return todos(ctx).firstOrNull { Frase.normalizar(it.nombre).contains(q) }
    }

    fun fecha(c: Cumple) = "${c.dia} de ${MESES[c.mes - 1]}"

    // --- Aviso diario --------------------------------------------------------------

    fun programar(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 5); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        val pi = PendingIntent.getBroadcast(ctx, 4400, Intent(ctx, ReceptorCumpleanos::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi) } catch (_: Exception) {}
    }

    fun avisarHoy(ctx: Context) {
        programar(ctx) // el de mañana
        val hoy = deHoy(ctx)
        if (hoy.isEmpty()) return
        val nombres = hoy.joinToString(" y ") { it.nombre }
        if (ctx.tiene(Manifest.permission.POST_NOTIFICATIONS)) {
            val n = Notification.Builder(ctx, Avisos.CANAL_RECORDATORIOS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("🎂 Hoy cumple años $nombres")
                .setContentText("Diga «${Prefs.palabraActivacion}, salúdalo por su cumpleaños» y le escribo por WhatsApp.")
                .setAutoCancel(true).build()
            ctx.getSystemService(NotificationManager::class.java).notify(4401, n)
        }
        if (!Modos.puedeAnunciar) return
        val primero = hoy.first()
        val r = Yarvis.cerebro.preguntarAlUsuario("${Prefs.trato.replaceFirstChar { it.uppercase() }}, hoy cumple años $nombres. ¿Le mando un saludo por WhatsApp a ${primero.nombre}?") { f ->
            Yarvis.cerebro.decidirSaludo(primero.nombre, f)
        }
        Yarvis.decirYEscuchar(r)
    }

    /** Escribe el saludo con el estilo del usuario (si tiene un chat automático con esa persona, usa esa guía). */
    fun redactarSaludo(nombre: String): String {
        val primerNombre = nombre.substringBefore(' ')
        val guia = ChatAsistido.buscar(nombre)?.guia?.takeLast(3000).orEmpty()
        return try {
            CerebroIA.preguntar("Escribe un saludo de cumpleaños para $primerNombre.", buscar = false, recordar = false,
                sistemaFijo = "Eres ${Prefs.nombre.ifBlank { "el usuario" }}, de Perú, escribiendo por WhatsApp. Escribe un saludo de cumpleaños cálido y natural, de 1 a 3 frases, con uno o dos emojis. " +
                    (if (guia.isNotBlank()) "Imita exactamente su forma de escribir según este chat real con esa persona:\n$guia\n" else "") +
                    "Responde solo con el mensaje, sin comillas.").trim().trim('"', '«', '»')
        } catch (_: Exception) { "¡Feliz cumpleaños, $primerNombre! 🎉 Que pases un día increíble, un fuerte abrazo." }
    }
}

class ReceptorCumpleanos : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Cumpleanos.avisarHoy(ctx)
    }
}
