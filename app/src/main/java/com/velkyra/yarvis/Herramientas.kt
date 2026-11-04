package com.velkyra.yarvis

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun Context.tiene(permiso: String) = checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED

/** Búsqueda en la agenda de contactos. */
object Contactos {
    data class Contacto(val nombre: String, val numero: String)

    fun buscar(ctx: Context, objetivo: String, estricto: Boolean = false): Contacto? {
        if (!ctx.tiene(Manifest.permission.READ_CONTACTS)) return null
        val obj = Frase.normalizar(objetivo)
            .replace(Regex("^(a mi |mi |al |a la |a |la |el |los |las )"), "")
            .trim()
        if (obj.isEmpty()) return null
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.IS_PRIMARY),
            null, null, null
        ) ?: return null
        var exacto: Contacto? = null
        var empieza: Contacto? = null
        var contiene: Contacto? = null
        c.use {
            while (it.moveToNext()) {
                val nom = it.getString(0) ?: continue
                val num = it.getString(1) ?: continue
                val n = Frase.normalizar(nom)
                when {
                    n == obj -> if (exacto == null || it.getInt(2) == 1) exacto = Contacto(nom, num)
                    empieza == null && (n.startsWith("$obj ") || n.startsWith(obj)) -> empieza = Contacto(nom, num)
                    contiene == null && n.length >= 3 && (n.contains(obj) || obj.contains(n)) -> contiene = Contacto(nom, num)
                }
            }
        }
        return if (estricto) exacto ?: empieza?.takeIf { Frase.normalizar(it.nombre).startsWith("$obj ") || Frase.normalizar(it.nombre) == obj } else exacto ?: empieza ?: contiene
    }

    /** Número en formato internacional para WhatsApp (Perú por defecto). */
    fun internacional(numero: String): String {
        val mas = numero.trim().startsWith("+")
        var d = numero.filter { it.isDigit() }
        if (mas) return d
        if (d.startsWith("00")) return d.drop(2)
        if (d.startsWith("0")) d = d.drop(1)
        if (d.length == 9 && d.startsWith("9")) return "51$d"
        return d
    }
}

/** Calendario del celular. */
object Agenda {
    private val es = Locale.forLanguageTag("es-PE")

    fun crear(ctx: Context, titulo: String, inicio: Calendar, minutos: Int = 60): Boolean {
        if (!ctx.tiene(Manifest.permission.WRITE_CALENDAR) || !ctx.tiene(Manifest.permission.READ_CALENDAR)) return false
        val id = calendarioPrincipal(ctx) ?: return false
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, id)
            put(CalendarContract.Events.TITLE, titulo)
            put(CalendarContract.Events.DTSTART, inicio.timeInMillis)
            put(CalendarContract.Events.DTEND, inicio.timeInMillis + minutos * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return false
        val evento = uri.lastPathSegment?.toLongOrNull() ?: return true
        ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, evento)
            put(CalendarContract.Reminders.MINUTES, 15)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
        return true
    }

    private fun calendarioPrincipal(ctx: Context): Long? {
        val c = ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "${CalendarContract.Calendars.VISIBLE} = 1", null, null
        ) ?: return null
        var elegido: Long? = null
        c.use {
            while (it.moveToNext()) {
                if (it.getInt(2) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                if (it.getInt(1) == 1) return it.getLong(0)
                if (elegido == null) elegido = it.getLong(0)
            }
        }
        return elegido
    }

    /** Eventos de un día (0 = hoy, 1 = mañana…). Devuelve null si no hay permiso. */
    fun eventos(ctx: Context, dia: Int): List<String>? {
        if (!ctx.tiene(Manifest.permission.READ_CALENDAR)) return null
        val ini = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dia); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        val fin = (ini.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(ini.timeInMillis.toString()).appendPath(fin.timeInMillis.toString()).build()
        val lista = mutableListOf<String>()
        ctx.contentResolver.query(
            uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
            null, null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use {
            while (it.moveToNext()) {
                val titulo = it.getString(0) ?: continue
                lista.add(if (it.getInt(2) == 1) "$titulo, todo el día" else "$titulo a las " + SimpleDateFormat("h:mm a", es).format(Date(it.getLong(1))))
            }
        }
        return lista
    }
}

/** Recordatorios con aviso a la hora exacta, por voz y notificación. */
object Recordatorios {
    data class Item(val id: Int, val cuando: Long, val texto: String)

    fun lista(): List<Item> {
        val arr = try { JSONArray(Prefs.recordatorios) } catch (_: Exception) { JSONArray() }
        val r = mutableListOf<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            r.add(Item(o.getInt("id"), o.getLong("cuando"), o.getString("texto")))
        }
        return r.sortedBy { it.cuando }
    }

    private fun guardar(items: List<Item>) {
        val arr = JSONArray()
        for (i in items) arr.put(JSONObject().put("id", i.id).put("cuando", i.cuando).put("texto", i.texto))
        Prefs.recordatorios = arr.toString()
    }

    fun crear(ctx: Context, cuando: Long, texto: String): Item {
        val id = Prefs.contadorIds + 1
        Prefs.contadorIds = id
        val item = Item(id, cuando, texto)
        guardar(lista() + item)
        programar(ctx, item)
        return item
    }

    fun quitar(ctx: Context, id: Int) {
        guardar(lista().filter { it.id != id })
        alarmas(ctx).cancel(intento(ctx, Item(id, 0, "")))
    }

    fun borrarTodos(ctx: Context) {
        for (i in lista()) alarmas(ctx).cancel(intento(ctx, i))
        guardar(emptyList())
    }

    fun reprogramarTodos(ctx: Context) {
        val ahora = System.currentTimeMillis()
        for (i in lista()) {
            if (i.cuando > ahora) programar(ctx, i) else ctx.sendBroadcast(Intent(ctx, ReceptorRecordatorio::class.java).putExtra("id", i.id).putExtra("texto", i.texto))
        }
    }

    private fun alarmas(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun intento(ctx: Context, i: Item): PendingIntent = PendingIntent.getBroadcast(
        ctx, i.id,
        Intent(ctx, ReceptorRecordatorio::class.java).putExtra("id", i.id).putExtra("texto", i.texto),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun programar(ctx: Context, i: Item) {
        val am = alarmas(ctx)
        val pi = intento(ctx, i)
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, i.cuando, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, i.cuando, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, i.cuando, pi)
        }
    }
}

class ReceptorRecordatorio : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val texto = intent.getStringExtra("texto").orEmpty()
        Recordatorios.quitar(ctx, id)
        Avisos.recordatorio(ctx, id, texto)
        Yarvis.anunciar("${Prefs.trato.replaceFirstChar { it.uppercase() }}, le recuerdo: $texto.")
    }
}

/** Notificaciones de YARVIS. */
object Avisos {
    const val CANAL_ESCUCHA = "escucha"
    const val CANAL_RECORDATORIOS = "recordatorios"
    const val CANAL_SISTEMA = "sistema"

    fun crearCanales(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL_ESCUCHA, "Escucha «Yarvis»", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Aviso fijo mientras YARVIS espera su palabra de activación"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CANAL_RECORDATORIOS, "Recordatorios", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Recordatorios que le pidió a YARVIS"
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CANAL_SISTEMA, "Avisos de YARVIS", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun abrirApp(ctx: Context, req: Int, escuchar: Boolean = false): PendingIntent = PendingIntent.getActivity(
        ctx, req,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("escuchar", escuchar),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun recordatorio(ctx: Context, id: Int, texto: String) {
        if (!ctx.tiene(Manifest.permission.POST_NOTIFICATIONS)) return
        val n = Notification.Builder(ctx, CANAL_RECORDATORIOS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Recordatorio")
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(abrirApp(ctx, 50))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun pedirActivacion(ctx: Context) {
        if (!ctx.tiene(Manifest.permission.POST_NOTIFICATIONS)) return
        val n = Notification.Builder(ctx, CANAL_SISTEMA)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("YARVIS está listo")
            .setContentText("Toque aquí para activar la escucha de «${Prefs.palabraActivacion}».")
            .setContentIntent(abrirApp(ctx, 51))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(9, n)
    }
}
