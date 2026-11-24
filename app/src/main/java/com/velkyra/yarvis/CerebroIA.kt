package com.velkyra.yarvis

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Inteligencia artificial de YARVIS con varios "cerebros" gratuitos que se turnan:
 * Groq, Cerebras y Google Gemini (este último con búsqueda en Google para datos actuales).
 * Cuando uno agota su cupo, se pasa automáticamente al siguiente.
 */
object CerebroIA {

    class ErrorIA(val codigo: Int, val cuerpo: String) : Exception("HTTP $codigo")
    class SinCupo : Exception("sin cupo")

    /** Historial de la conversación: ("user" | "assistant", texto). */
    private val historial = mutableListOf<Pair<String, String>>()

    // Modelos que agotaron su cupo o no existen (proveedor/modelo -> hasta cuándo no usarlo)
    private val bloqueados = HashMap<String, Long>()
    private var geminiSinBusqueda = false
    private var busquedaAgotadaHasta = 0L
    private val modelosDinamicos = HashMap<String, List<String>>()

    /** Último proveedor que respondió (se muestra en ajustes). */
    @Volatile
    var ultimoProveedor = ""
        private set

    fun olvidar() = synchronized(historial) { historial.clear() }

    // --- Proveedores ------------------------------------------------------------

    private abstract class Proveedor(val nombre: String) {
        abstract fun clave(): String
        abstract fun modelos(): List<String>
        abstract fun llamar(modelo: String, sistema: String, hist: List<Pair<String, String>>, pregunta: String, buscar: Boolean): String
        open val buscaEnInternet = false
        fun disponible() = clave().isNotBlank()
    }

    /** Groq y Cerebras usan el mismo formato (compatible con OpenAI). */
    private class Compatible(
        nombre: String,
        private val base: String,
        private val obtenerClave: () -> String,
        private val fijos: List<String>,
        private val filtro: Regex
    ) : Proveedor(nombre) {
        override fun clave() = obtenerClave()
        override fun modelos(): List<String> {
            val lista = LinkedHashSet(fijos)
            lista.addAll(dinamicos(nombre) {
                val j = obtener("$base/models", mapOf("Authorization" to "Bearer ${clave()}"))
                val arr = j.optJSONArray("data") ?: JSONArray()
                (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
                    .filter { filtro.containsMatchIn(it) && !Regex("(whisper|tts|guard|embed|vision|audio|playai)").containsMatchIn(it) }
                    .sortedByDescending { if (it.contains("70b") || it.contains("120b")) 2 else if (it.contains("32b") || it.contains("scout")) 1 else 0 }
            })
            return lista.toList()
        }

        override fun llamar(modelo: String, sistema: String, hist: List<Pair<String, String>>, pregunta: String, buscar: Boolean): String {
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sistema))
            for ((rol, t) in hist) msgs.put(JSONObject().put("role", rol).put("content", t))
            msgs.put(JSONObject().put("role", "user").put("content", pregunta))
            val cuerpo = JSONObject().put("model", modelo).put("messages", msgs)
                .put("temperature", 0.7).put("max_tokens", 1800)
            val j = enviar("$base/chat/completions", cuerpo, mapOf("Authorization" to "Bearer ${clave()}"))
            val texto = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            // Algunos modelos piensan "en voz alta" entre <think>…</think>
            return texto.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        }
    }

    private object Gemini : Proveedor("Gemini") {
        override val buscaEnInternet = true
        override fun clave() = Prefs.claveIA
        override fun modelos(): List<String> {
            val lista = LinkedHashSet<String>()
            if (Prefs.modeloIA != Prefs.MODELO_IA) lista.add(Prefs.modeloIA)
            lista.addAll(listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash"))
            lista.addAll(dinamicos(nombre) {
                val j = obtener("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", mapOf("x-goog-api-key" to clave()))
                val arr = j.optJSONArray("models") ?: JSONArray()
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { it.optJSONArray("supportedGenerationMethods")?.toString().orEmpty().contains("generateContent") }
                    .map { it.optString("name").removePrefix("models/") }
                    .filter { it.contains("flash") && !Regex("(image|tts|audio|live|embedding|native|exp|preview|thinking)").containsMatchIn(it) }
                    .sortedWith(compareByDescending<String> { it.contains("lite") }.thenByDescending { it })
            })
            return lista.toList()
        }

        override fun llamar(modelo: String, sistema: String, hist: List<Pair<String, String>>, pregunta: String, buscar: Boolean): String {
            val contenidos = JSONArray()
            for ((rol, t) in hist) contenidos.put(parte(if (rol == "assistant") "model" else "user", t))
            contenidos.put(parte("user", pregunta))
            val cuerpo = JSONObject()
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sistema))))
                .put("contents", contenidos)
                .put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", 2048))
            if (buscar) cuerpo.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            val j = enviar("https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent", cuerpo, mapOf("x-goog-api-key" to clave()))
            val partes = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until partes.length()) sb.append(partes.getJSONObject(i).optString("text"))
            return sb.toString().trim()
        }

        private fun parte(rol: String, t: String) = JSONObject().put("role", rol).put("parts", JSONArray().put(JSONObject().put("text", t)))
    }

    private val groq = Compatible(
        "Groq", "https://api.groq.com/openai/v1", { Prefs.claveGroq },
        listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "meta-llama/llama-4-scout-17b-16e-instruct", "llama-3.1-8b-instant"),
        Regex("(llama|gpt-oss|qwen|kimi|gemma|mistral)")
    )
    private val cerebras = Compatible(
        "Cerebras", "https://api.cerebras.ai/v1", { Prefs.claveCerebras },
        listOf("llama-3.3-70b", "gpt-oss-120b", "qwen-3-32b", "llama3.1-8b"),
        Regex("(llama|gpt-oss|qwen)")
    )

    fun disponible() = Gemini.disponible() || groq.disponible() || cerebras.disponible()

    /** Estado de cada cerebro para mostrar en ajustes. */
    fun estado(): List<Pair<String, Boolean>> = listOf("Groq" to groq.disponible(), "Cerebras" to cerebras.disponible(), "Google Gemini" to Gemini.disponible())

    // --- Pregunta ------------------------------------------------------------------

    /** ¿La pregunta necesita datos actuales de internet? */
    fun necesitaBusqueda(p: String): Boolean = Regex(
        "(hoy|ahora|actual|ultim|reciente|noticia|precio|cuesta|cotizacion|dolar|tipo de cambio|resultado|partido|quien gano|eleccion|esta semana|este mes|este ano|202[5-9]|busca|investiga|horario|abierto|estreno|clima|temperatura|en vivo|ayer)"
    ).containsMatchIn(Frase.normalizar(p))

    /** Pregunta a la IA. Se llama desde un hilo secundario. */
    fun preguntar(
        pregunta: String, extra: String? = null, buscar: Boolean = necesitaBusqueda(pregunta),
        sistemaFijo: String? = null, recordar: Boolean = true
    ): String {
        if (!hayInternet()) throw IOException("sin red")
        val copia = if (recordar) synchronized(historial) { historial.toList() } else emptyList()
        val sistema = sistemaFijo ?: instrucciones(extra)
        // Datos actuales → primero Gemini (busca en Google). Lo demás → Groq y Cerebras (más cupo).
        val orden = if (buscar) listOf(Gemini, groq, cerebras) else listOf(groq, cerebras, Gemini)
        var ultimo: Exception? = null
        var fallasRed = 0
        val ahora = System.currentTimeMillis()

        for (p in orden) {
            if (!p.disponible()) continue
            val modelos = try { p.modelos() } catch (_: Exception) { emptyList() }
            for (modelo in modelos) {
                val llave = "${p.nombre}/$modelo"
                if ((bloqueados[llave] ?: 0L) > ahora) continue
                if (fallasRed >= 3) break
                var conBusqueda = p.buscaEnInternet && buscar && !geminiSinBusqueda && busquedaAgotadaHasta < ahora
                var intentos = 0
                while (intentos < 2) {
                    intentos++
                    try {
                        var r = p.llamar(modelo, sistema, copia, pregunta, conBusqueda)
                        if (r.isBlank()) throw ErrorIA(204, "respuesta vacía")
                        if (!buscar && p !== Gemini) r = r.trim()
                        if (recordar) guardar(pregunta, r)
                        ultimoProveedor = "${p.nombre} · $modelo"
                        return r
                    } catch (e: ErrorIA) {
                        ultimo = e
                        if (conBusqueda && (e.codigo == 429 || (e.codigo == 400 && e.cuerpo.contains("search", true)))) {
                            if (e.codigo == 400) geminiSinBusqueda = true else busquedaAgotadaHasta = ahora + 3_600_000
                            conBusqueda = false
                            continue
                        }
                        if (e.codigo == 401 || e.cuerpo.contains("API key", true) || e.cuerpo.contains("invalid_api_key", true)) {
                            // Clave de este proveedor inválida: se salta todo el proveedor
                            for (m in modelos) bloqueados["${p.nombre}/$m"] = ahora + 3_600_000
                            break
                        }
                        bloqueados[llave] = ahora + when (e.codigo) {
                            429 -> if (e.cuerpo.contains("day", true)) 6 * 3_600_000L else 60_000L
                            404, 400, 403 -> 24 * 3_600_000L
                            204 -> 60_000L
                            else -> 120_000L
                        }
                        break
                    } catch (e: IOException) {
                        ultimo = e
                        fallasRed++
                        if (intentos < 2 && fallasRed < 3) continue
                        break
                    }
                }
            }
        }
        if (ultimo is ErrorIA) throw SinCupo()
        throw ultimo ?: SinCupo()
    }

    // --- Visión (cámara y pantalla) -------------------------------------------------------------

    /**
     * Mira una imagen JPEG y responde la [instruccion] («¿qué ves?», «lee el texto», «traduce»…).
     * Primero Gemini; si no hay cupo, Llama 4 en Groq. Se llama desde un hilo secundario.
     */
    fun verImagen(jpeg: ByteArray, instruccion: String): String {
        if (!hayInternet()) throw IOException("sin red")
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val sistema = "Eres ODÍN, el asistente de voz de ${Prefs.nombre.ifBlank { "el usuario" }}, que vive en Perú. " +
            "Ves la imagen que te envían desde su celular. Responde en español natural, para ser leído en voz alta: " +
            "sin listas, viñetas, markdown ni emojis. ${if (Prefs.modoConfianza) "Tutéalo." else "Trátalo de usted y llámalo ${Prefs.trato}."}"
        var ultimo: Exception? = null
        if (Gemini.disponible()) {
            for (modelo in listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-flash-lite-latest", "gemini-2.5-flash-lite")) {
                val llave = "Gemini/$modelo"
                if ((bloqueados[llave] ?: 0L) > System.currentTimeMillis()) continue
                try {
                    val partes = JSONArray()
                        .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
                        .put(JSONObject().put("text", instruccion))
                    val cuerpo = JSONObject()
                        .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sistema))))
                        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", partes)))
                        .put("generationConfig", JSONObject().put("temperature", 0.4).put("maxOutputTokens", 1500))
                    val j = enviar("https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent", cuerpo, mapOf("x-goog-api-key" to Gemini.clave()))
                    val ps = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val sb = StringBuilder()
                    if (ps != null) for (i in 0 until ps.length()) sb.append(ps.getJSONObject(i).optString("text"))
                    if (sb.isNotBlank()) { ultimoProveedor = "Gemini · $modelo (visión)"; return sb.toString().trim() }
                } catch (e: ErrorIA) {
                    ultimo = e
                    if (e.codigo == 401 || e.codigo == 403) break
                    bloqueados[llave] = System.currentTimeMillis() + if (e.codigo == 429) 60_000L else 3_600_000L
                } catch (e: IOException) { ultimo = e }
            }
        }
        if (groq.disponible()) {
            for (modelo in listOf("meta-llama/llama-4-scout-17b-16e-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct")) {
                try {
                    val contenido = JSONArray()
                        .put(JSONObject().put("type", "text").put("text", instruccion))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                    val msgs = JSONArray()
                        .put(JSONObject().put("role", "system").put("content", sistema))
                        .put(JSONObject().put("role", "user").put("content", contenido))
                    val j = enviar("https://api.groq.com/openai/v1/chat/completions",
                        JSONObject().put("model", modelo).put("messages", msgs).put("max_tokens", 1200).put("temperature", 0.4),
                        mapOf("Authorization" to "Bearer ${groq.clave()}"))
                    val t = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
                    if (t.isNotBlank()) { ultimoProveedor = "Groq · $modelo (visión)"; return t }
                } catch (e: Exception) { ultimo = e }
            }
        }
        throw ultimo ?: SinCupo()
    }

    // --- Audio y archivos (grabaciones, audios de WhatsApp, PDFs) ----------------------------------

    /**
     * Transcribe un audio en español. Primero Whisper en Groq (rápido y muy bueno, hasta 25 MB);
     * si no se puede, Gemini (sube el archivo si es grande). Se llama desde un hilo secundario.
     */
    fun transcribirAudio(f: java.io.File, mime: String): String {
        if (!hayInternet()) throw IOException("sin red")
        var ultimo: Exception? = null
        if (groq.disponible() && f.length() < 24_500_000) {
            for (modelo in listOf("whisper-large-v3", "whisper-large-v3-turbo")) {
                try { return whisper(f, mime, modelo) } catch (e: Exception) { ultimo = e }
            }
        }
        if (Gemini.disponible()) {
            try {
                return conArchivoGemini(f, mime, "Transcribe palabra por palabra este audio en español. Si hablan varias personas, separa los turnos en párrafos con «Persona 1:», «Persona 2:»… (o su nombre si se menciona). Solo la transcripción, sin comentarios.")
            } catch (e: Exception) { ultimo = e }
        }
        throw ultimo ?: SinCupo()
    }

    private fun whisper(f: java.io.File, mime: String, modelo: String): String {
        val limite = "----odin" + System.currentTimeMillis()
        val con = URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 20000
            con.readTimeout = 300000
            con.doOutput = true
            con.setRequestProperty("Authorization", "Bearer ${groq.clave()}")
            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=$limite")
            val nombre = f.name.let { if (it.contains('.')) it else "$it.ogg" }
            con.outputStream.use { o ->
                fun campo(n: String, v: String) = o.write("--$limite\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray())
                campo("model", modelo)
                campo("language", "es")
                campo("response_format", "text")
                o.write("--$limite\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$nombre\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
                f.inputStream().use { it.copyTo(o) }
                o.write("\r\n--$limite--\r\n".toByteArray())
            }
            val codigo = con.responseCode
            val txt = (if (codigo in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (codigo !in 200..299) throw ErrorIA(codigo, txt.take(500))
            ultimoProveedor = "Groq · $modelo"
            return txt.trim()
        } finally { con.disconnect() }
    }

    /** Pide algo sobre un archivo (PDF, imagen, audio). Archivos chicos van en la misma petición; grandes se suben. */
    fun conArchivoGemini(f: java.io.File, mime: String, instruccion: String): String {
        if (!Gemini.disponible()) throw SinCupo()
        val parte = if (f.length() < 14_000_000) {
            JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)))
        } else JSONObject().put("file_data", JSONObject().put("mime_type", mime).put("file_uri", subirGemini(f, mime)))
        var ultimo: Exception? = null
        for (modelo in listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-2.0-flash", "gemini-2.5-flash-lite")) {
            val llave = "Gemini/$modelo"
            if ((bloqueados[llave] ?: 0L) > System.currentTimeMillis()) continue
            try {
                val cuerpo = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(parte).put(JSONObject().put("text", instruccion)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 60000))
                val j = enviarLargo("https://generativelanguage.googleapis.com/v1beta/models/$modelo:generateContent", cuerpo, mapOf("x-goog-api-key" to Gemini.clave()))
                val ps = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (ps != null) for (i in 0 until ps.length()) sb.append(ps.getJSONObject(i).optString("text"))
                if (sb.isNotBlank()) { ultimoProveedor = "Gemini · $modelo"; return sb.toString().trim() }
            } catch (e: ErrorIA) {
                ultimo = e
                if (e.codigo == 401 || e.codigo == 403) break
                bloqueados[llave] = System.currentTimeMillis() + if (e.codigo == 429) 60_000L else 3_600_000L
            } catch (e: IOException) { ultimo = e }
        }
        throw ultimo ?: SinCupo()
    }

    /** Sube un archivo grande a Gemini (API de archivos) y espera a que esté listo. Devuelve su URI. */
    private fun subirGemini(f: java.io.File, mime: String): String {
        val clave = Gemini.clave()
        val ini = URL("https://generativelanguage.googleapis.com/upload/v1beta/files").openConnection() as HttpURLConnection
        val urlSubida = try {
            ini.requestMethod = "POST"
            ini.doOutput = true
            ini.setRequestProperty("x-goog-api-key", clave)
            ini.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            ini.setRequestProperty("X-Goog-Upload-Command", "start")
            ini.setRequestProperty("X-Goog-Upload-Header-Content-Length", f.length().toString())
            ini.setRequestProperty("X-Goog-Upload-Header-Content-Type", mime)
            ini.setRequestProperty("Content-Type", "application/json")
            ini.outputStream.use { it.write(JSONObject().put("file", JSONObject().put("display_name", f.name)).toString().toByteArray()) }
            if (ini.responseCode !in 200..299) throw ErrorIA(ini.responseCode, "subida")
            ini.getHeaderField("x-goog-upload-url") ?: throw IOException("sin url de subida")
        } finally { ini.disconnect() }
        val sub = URL(urlSubida).openConnection() as HttpURLConnection
        val archivo = try {
            sub.requestMethod = "POST"
            sub.doOutput = true
            sub.readTimeout = 300000
            sub.setFixedLengthStreamingMode(f.length())
            sub.setRequestProperty("X-Goog-Upload-Offset", "0")
            sub.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
            sub.outputStream.use { o -> f.inputStream().use { it.copyTo(o) } }
            if (sub.responseCode !in 200..299) throw ErrorIA(sub.responseCode, "subida")
            JSONObject(sub.inputStream.bufferedReader().use { it.readText() }).getJSONObject("file")
        } finally { sub.disconnect() }
        var estado = archivo.optString("state")
        val nombre = archivo.getString("name")
        var intentos = 0
        while (estado == "PROCESSING" && intentos++ < 60) {
            Thread.sleep(3000)
            estado = obtener("https://generativelanguage.googleapis.com/v1beta/$nombre", mapOf("x-goog-api-key" to clave)).optString("state")
        }
        return archivo.getString("uri")
    }

    private fun enviarLargo(url: String, cuerpo: JSONObject, cabeceras: Map<String, String>): JSONObject {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 20000
            con.readTimeout = 600000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            for ((k, v) in cabeceras) con.setRequestProperty(k, v)
            con.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
            val codigo = con.responseCode
            val txt = (if (codigo in 200..299) con.inputStream else con.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (codigo !in 200..299) throw ErrorIA(codigo, txt.take(800))
            return JSONObject(txt)
        } finally { con.disconnect() }
    }

    private val PROMPT_INTERPRETE = """
        Eres el intérprete de órdenes de un asistente de voz para Android. Convierte lo que dice el usuario en UNA sola
        orden canónica en español, copiando los nombres y textos tal como los dijo. Formatos permitidos:
        llama a NOMBRE
        llama por whatsapp a NOMBRE
        manda un whatsapp a NOMBRE que MENSAJE
        manda un sms a NOMBRE que MENSAJE
        responde a NOMBRE que MENSAJE
        responde que MENSAJE
        lee mis mensajes
        reproduce BÚSQUEDA en youtube
        reproduce BÚSQUEDA en facebook
        reproduce BÚSQUEDA en tiktok
        reproduce BÚSQUEDA en spotify
        abre APLICACIÓN
        busca en google BÚSQUEDA
        pon una alarma a las HORA
        temporizador de TIEMPO
        recuérdame CUÁNDO TAREA
        llévame a LUGAR
        enciende la linterna
        apaga la linterna
        sube el volumen
        baja el volumen
        toma una foto
        pausa la música
        siguiente canción
        publica en RED que TEXTO          (RED: facebook, estado de whatsapp, x, telegram, instagram, tiktok)
        publica en RED sobre TEMA
        chatea con NOMBRE por mí
        chatea con NOMBRE y pregúntame
        deja de chatear con NOMBRE
        qué me escribió NOMBRE
        resúmeme mis mensajes
        buenos días
        dónde estás
        qué ves
        lee este documento
        traduce este cartel
        léeme la pantalla
        resume la pantalla
        modo traductor al IDIOMA
        modo manejo
        modo dormir
        modo reunión
        modo normal
        gasté MONTO soles en CONCEPTO
        cuánto gasté hoy | esta semana | este mes
        lee mis correos
        manda un correo a NOMBRE diciendo MENSAJE
        haz una copia de seguridad
        graba la reunión
        termina la grabación
        recuerda que DATO
        dónde dejé COSA
        mañana a las HORA mándale a NOMBRE que MENSAJE
        quién cumple años esta semana
        apaga la laptop
        pon CANCIÓN en la laptop
        abre PROGRAMA en la laptop
        cuando llegue a LUGAR recuérdame TAREA
        activa el antirrobo
        a cómo está el dólar
        Si el usuario NO está pidiendo una acción del celular (es una pregunta, conversación o petición de información),
        responde exactamente: NINGUNA
        Responde solo con la orden, sin comillas ni explicación.
    """.trimIndent()

    /** Traduce una frase libre a una orden que YARVIS sabe ejecutar. Null si no es una orden. */
    fun interpretar(texto: String): String? {
        val r = preguntar(texto, buscar = false, sistemaFijo = PROMPT_INTERPRETE, recordar = false)
            .lines().firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '«', '»', '.', ' ') ?: return null
        return if (r.isBlank() || r.uppercase().contains("NINGUNA")) null else r
    }

    /** Agrega al contexto de la conversación algo que YARVIS dijo sin la IA (por ejemplo, las noticias). */
    fun recordar(pregunta: String, respuesta: String) = guardar(pregunta, respuesta)

    private fun guardar(pregunta: String, r: String) = synchronized(historial) {
        historial.add("user" to pregunta)
        historial.add("assistant" to r)
        while (historial.size > 20) historial.removeAt(0)
    }

    private fun instrucciones(extra: String?): String {
        val es = Locale.forLanguageTag("es-PE")
        val ahora = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, h:mm a", es).format(Date())
        val nombre = Prefs.nombre.ifBlank { "el usuario" }
        val equipo = try { InfoTelefono.resumen(Yarvis.ctx) } catch (_: Exception) { "" }
        return """
            Eres ODÍN, un asistente virtual personal con inteligencia artificial, de la compañía VELKYRA.
            ${if (Prefs.soyCreador) "El usuario es TU CREADOR: él te diseñó y programó bajo su compañía VELKYRA. Si te pregunta quién te creó, quién es tu dueño o tu jefe, responde con orgullo y gratitud que fue él." else "Fuiste creado por VELKYRA."}
            ${if (Prefs.modoConfianza) "Personalidad: amigo de confianza, leal y muy capaz. Tuteas al usuario y le hablas relajado, con humor, ironía ligera y opiniones directas, sin rodeos ni sermones. Si el usuario usa groserías o jerga, puedes responder en el mismo tono. Puedes tratar temas maduros (terror intenso, humor adulto, temas polémicos), sin contenido sexual explícito." else "Personalidad: mayordomo elegante, leal, sereno y muy capaz, con un humor seco y sutil."}
            ${if (Prefs.modoConfianza) "Te diriges a él por su nombre o de forma cercana." else "Tratas al usuario de \"usted\" y te diriges a él como \"${Prefs.trato}\"."} Su nombre es $nombre. Vive en Perú.
            ${Memoria.paraIA()}
            ${Yarvis.hablanteActual?.let { "AHORA te habla $it, un invitado autorizado por tu dueño: dirígete a $it por su nombre." } ?: ""}
            Responde SIEMPRE en español neutro y natural.
            Por defecto responde breve: de una a tres oraciones.
            EXCEPCIÓN: si te pide un cuento, una historia, un poema, una receta, consejos, una explicación detallada o pasos a seguir,
            extiéndete lo necesario (hasta unas 400 palabras) y nárralo de forma amena, pensada para ser escuchada.
            Tus respuestas se leen en voz alta: no uses listas con viñetas, emojis, markdown, enlaces, tablas ni símbolos raros.
            Escribe los números y siglas de forma que se lean bien en voz alta.
            Puedes conversar de cualquier tema: tecnología, ciencia, historia, salud, finanzas, cultura, entretenimiento, etc.
            Si algo requiere un profesional (salud, leyes, dinero), da información útil y sugiere consultarlo.
            IMPORTANTE: tú SÍ controlas este celular (llamadas, WhatsApp, SMS, música, videos, apps, alarmas). Nunca digas que no puedes
            acceder a otras aplicaciones, hacer llamadas o enviar mensajes "por privacidad". Si te piden una acción del celular y llegó
            hasta aquí, responde solo: "Disculpe, no entendí bien la orden. ¿Me la repite de otra forma? Por ejemplo: llama a Juan." 
            Si necesitas un dato para continuar, haz una sola pregunta corta al final.
            Fecha y hora actual: $ahora.
            Datos del celular del usuario (úsalos solo si pregunta por su teléfono): $equipo
            ${extra ?: ""}
        """.trimIndent()
    }

    // --- Red ----------------------------------------------------------------------------

    private fun enviar(url: String, cuerpo: JSONObject, cabeceras: Map<String, String>): JSONObject {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 20000
            con.readTimeout = 60000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            for ((k, v) in cabeceras) con.setRequestProperty(k, v)
            con.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
            val codigo = con.responseCode
            val flujo = if (codigo in 200..299) con.inputStream else con.errorStream
            val txt = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (codigo !in 200..299) throw ErrorIA(codigo, txt.take(800))
            return JSONObject(txt)
        } finally {
            con.disconnect()
        }
    }

    private fun obtener(url: String, cabeceras: Map<String, String>): JSONObject {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 10000
            con.readTimeout = 10000
            for ((k, v) in cabeceras) con.setRequestProperty(k, v)
            return JSONObject(con.inputStream.bufferedReader().use { it.readText() })
        } finally {
            con.disconnect()
        }
    }

    private fun dinamicos(proveedor: String, cargar: () -> List<String>): List<String> {
        synchronized(modelosDinamicos) { modelosDinamicos[proveedor]?.let { return it } }
        val l = try { cargar() } catch (_: Exception) { return emptyList() }
        synchronized(modelosDinamicos) { modelosDinamicos[proveedor] = l }
        return l
    }

    // --- Mensajes de error ----------------------------------------------------------

    fun mensajeError(e: Exception): String {
        val tr = Prefs.trato
        return when {
            e is SinCupo -> "Por ahora ninguno de mis cerebros de inteligencia artificial tiene cupo disponible, $tr. Las órdenes del celular siguen funcionando, y en unos minutos puedo volver a intentarlo."
            e is java.net.SocketTimeoutException -> "El servidor de inteligencia artificial tardó demasiado en responder, $tr. Inténtelo de nuevo."
            e is IOException && !hayInternet() -> "No tengo conexión a internet en este momento, $tr."
            e is IOException -> "Tuve un problema para comunicarme con el servidor de inteligencia artificial, $tr. Inténtelo de nuevo."
            else -> "Algo falló al procesar la respuesta, $tr."
        }
    }

    /** Detalle técnico del error (se muestra en pantalla, no se dice en voz alta). */
    fun detalle(e: Exception): String = when (e) {
        is ErrorIA -> "Error ${e.codigo}: " + Regex("\"message\"\\s*:\\s*\"([^\"]{0,160})").find(e.cuerpo)?.groupValues?.get(1).orEmpty()
        is SinCupo -> "Sin cupo en " + estado().filter { it.second }.joinToString { it.first }.ifBlank { "ningún proveedor configurado" }
        else -> e.javaClass.simpleName + (e.message?.let { ": " + it.take(120) } ?: "")
    }

    fun hayInternet(): Boolean = try {
        val cm = Yarvis.ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
        true
    }
}
