package com.velkyra.yarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bandeja de mensajes recibidos (WhatsApp, Messenger, SMS, Telegram…), guardada en el celular
 * durante 3 días, para «¿qué me escribió Juan?» y «resúmeme los mensajes de hoy».
 */
object Bandeja {

    data class Msj(val app: String, val quien: String, val texto: String, val hora: Long)

    private const val DIAS = 3
    private val lista = ArrayList<Msj>()
    private var cargada = false

    private fun sp() = Yarvis.ctx.getSharedPreferences("bandeja", Context.MODE_PRIVATE)

    private fun cargar() {
        if (cargada) return
        cargada = true
        try {
            val a = JSONArray(sp().getString("m", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                lista.add(Msj(o.optString("a"), o.optString("q"), o.optString("t"), o.optLong("h")))
            }
        } catch (_: Exception) {}
    }

    private fun guardar() {
        val limite = System.currentTimeMillis() - DIAS * 86_400_000L
        lista.removeAll { it.hora < limite }
        while (lista.size > 400) lista.removeAt(0)
        val a = JSONArray()
        for (m in lista) a.put(JSONObject().put("a", m.app).put("q", m.quien).put("t", m.texto).put("h", m.hora))
        sp().edit().putString("m", a.toString()).apply()
    }

    fun agregar(app: String, quien: String, texto: String) = synchronized(lista) {
        cargar()
        // WhatsApp a veces repite el mismo mensaje: no duplicar
        if (lista.takeLast(30).any { it.quien == quien && it.texto == texto && System.currentTimeMillis() - it.hora < 600_000 }) return@synchronized
        lista.add(Msj(app, quien, texto, System.currentTimeMillis()))
        guardar()
    }

    fun todos(): List<Msj> = synchronized(lista) { cargar(); lista.toList() }

    /** Mensajes de una persona (por nombre aproximado) en las últimas [horas]. */
    fun de(quien: String, horas: Int = 48): List<Msj> {
        val q = Frase.normalizar(quien).replace(Regex("^(a |al |la |el |mi )"), "").trim()
        if (q.isBlank()) return emptyList()
        val desde = System.currentTimeMillis() - horas * 3_600_000L
        return todos().filter { it.hora >= desde }.filter {
            val n = Frase.normalizar(it.quien)
            n == q || n.startsWith("$q ") || n.contains(q) || (n.length >= 3 && q.contains(n))
        }
    }

    /** Mensajes desde la medianoche de hoy. */
    fun deHoy(): List<Msj> {
        val c = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
        }
        return todos().filter { it.hora >= c.timeInMillis }
    }

    fun borrar() = synchronized(lista) { cargar(); lista.clear(); guardar() }

    fun hora(m: Msj): String = java.text.SimpleDateFormat("h:mm a", java.util.Locale.forLanguageTag("es-PE")).format(java.util.Date(m.hora))

    /** Texto compacto para dárselo a la IA. */
    fun comoTexto(l: List<Msj>): String = l.joinToString("\n") { "[${hora(it)}] ${it.quien} (${it.app}): ${it.texto}" }.takeLast(9000)

    /** Agrupa por persona: «Juan (3), María (1)». */
    fun conteo(l: List<Msj>): String = l.groupBy { it.quien }.entries.sortedByDescending { it.value.size }
        .joinToString(", ") { (q, v) -> if (v.size == 1) "uno de $q" else "${v.size} de $q" }
}
