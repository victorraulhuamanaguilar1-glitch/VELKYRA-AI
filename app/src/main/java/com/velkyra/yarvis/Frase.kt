package com.velkyra.yarvis

import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/**
 * Una frase dicha por el usuario. Guarda la versión normalizada (sin tildes, minúsculas)
 * para entenderla, y la original para repetir textos con sus tildes (mensajes, notas…).
 */
class Frase(val original: String) {

    private val tokensOriginales: List<String> =
        original.replace(Regex("[¿?¡!,;\"“”]"), " ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    private val tokensNorm: List<String> = tokensOriginales.map { normalizar(it) }
    private val inicios: IntArray

    /** Texto normalizado: "recuérdame mañana…" → "recuerdame manana…" */
    val t: String

    init {
        val sb = StringBuilder()
        inicios = IntArray(tokensNorm.size)
        tokensNorm.forEachIndexed { i, tok ->
            if (i > 0) sb.append(' ')
            inicios[i] = sb.length
            sb.append(tok)
        }
        t = sb.toString().trimEnd('.')
    }

    private fun tokenEn(pos: Int): Int {
        var idx = 0
        for (i in inicios.indices) if (inicios[i] <= pos) idx = i
        return idx
    }

    /** Devuelve el texto ORIGINAL (con tildes) que corresponde a un rango del texto normalizado. */
    fun originalEntre(desde: Int, hasta: Int = t.length): String {
        if (tokensOriginales.isEmpty() || desde >= t.length) return ""
        val a = tokenEn(desde)
        val b = tokenEn((hasta - 1).coerceAtLeast(desde))
        return tokensOriginales.subList(a, b + 1).joinToString(" ").trim().trimEnd('.')
    }

    fun originalDeGrupo(m: MatchResult, grupo: Int): String {
        val r = m.groups[grupo]?.range ?: return ""
        return originalEntre(r.first, r.last + 1)
    }

    /** Texto original sin los tokens que caen dentro de los rangos indicados. */
    fun originalSin(rangos: List<IntRange>): String {
        val quitar = HashSet<Int>()
        for (r in rangos) {
            if (r.isEmpty()) continue
            for (i in tokenEn(r.first)..tokenEn(r.last)) quitar.add(i)
        }
        return tokensOriginales.filterIndexed { i, _ -> i !in quitar }.joinToString(" ")
    }

    fun tiene(regex: String) = Regex(regex).containsMatchIn(t)

    companion object {
        fun normalizar(s: String): String {
            val sinTildes = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            return sinTildes.replace(Regex("[¿?¡!,;\"“”]"), " ").replace(Regex("\\s+"), " ").trim()
        }
    }
}

/** Entiende fechas y horas dichas en español: "mañana a las 8", "en 20 minutos", "el viernes a las 3 de la tarde"… */
object Tiempo {

    data class Momento(val cal: Calendar, val conHora: Boolean, val conDia: Boolean, val rangos: List<IntRange>)

    private val numeros = mapOf(
        "un" to 1, "una" to 1, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
        "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12,
        "quince" to 15, "veinte" to 20, "treinta" to 30, "cuarenta" to 40, "cuarenta y cinco" to 45
    )
    private val dias = listOf("domingo", "lunes", "martes", "miercoles", "jueves", "viernes", "sabado")
    private val meses = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
        "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    )
    private const val NUM = "(\\d{1,3}|un|una|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|quince|veinte|treinta|cuarenta y cinco|cuarenta|media)"

    fun numero(s: String): Double = s.toDoubleOrNull() ?: if (s == "media") 0.5 else (numeros[s] ?: 1).toDouble()

    /** Duración: "5 minutos", "media hora", "una hora y 20 minutos" → segundos. */
    fun duracion(t: String): Pair<Int, IntRange>? {
        val r = Regex("$NUM\\s*(segundo|minuto|hora)s?(?:\\s+y\\s+$NUM\\s*(minuto|segundo)s?)?(?:\\s+y\\s+media)?")
        val m = r.find(t) ?: return null
        var seg = segundos(numero(m.groupValues[1]), m.groupValues[2])
        if (m.groupValues[3].isNotEmpty()) seg += segundos(numero(m.groupValues[3]), m.groupValues[4])
        if (m.value.endsWith("y media")) seg += if (m.groupValues[2] == "hora") 1800 else 30
        return seg.toInt() to m.range
    }

    private fun segundos(n: Double, unidad: String) = when (unidad) {
        "segundo" -> n
        "minuto" -> n * 60
        else -> n * 3600
    }

    fun analizar(t: String, ahora: Calendar = Calendar.getInstance()): Momento? {
        val rangos = mutableListOf<IntRange>()
        val cal = ahora.clone() as Calendar
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // "en 20 minutos", "dentro de una hora"
        Regex("(?:en|dentro de)\\s+$NUM\\s*(segundo|minuto|hora)s?(?:\\s+y\\s+media)?").find(t)?.let { m ->
            var s = segundos(numero(m.groupValues[1]), m.groupValues[2])
            if (m.value.endsWith("y media")) s += if (m.groupValues[2] == "hora") 1800 else 30
            val c = ahora.clone() as Calendar
            c.add(Calendar.SECOND, s.toInt())
            return Momento(c, true, true, listOf(m.range))
        }

        var conDia = false
        var diaFijado = false

        // Parte del día ("de la mañana") se quita antes de buscar "mañana" como día
        var parte = ""
        Regex("(de la manana|en la manana|de la tarde|en la tarde|por la tarde|de la noche|en la noche|por la noche|de la madrugada|\\b[ap]\\.?\\s?m\\b\\.?)").find(t)?.let {
            parte = it.value
            rangos.add(it.range)
        }
        val sinParte = if (parte.isEmpty()) t else t.replaceRange(rangos.last(), " ".repeat(rangos.last().count()))

        Regex("\\bpasado manana\\b").find(sinParte)?.let {
            cal.add(Calendar.DAY_OF_YEAR, 2); conDia = true; diaFijado = true; rangos.add(it.range)
        }
        if (!diaFijado) Regex("\\bmanana\\b").find(sinParte)?.let {
            cal.add(Calendar.DAY_OF_YEAR, 1); conDia = true; diaFijado = true; rangos.add(it.range)
        }
        if (!diaFijado) Regex("\\b(hoy|esta noche|esta tarde)\\b").find(sinParte)?.let {
            conDia = true; diaFijado = true; rangos.add(it.range)
            if (it.value != "hoy" && parte.isEmpty()) parte = if (it.value.contains("noche")) "noche" else "tarde"
        }
        if (!diaFijado) Regex("(?:el|este|el proximo|este proximo|para el)?\\s*\\b(domingo|lunes|martes|miercoles|jueves|viernes|sabado)\\b").find(sinParte)?.let {
            val objetivo = dias.indexOf(it.groupValues[1]) + 1
            var diff = objetivo - cal.get(Calendar.DAY_OF_WEEK)
            if (diff <= 0) diff += 7
            cal.add(Calendar.DAY_OF_YEAR, diff)
            conDia = true; diaFijado = true; rangos.add(it.range)
        }
        if (!diaFijado) Regex("(?:el\\s+)?(\\d{1,2})\\s+de\\s+(${meses.joinToString("|")})").find(sinParte)?.let {
            val d = it.groupValues[1].toInt()
            val mes = meses.indexOf(it.groupValues[2])
            cal.set(Calendar.MONTH, mes)
            cal.set(Calendar.DAY_OF_MONTH, d.coerceIn(1, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
            if (cal.before(ahora)) cal.add(Calendar.YEAR, 1)
            conDia = true; diaFijado = true; rangos.add(it.range)
        }

        // Hora
        var hora = -1
        var minuto = 0
        var horaDicha = false
        val rHora = Regex("(?:a las|a la|para las|las|a eso de las)\\s+(\\d{1,2}|una|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce)(?:[:.](\\d{2}))?(?:\\s+(?:y|con)\\s+(media|cuarto|\\d{1,2}))?(?:\\s+menos\\s+(cuarto|\\d{1,2}))?(?:\\s+en punto)?")
        val mh = rHora.find(sinParte) ?: Regex("\\b(\\d{1,2}):(\\d{2})\\b").find(sinParte)
        if (mh != null) {
            hora = numero(mh.groupValues[1]).toInt()
            minuto = mh.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val y = mh.groupValues.getOrNull(3).orEmpty()
            if (y == "media") minuto = 30 else if (y == "cuarto") minuto = 15 else y.toIntOrNull()?.let { minuto = it }
            val menos = mh.groupValues.getOrNull(4).orEmpty()
            if (menos.isNotEmpty()) {
                val q = if (menos == "cuarto") 15 else menos.toIntOrNull() ?: 0
                hora -= 1; minuto = 60 - q
            }
            rangos.add(mh.range)
            horaDicha = true
        } else {
            Regex("\\b(al mediodia|a mediodia|a medianoche|a la medianoche)\\b").find(sinParte)?.let {
                hora = if (it.value.contains("noche")) 0 else 12
                rangos.add(it.range)
                horaDicha = true
            }
        }

        if (hora < 0 && !conDia) return null
        if (hora < 0) {
            hora = 9 // si solo dijo el día, se avisa a las 9 a. m.
        } else {
            val p = parte
            when {
                p.contains("tarde") || p.contains("noche") || p.startsWith("p") -> if (hora < 12) hora += 12
                p.contains("manana") || p.contains("madrugada") || p.startsWith("a") -> if (hora == 12) hora = 0
            }
        }
        if (hora !in 0..23 || minuto !in 0..59) return null

        cal.set(Calendar.HOUR_OF_DAY, hora)
        cal.set(Calendar.MINUTE, minuto)

        // Sin "de la tarde/mañana": se elige la próxima vez que llegue esa hora
        if (parte.isEmpty() && mh != null && hora in 1..11) {
            if (!conDia) {
                if (!cal.after(ahora)) {
                    cal.add(Calendar.HOUR_OF_DAY, 12)
                    if (!cal.after(ahora)) cal.add(Calendar.HOUR_OF_DAY, 12)
                }
            } else if (hora <= 6) {
                cal.add(Calendar.HOUR_OF_DAY, 12) // "mañana a las 3" → 3 de la tarde
            }
        } else if (!conDia && !cal.after(ahora)) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return Momento(cal, horaDicha, conDia, rangos)
    }

    fun describir(cal: Calendar): String {
        val hoy = Calendar.getInstance()
        val es = Locale.forLanguageTag("es-PE")
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val h12 = if (h % 12 == 0) 12 else h % 12
        val parte = when {
            h < 6 -> "de la madrugada"
            h < 12 -> "de la mañana"
            h < 19 -> "de la tarde"
            else -> "de la noche"
        }
        val horaTxt = (if (h12 == 1) "a la 1" else "a las $h12") + (if (m > 0) String.format(es, ":%02d", m) else "") + " $parte"
        val diasDif = diasEntre(hoy, cal)
        val diaTxt = when (diasDif) {
            0 -> "hoy"
            1 -> "mañana"
            2 -> "pasado mañana"
            in 3..6 -> "el " + java.text.SimpleDateFormat("EEEE", es).format(cal.time)
            else -> "el " + java.text.SimpleDateFormat("EEEE d 'de' MMMM", es).format(cal.time)
        }
        return "$diaTxt $horaTxt"
    }

    fun diasEntre(a: Calendar, b: Calendar): Int {
        val x = a.clone() as Calendar
        val y = b.clone() as Calendar
        for (c in listOf(x, y)) {
            c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        }
        return Math.round((y.timeInMillis - x.timeInMillis) / 86_400_000.0).toInt()
    }
}
