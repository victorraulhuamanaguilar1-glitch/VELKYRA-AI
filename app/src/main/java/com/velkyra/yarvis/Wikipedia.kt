package com.velkyra.yarvis

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Respaldo de conocimiento gratuito y sin límite diario: Wikipedia en español. */
object Wikipedia {

    private const val AGENTE = "YARVIS-Android/2.0 (VELKYRA; velkyra3@gmail.com)"

    private fun leer(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", AGENTE)
        c.connectTimeout = 12000
        c.readTimeout = 15000
        try {
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    /** Quita las palabras de pregunta para buscar mejor ("¿quién fue Túpac Amaru?" → "Túpac Amaru"). */
    fun limpiar(pregunta: String): String {
        var q = pregunta.trim().replace(Regex("[¿?¡!]"), "")
        q = q.replace(Regex("(?i)^(yarvis[, ]+)?(oye[, ]+)?(dime|cuentame|cuéntame|explicame|explícame|hablame|háblame|sabes|me puedes decir|puedes decirme|quisiera saber)?\\s*(de |sobre |acerca de |algo de |algo sobre )?"), "")
        q = q.replace(Regex("(?i)^(quien|quién|quienes|quiénes|que|qué|cual|cuál|cuales|cuáles|cuando|cuándo|donde|dónde|como|cómo|por que|por qué|cuanto|cuánto|cuantos|cuántos)\\s+(fue|fueron|es|son|era|eran|esta|está|queda|paso|pasó|sucedio|sucedió|ocurrio|ocurrió|significa|nacio|nació)?\\s*"), "")
        q = q.replace(Regex("(?i)^(en|de|del|con)\\s+"), "")
        q = q.replace(Regex("(?i)^(el|la|los|las|un|una)\\s+"), "")
        return q.trim().ifBlank { pregunta.trim() }
    }

    /** Devuelve un resumen corto (2–3 oraciones) o null si no encuentra nada. */
    fun consultar(pregunta: String): String? = try {
        val q = limpiar(pregunta)
        val busqueda = leer("https://es.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=1&srsearch=" + URLEncoder.encode(q, "UTF-8"))
        val titulo = busqueda.getJSONObject("query").getJSONArray("search").optJSONObject(0)?.optString("title")
        if (titulo.isNullOrBlank()) null else {
            val res = leer("https://es.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(titulo.replace(' ', '_'), "UTF-8").replace("+", "%20"))
            val extracto = res.optString("extract").replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim()
            if (extracto.isBlank()) null else resumir(extracto, 3)
        }
    } catch (_: Exception) {
        null
    }

    private fun resumir(texto: String, oraciones: Int): String {
        val partes = Regex("(?<=[.!?])\\s+(?=[A-ZÁÉÍÓÚÑ0-9])").split(texto)
        return partes.take(oraciones).joinToString(" ").take(700)
    }
}
