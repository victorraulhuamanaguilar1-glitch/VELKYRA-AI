package com.velkyra.yarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Memoria personal: «recuerda que dejé las llaves en el cajón» → «¿dónde dejé las llaves?».
 * También placas, claves del wifi, tallas… Todo se queda en el celular.
 */
object Memoria {

    data class Dato(val texto: String, val hora: Long)

    private fun sp() = Yarvis.ctx.getSharedPreferences("yarvis", Context.MODE_PRIVATE)

    fun lista(): List<Dato> = try {
        val a = JSONArray(sp().getString("memoria", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Dato(o.getString("t"), o.optLong("h")) }
    } catch (_: Exception) { emptyList() }

    private fun guardar(l: List<Dato>) {
        val a = JSONArray()
        for (d in l.takeLast(300)) a.put(JSONObject().put("t", d.texto).put("h", d.hora))
        sp().edit().putString("memoria", a.toString()).apply()
    }

    private val vacias = setOf(
        "el", "la", "los", "las", "un", "una", "de", "del", "en", "y", "que", "mi", "mis", "tu", "su", "a", "al", "por", "para",
        "con", "es", "esta", "estan", "deje", "puse", "guarde", "donde", "cual", "cuales", "como", "se", "lo", "le", "me", "te"
    )

    private fun claves(s: String) = Frase.normalizar(s).split(" ").filter { it.length > 2 && it !in vacias }
        .map { it.trimEnd('s') }.toSet()

    /** Guarda un dato. Si ya había uno sobre lo mismo (p. ej. dónde están las llaves), lo reemplaza. */
    fun recordar(texto: String) {
        val nuevo = claves(texto)
        val l = lista().filterNot { d ->
            val k = claves(d.texto)
            // Mismo tema: comparten el sustantivo principal (llaves, placa, clave del wifi…)
            val comunes = k intersect nuevo
            comunes.isNotEmpty() && comunes.size >= minOf(2, minOf(k.size, nuevo.size)) &&
                Regex("(deje|puse|guarde|esta|estan|es|son)").containsMatchIn(Frase.normalizar(d.texto))
        }
        guardar(l + Dato(texto.trim().trimEnd('.'), System.currentTimeMillis()))
    }

    /** Datos que se parecen a la pregunta, del más parecido al menos. */
    fun buscar(pregunta: String): List<Dato> {
        val q = claves(pregunta)
        if (q.isEmpty()) return emptyList()
        return lista().map { it to (claves(it.texto) intersect q).size }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Dato, Int>> { it.second }.thenByDescending { it.first.hora }).map { it.first }
    }

    fun olvidar(sobre: String): Int {
        val q = claves(sobre)
        val l = lista()
        val quedan = l.filter { (claves(it.texto) intersect q).isEmpty() }
        guardar(quedan)
        return l.size - quedan.size
    }

    fun borrarTodo() = guardar(emptyList())

    /** Para que la IA también pueda usarlos («¿cuál es mi placa?»). */
    fun paraIA(): String {
        val l = lista()
        if (l.isEmpty()) return ""
        return "Datos que el usuario te pidió recordar (úsalos si te pregunta por ellos): " +
            l.takeLast(40).joinToString("; ") { it.texto }.take(2500) + "."
    }

    /** Convierte «dejé las llaves» (dicho por el usuario) en «usted dejó las llaves». */
    fun enSegunda(t: String): String {
        val tr = if (Prefs.modoConfianza) mapOf("deje" to "dejaste", "puse" to "pusiste", "guarde" to "guardaste", "mi" to "tu", "mis" to "tus", "estacione" to "estacionaste", "tengo" to "tienes", "soy" to "eres")
        else mapOf("deje" to "dejó", "puse" to "puso", "guarde" to "guardó", "mi" to "su", "mis" to "sus", "estacione" to "estacionó", "tengo" to "tiene", "soy" to "es")
        return t.split(" ").joinToString(" ") { w -> tr[Frase.normalizar(w)] ?: w }
    }
}
