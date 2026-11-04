package com.velkyra.asistente

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Conexión con la inteligencia artificial de Google (Gemini).
 * Se usa para las preguntas libres que no son comandos del celular.
 * La clave gratuita se obtiene en https://aistudio.google.com
 */
object CerebroIA {

    class ErrorIA(val codigo: Int, mensaje: String) : Exception(mensaje)

    fun preguntar(
        clave: String,
        modelo: String,
        nombreUsuario: String,
        historial: List<Pair<String, String>>,
        pregunta: String
    ): String {
        val ahora = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, h:mm a", Locale.forLanguageTag("es-PE"))
            .format(Date())

        val instrucciones = "Eres VELKYRA, el asistente virtual personal de $nombreUsuario. " +
            "Tu personalidad es elegante, leal, segura e ingeniosa, con un toque de humor seco, " +
            "como un mayordomo de inteligencia artificial futurista. Trata al usuario por su nombre de vez en cuando. " +
            "Responde siempre en español y de forma breve (máximo tres oraciones, salvo que te pida más detalle), " +
            "porque tus respuestas se leen en voz alta: no uses listas, viñetas, emojis, markdown ni símbolos especiales. " +
            "Fecha y hora actual: $ahora."

        val contenidos = JSONArray()
        for ((rol, texto) in historial) {
            contenidos.put(mensaje(rol, texto))
        }
        contenidos.put(mensaje("user", pregunta))

        val cuerpo = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instrucciones))))
            .put("contents", contenidos)

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent")
        val con = url.openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15000
            con.readTimeout = 40000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            con.setRequestProperty("x-goog-api-key", clave)
            con.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }

            val codigo = con.responseCode
            val flujo = if (codigo in 200..299) con.inputStream else con.errorStream
            val respuesta = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (codigo !in 200..299) throw ErrorIA(codigo, respuesta.take(300))

            val json = JSONObject(respuesta)
            val candidatos = json.optJSONArray("candidates")
            if (candidatos == null || candidatos.length() == 0) {
                return "Prefiero no responder a eso. ¿Te ayudo con otra cosa?"
            }
            val partes = candidatos.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: return "No obtuve una respuesta clara. Inténtalo de nuevo."
            val sb = StringBuilder()
            for (i in 0 until partes.length()) {
                sb.append(partes.getJSONObject(i).optString("text"))
            }
            return sb.toString().trim().ifEmpty { "No obtuve una respuesta clara. Inténtalo de nuevo." }
        } finally {
            con.disconnect()
        }
    }

    private fun mensaje(rol: String, texto: String): JSONObject =
        JSONObject()
            .put("role", rol)
            .put("parts", JSONArray().put(JSONObject().put("text", texto)))

    fun mensajeError(e: Exception): String = when {
        e is ErrorIA && (e.codigo == 400 || e.codigo == 401 || e.codigo == 403) ->
            "Mi clave de inteligencia artificial no es válida. Revísala en ajustes."
        e is ErrorIA && e.codigo == 404 ->
            "El modelo de inteligencia artificial configurado no existe. Revisa el nombre del modelo en ajustes."
        e is ErrorIA && e.codigo == 429 ->
            "Alcancé el límite gratuito de consultas por ahora. Inténtalo en un momento."
        e is ErrorIA ->
            "El servidor de inteligencia artificial respondió con un error ${e.codigo}. Inténtalo de nuevo."
        e is IOException ->
            "No tengo conexión a internet en este momento."
        else ->
            "Algo falló al pensar la respuesta. Inténtalo de nuevo."
    }
}
