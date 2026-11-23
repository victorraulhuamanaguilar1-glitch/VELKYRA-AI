package com.velkyra.yarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** Gastos e ingresos anotados por voz («gasté 20 soles en pasajes», «¿cuánto gasté esta semana?»). */
object Gastos {

    data class Mov(val ingreso: Boolean, val monto: Double, val concepto: String, val hora: Long)

    private fun sp() = Yarvis.ctx.getSharedPreferences("yarvis", Context.MODE_PRIVATE)

    fun lista(): List<Mov> = try {
        val a = JSONArray(sp().getString("gastos", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Mov(o.optBoolean("i"), o.optDouble("m"), o.optString("c"), o.optLong("h"))
        }
    } catch (_: Exception) { emptyList() }

    private fun guardar(l: List<Mov>) {
        val a = JSONArray()
        for (m in l.takeLast(3000)) a.put(JSONObject().put("i", m.ingreso).put("m", m.monto).put("c", m.concepto).put("h", m.hora))
        sp().edit().putString("gastos", a.toString()).apply()
    }

    fun agregar(m: Mov) = guardar(lista() + m)

    fun quitarUltimo(): Mov? {
        val l = lista()
        if (l.isEmpty()) return null
        guardar(l.dropLast(1))
        return l.last()
    }

    fun soles(x: Double): String {
        val entero = x.toLong()
        val cent = Math.round((x - entero) * 100).toInt()
        val base = if (entero == 1L) "un sol" else "${String.format(Locale.US, "%,d", entero).replace(',', ' ')} soles"
        return if (cent > 0) "$base con $cent céntimos" else base
    }

    // --- Entender montos ----------------------------------------------------------

    private val palabras = mapOf(
        "un" to 1, "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7,
        "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14, "quince" to 15,
        "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18, "diecinueve" to 19, "veinte" to 20, "veinticinco" to 25,
        "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60, "setenta" to 70, "ochenta" to 80,
        "noventa" to 90, "cien" to 100, "ciento" to 100, "doscientos" to 200, "trescientos" to 300, "quinientos" to 500, "mil" to 1000
    )

    private const val NUM = "(\\d+(?:[.,]\\d{1,2})?|un|uno|una|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|trece|catorce|quince|dieciseis|diecisiete|dieciocho|diecinueve|veinte|veinticinco|treinta|cuarenta|cincuenta|sesenta|setenta|ochenta|noventa|cien|ciento|doscientos|trescientos|quinientos|mil)"
    private const val MONEDA = "(?:\\s*(?:soles?|sol|lucas?|luca|nuevos soles|s/\\.?|dolares?|pesos))?"

    private fun valor(s: String): Double = s.replace(',', '.').toDoubleOrNull() ?: (palabras[s] ?: 0).toDouble()

    private val rGasto = Regex("^(?:anota(?:me)? que |apunta que |registra que |anota un gasto de |registra un gasto de |gasto de |un gasto de )?(?:me )?(?:gaste|he gastado|pague|he pagado|compre|di|invert[ií])\\s+(?:s/\\.?\\s*)?$NUM$MONEDA(?:\\s+con\\s+(\\d{1,2})(?:\\s+centimos?)?)?(?:\\s+(?:en|de|por|para|del|de la|en el|en la)\\s+(.+))?$")
    private val rGasto2 = Regex("^(?:anota(?:me)? |apunta |registra )(?:un )?gasto(?: de)?\\s+(?:s/\\.?\\s*)?$NUM$MONEDA(?:\\s+con\\s+(\\d{1,2}))?(?:\\s+(?:en|de|por|para)\\s+(.+))?$")
    private val rGasto3 = Regex("^(?:anota que |apunta que )?(?:compre|pague)\\s+(.+?)\\s+(?:a|en|por)\\s+(?:s/\\.?\\s*)?$NUM$MONEDA(?:\\s+con\\s+(\\d{1,2}))?$")
    private val rIngreso = Regex("^(?:anota que |apunta que |registra que )?(?:me pagaron|recibi|he recibido|cobre|gane|me depositaron|me dieron|me yapearon|me plinearon|vendi)\\s+(?:s/\\.?\\s*)?$NUM$MONEDA(?:\\s+con\\s+(\\d{1,2}))?(?:\\s+(?:de|por|en|del)\\s+(.+))?$")

    /** Intenta registrar un gasto o ingreso dicho por el usuario. */
    fun registrar(f: Frase): Mov? {
        val t = f.t
        rGasto.find(t)?.let { m -> return nuevo(false, m.groupValues[1], m.groupValues[2], m.groups[3]?.let { f.originalDeGrupo(m, 3) }) }
        rGasto2.find(t)?.let { m -> return nuevo(false, m.groupValues[1], m.groupValues[2], m.groups[3]?.let { f.originalDeGrupo(m, 3) }) }
        rGasto3.find(t)?.let { m -> return nuevo(false, m.groupValues[2], m.groupValues[3], f.originalDeGrupo(m, 1)) }
        rIngreso.find(t)?.let { m -> return nuevo(true, m.groupValues[1], m.groupValues[2], m.groups[3]?.let { f.originalDeGrupo(m, 3) }) }
        return null
    }

    private fun nuevo(ingreso: Boolean, n: String, cent: String, concepto: String?): Mov? {
        val monto = valor(n) + (cent.toIntOrNull() ?: 0) / 100.0
        if (monto <= 0) return null
        val c = concepto?.trim()?.trimEnd('.')?.replace(Regex("(?i)^(un|una|el|la|los|las|unos|unas)\\s+"), "")
            ?.replaceFirstChar { it.uppercase() }.orEmpty().ifBlank { if (ingreso) "Ingreso" else "Varios" }
        val m = Mov(ingreso, monto, c, System.currentTimeMillis())
        agregar(m)
        return m
    }

    // --- Consultas ------------------------------------------------------------------

    /** Inicio del periodo dicho: hoy, ayer, semana, mes, año. Devuelve (desde, hasta, nombre). */
    fun periodo(t: String): Triple<Long, Long, String> {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val fin = System.currentTimeMillis() + 1
        return when {
            Regex("\\bayer\\b").containsMatchIn(t) -> { val h = c.timeInMillis; c.add(Calendar.DAY_OF_MONTH, -1); Triple(c.timeInMillis, h, "ayer") }
            Regex("(semana pasada)").containsMatchIn(t) -> {
                c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY); if (c.timeInMillis > fin) c.add(Calendar.DAY_OF_MONTH, -7)
                val h = c.timeInMillis; c.add(Calendar.DAY_OF_MONTH, -7); Triple(c.timeInMillis, h, "la semana pasada")
            }
            Regex("(semana)").containsMatchIn(t) -> {
                c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY); if (c.timeInMillis > fin) c.add(Calendar.DAY_OF_MONTH, -7)
                Triple(c.timeInMillis, fin, "esta semana")
            }
            Regex("(mes pasado)").containsMatchIn(t) -> {
                c.set(Calendar.DAY_OF_MONTH, 1); val h = c.timeInMillis; c.add(Calendar.MONTH, -1); Triple(c.timeInMillis, h, "el mes pasado")
            }
            Regex("(\\bmes\\b)").containsMatchIn(t) -> { c.set(Calendar.DAY_OF_MONTH, 1); Triple(c.timeInMillis, fin, "este mes") }
            Regex("(\\bano\\b)").containsMatchIn(t) -> { c.set(Calendar.DAY_OF_YEAR, 1); Triple(c.timeInMillis, fin, "este año") }
            Regex("(total|siempre|todo)").containsMatchIn(t) -> Triple(0L, fin, "en total")
            else -> Triple(c.timeInMillis, fin, "hoy")
        }
    }

    fun entre(desde: Long, hasta: Long) = lista().filter { it.hora in desde until hasta }

    /** Resumen hablado de un periodo, opcionalmente solo de un concepto («pasajes»). */
    fun informe(t: String, concepto: String?): String {
        val tr = Prefs.trato
        val (d, h, nombre) = periodo(t)
        var l = entre(d, h)
        if (!concepto.isNullOrBlank()) {
            val q = Frase.normalizar(concepto)
            l = l.filter { Frase.normalizar(it.concepto).let { c -> c.contains(q) || q.contains(c) } }
        }
        val g = l.filter { !it.ingreso }
        val i = l.filter { it.ingreso }
        if (g.isEmpty() && i.isEmpty()) return "No tiene gastos anotados ${if (concepto.isNullOrBlank()) "" else "en $concepto "}$nombre, $tr."
        val sb = StringBuilder()
        if (g.isNotEmpty()) {
            sb.append("${nombre.replaceFirstChar { it.uppercase() }} gastó ${soles(g.sumOf { it.monto })}")
            if (concepto.isNullOrBlank()) {
                val partes = g.groupBy { it.concepto }.mapValues { e -> e.value.sumOf { it.monto } }.entries.sortedByDescending { it.value }.take(5)
                if (partes.size > 1 || g.size > 1) sb.append(": ").append(partes.joinToString(", ") { "${soles(it.value)} en ${it.key.lowercase()}" })
            }
            sb.append(". ")
        }
        if (i.isNotEmpty()) {
            sb.append("Ingresos: ${soles(i.sumOf { it.monto })}. ")
            if (g.isNotEmpty()) {
                val bal = i.sumOf { it.monto } - g.sumOf { it.monto }
                sb.append(if (bal >= 0) "Le quedan ${soles(bal)} a favor. " else "Gastó ${soles(-bal)} más de lo que recibió. ")
            }
        }
        return sb.toString().trim()
    }
}
