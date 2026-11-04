package com.velkyra.yarvis

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Inteligencia artificial de Google Gemini, con búsqueda en Google para datos actuales. */
object CerebroIA {

    class ErrorIA(val codigo: Int, val cuerpo: String) : Exception("HTTP $codigo")

    private val historial = mutableListOf<Pair<String, String>>()
    private var sinBusqueda = false

    fun olvidar() = synchronized(historial) { historial.clear() }

    fun disponible() = Prefs.claveIA.isNotBlank()

    class SinCupo : Exception("sin cupo")

    // Modelos que agotaron su cupo gratuito (modelo -> hasta cuándo no usarlo)
    private val agotados = HashMap<String, Long>()
    private var busquedaAgotadaHasta = 0L
    private var dinamicos: List<String>? = null

    private val preferidos = listOf(
        "gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite",
        "gemini-2.0-flash", "gemini-2.0-flash-lite"
    )

    /** ¿La pregunta necesita datos actuales de internet? (la búsqueda gasta cupo, solo se usa si hace falta) */
    fun necesitaBusqueda(p: String): Boolean = Regex(
        "(hoy|ahora|actual|ultim|reciente|noticia|precio|cuesta|cotizacion|dolar|tipo de cambio|resultado|partido|quien gano|eleccion|esta semana|este mes|este ano|202[5-9]|busca|investiga|horario|abierto|estreno|clima|temperatura)"
    ).containsMatchIn(Frase.normalizar(p))

    /** Pregunta a la IA. Se llama desde un hilo secundario. */
    fun preguntar(pregunta: String, extra: String? = null, buscar: Boolean = necesitaBusqueda(pregunta)): String {
        val copia = synchronized(historial) { historial.toList() }
        val candidatos = LinkedHashSet<String>()
        if (Prefs.modeloIA != Prefs.MODELO_IA) candidatos.add(Prefs.modeloIA)
        candidatos.addAll(preferidos)
        candidatos.addAll(modelosDisponibles())
        var ultimo: Exception? = null
        val ahora = System.currentTimeMillis()
        for (modelo in candidatos) {
            if ((agotados[modelo] ?: 0L) > ahora) continue
            var conBusqueda = buscar && !sinBusqueda && busquedaAgotadaHasta < ahora
            var intentos = 0
            while (intentos < 2) {
                intentos++
                try {
                    val r = llamar(modelo, copia, pregunta, extra, conBusqueda)
                    synchronized(historial) {
                        historial.add("user" to pregunta)
                        historial.add("model" to r)
                        while (historial.size > 16) historial.removeAt(0)
                    }
                    return r
                } catch (e: ErrorIA) {
                    ultimo = e
                    if (conBusqueda && (e.codigo == 429 || (e.codigo == 400 && e.cuerpo.contains("search", true)))) {
                        // Se agotó o no está disponible la búsqueda: reintentar sin ella
                        if (e.codigo == 400) sinBusqueda = true else busquedaAgotadaHasta = ahora + 3_600_000
                        conBusqueda = false
                        continue
                    }
                    when (e.codigo) {
                        429 -> agotados[modelo] = ahora + if (e.cuerpo.contains("PerDay", true) || e.cuerpo.contains("per day", true)) 6 * 3_600_000L else 60_000L
                        404, 400 -> if (e.cuerpo.contains("API key", true) || e.cuerpo.contains("API_KEY", true)) throw e else agotados[modelo] = ahora + 24 * 3_600_000L
                        else -> if (e.codigo in 500..599) agotados[modelo] = ahora + 120_000L else throw e
                    }
                    break
                }
            }
        }
        if (ultimo is ErrorIA && (ultimo.codigo == 429 || ultimo.codigo == 404 || ultimo.codigo == 400)) throw SinCupo()
        throw ultimo ?: IOException("sin respuesta")
    }

    /** Lista de modelos "flash" disponibles para la clave (se consulta una vez). */
    private fun modelosDisponibles(): List<String> {
        dinamicos?.let { return it }
        val lista = try {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection() as HttpURLConnection
            c.setRequestProperty("x-goog-api-key", Prefs.claveIA)
            c.connectTimeout = 10000
            c.readTimeout = 10000
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            c.disconnect()
            val arr = j.optJSONArray("models") ?: JSONArray()
            val r = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val nombre = m.optString("name").removePrefix("models/")
                val metodos = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                if (!metodos.contains("generateContent")) continue
                if (!nombre.contains("flash") || Regex("(image|tts|audio|live|embedding|native|exp|preview|thinking)").containsMatchIn(nombre)) continue
                r.add(nombre)
            }
            // Primero los "lite" (más cupo gratuito), luego los más nuevos
            r.sortedWith(compareByDescending<String> { it.contains("lite") }.thenByDescending { it })
        } catch (_: Exception) {
            emptyList()
        }
        dinamicos = lista
        return lista
    }

    private fun llamar(modelo: String, hist: List<Pair<String, String>>, pregunta: String, extra: String?, conBusqueda: Boolean): String {
        val es = Locale.forLanguageTag("es-PE")
        val ahora = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, h:mm a", es).format(Date())
        val nombre = Prefs.nombre.ifBlank { "el usuario" }
        val sistema = """
            Eres YARVIS, un asistente virtual personal con inteligencia artificial, creado por VELKYRA.
            Personalidad: mayordomo británico elegante, leal, sereno y muy capaz, con un humor seco y sutil.
            Tratas al usuario de "usted" y te diriges a él como "${Prefs.trato}". Su nombre es $nombre.
            Responde SIEMPRE en español, de forma breve y natural: una a tres oraciones, salvo que pida detalle.
            Tus respuestas se leen en voz alta: no uses listas, viñetas, emojis, markdown, enlaces ni símbolos.
            Escribe los números de forma que se lean bien en voz alta.
            Si el usuario pide algo que requiere una acción en el celular que no puedes hacer, díselo con elegancia.
            Si necesitas un dato para continuar, haz una sola pregunta corta al final.
            Fecha y hora actual: $ahora. Zona: Perú.
            ${extra ?: ""}
        """.trimIndent()

        val contenidos = JSONArray()
        for ((rol, texto) in hist) contenidos.put(mensaje(rol, texto))
        contenidos.put(mensaje("user", pregunta))

        val cuerpo = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sistema))))
            .put("contents", contenidos)
            .put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", 600))
        if (conBusqueda) cuerpo.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

        val con = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent")
            .openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15000
            con.readTimeout = 45000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            con.setRequestProperty("x-goog-api-key", Prefs.claveIA)
            con.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
            val codigo = con.responseCode
            val flujo = if (codigo in 200..299) con.inputStream else con.errorStream
            val txt = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (codigo !in 200..299) throw ErrorIA(codigo, txt.take(600))
            val json = JSONObject(txt)
            val candidatos = json.optJSONArray("candidates")
            if (candidatos == null || candidatos.length() == 0) return "Prefiero no responder a eso, ${Prefs.trato}."
            val partes = candidatos.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: return "No obtuve una respuesta clara, ${Prefs.trato}. ¿Lo intentamos de nuevo?"
            val sb = StringBuilder()
            for (i in 0 until partes.length()) sb.append(partes.getJSONObject(i).optString("text"))
            return sb.toString().trim().ifEmpty { "No obtuve una respuesta clara, ${Prefs.trato}." }
        } finally {
            con.disconnect()
        }
    }

    private fun mensaje(rol: String, texto: String) = JSONObject()
        .put("role", rol)
        .put("parts", JSONArray().put(JSONObject().put("text", texto)))

    fun mensajeError(e: Exception): String {
        val tr = Prefs.trato
        return when {
            e is SinCupo -> "Se agotó por hoy el cupo gratuito de mi inteligencia artificial, $tr. Las órdenes del celular siguen funcionando con normalidad. El cupo se renueva mañana, o puede añadir otra clave en ajustes." 
            e is ErrorIA && (e.codigo == 400 || e.codigo == 401 || e.codigo == 403) && e.cuerpo.contains("API key", true) ->
                "Mi clave de inteligencia artificial no es válida, $tr. Puede revisarla en ajustes."
            e is ErrorIA && e.codigo == 403 -> "Google rechazó mi acceso a la inteligencia artificial, $tr. Revise la clave en ajustes."
            e is ErrorIA && e.codigo == 404 -> "El modelo de inteligencia artificial configurado ya no existe, $tr. Revíselo en ajustes."
            e is ErrorIA && e.codigo == 429 -> "He alcanzado el límite gratuito de consultas por el momento, $tr. Inténtelo en un minuto."
            e is ErrorIA && e.codigo >= 500 -> "Los servidores de inteligencia artificial están ocupados, $tr. Inténtelo de nuevo."
            e is IOException -> "No tengo conexión a internet en este momento, $tr."
            else -> "Algo falló al procesar la respuesta, $tr."
        }
    }
}
