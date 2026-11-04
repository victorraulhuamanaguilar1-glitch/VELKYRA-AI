package com.velkyra.asistente

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * El "cerebro" de VELKYRA: entiende las órdenes y las ejecuta.
 * Primero intenta con los comandos del celular (sin internet).
 * Si no es un comando, le pregunta a la inteligencia artificial.
 */
class Cerebro(private val act: Activity, private val prefs: SharedPreferences) {

    companion object {
        const val MODELO_POR_DEFECTO = "gemini-2.5-flash"

        fun normalizar(s: String): String {
            val sinTildes = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            return sinTildes
                .replace(Regex("[¿?¡!,;\"]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimEnd('.')
        }
    }

    private val es = Locale.forLanguageTag("es-PE")
    private val historial = mutableListOf<Pair<String, String>>()

    /** Se llama cuando el usuario se despide, para cerrar la app. */
    var alCerrar: (() -> Unit)? = null

    val nombre: String
        get() = prefs.getString("usuario", "Víctor").orEmpty().ifBlank { "Víctor" }

    fun procesar(original: String, responder: (String) -> Unit) {
        val t = normalizar(original)
        val local = try {
            comandoLocal(t, original)
        } catch (e: Exception) {
            "Tuve un problema al ejecutar esa orden."
        }
        if (local != null) {
            responder(local)
            return
        }

        val clave = prefs.getString("clave_ia", "").orEmpty().trim()
        if (clave.isEmpty()) {
            buscarWeb(original)
            responder("Mi cerebro de inteligencia artificial aún no está activado, así que te muestro lo que encontré en internet. Puedes activarlo en ajustes.")
            return
        }
        val modelo = prefs.getString("modelo_ia", MODELO_POR_DEFECTO).orEmpty().trim().ifBlank { MODELO_POR_DEFECTO }
        val copia = synchronized(historial) { historial.toList() }

        Thread {
            val respuesta = try {
                val r = CerebroIA.preguntar(clave, modelo, nombre, copia, original)
                synchronized(historial) {
                    historial.add("user" to original)
                    historial.add("model" to r)
                    while (historial.size > 12) historial.removeAt(0)
                }
                limpiarParaVoz(r)
            } catch (e: Exception) {
                CerebroIA.mensajeError(e)
            }
            responder(respuesta)
        }.start()
    }

    // ------------------------------------------------------------------
    //  Comandos del celular
    // ------------------------------------------------------------------

    private fun comandoLocal(t: String, original: String): String? {
        if (t.isBlank()) return "No te escuché bien, $nombre."

        // Despedida
        if (Regex("^(adios|hasta luego|apagate|cierrate|chau|chao|nos vemos|desconectate)\\b").containsMatchIn(t)) {
            alCerrar?.invoke()
            return "Hasta luego, $nombre. Aquí estaré cuando me necesites."
        }

        // Ayuda
        if (Regex("(que puedes hacer|que sabes hacer|^ayuda$|que comandos|lista de comandos)").containsMatchIn(t)) {
            return "Puedo decirte la hora y la fecha, abrir aplicaciones, llamar a tus contactos, buscar en Google y en YouTube, " +
                "guiarte con el mapa, poner alarmas y temporizadores, encender la linterna, controlar el volumen, " +
                "hacer cálculos, guardar notas y, con la inteligencia artificial activada, responder cualquier pregunta."
        }

        // Conversación básica
        if (Regex("^(hola|hey|buenas|buenos dias|buenas tardes|buenas noches|que tal)\\b").containsMatchIn(t) &&
            t.split(" ").size <= 4
        ) {
            return "${saludoPorHora()}, $nombre. ¿En qué te puedo ayudar?"
        }
        if (Regex("(quien eres|como te llamas|cual es tu nombre|presentate)").containsMatchIn(t)) {
            return "Soy VELKYRA, tu asistente virtual personal. Estoy aquí para ayudarte en lo que necesites, $nombre."
        }
        if (Regex("(como estas|como te encuentras|como te va)").containsMatchIn(t)) {
            return "Todos mis sistemas funcionan al cien por ciento. Gracias por preguntar, $nombre."
        }
        if (Regex("^(gracias|muchas gracias|excelente|buen trabajo|te pasaste)").containsMatchIn(t)) {
            return "Para eso estoy, $nombre."
        }
        if (Regex("(chiste|hazme reir|algo gracioso)").containsMatchIn(t)) {
            return chistes.random()
        }

        // Alarma
        if (t.contains("alarma") || t.contains("despiertame")) {
            return ponerAlarma(t)
        }

        // Temporizador
        if (Regex("(temporizador|cronometro|avisame en|cuenta regresiva)").containsMatchIn(t)) {
            val m = Regex("(\\d+|un|una|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|quince|veinte|treinta|media)\\s*(segundo|minuto|hora)")
                .find(t) ?: return "¿De cuánto tiempo? Por ejemplo: temporizador de 5 minutos."
            val n = numero(m.groupValues[1])
            val segundos = when (m.groupValues[2]) {
                "segundo" -> n
                "minuto" -> n * 60
                else -> n * 3600
            }.toInt()
            if (segundos <= 0) return "Ese tiempo no es válido."
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, segundos)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "VELKYRA")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            return if (lanzar(i)) "Temporizador iniciado: ${describirDuracion(segundos)}." else "No encontré una aplicación de reloj."
        }

        // Linterna
        if (t.contains("linterna") || Regex("(prende|enciende|apaga) (la )?luz").containsMatchIn(t)) {
            val encender = !Regex("(apaga|desactiva|quita)").containsMatchIn(t)
            return if (linterna(encender)) {
                if (encender) "Linterna encendida." else "Linterna apagada."
            } else {
                "Este equipo no tiene flash disponible."
            }
        }

        // Batería
        if (t.contains("bateria") || t.contains("carga del celular")) {
            val bm = act.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val nivel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val cargando = bm.isCharging
            var r = "La batería está al $nivel por ciento" + if (cargando) " y cargando." else "."
            if (nivel in 0..15 && !cargando) r += " Te recomiendo conectarlo pronto."
            return r
        }

        // Volumen
        if (t.contains("volumen") || t.contains("silencia")) {
            val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val flujo = AudioManager.STREAM_MUSIC
            when {
                Regex("(maximo|al tope|full)").containsMatchIn(t) ->
                    am.setStreamVolume(flujo, am.getStreamMaxVolume(flujo), AudioManager.FLAG_SHOW_UI)
                Regex("(sube|subir|aumenta|mas)").containsMatchIn(t) ->
                    repeat(3) { am.adjustStreamVolume(flujo, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
                Regex("(baja|bajar|disminuye|menos)").containsMatchIn(t) ->
                    repeat(3) { am.adjustStreamVolume(flujo, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
                Regex("(silencia|silencio|mute)").containsMatchIn(t) ->
                    am.adjustStreamVolume(flujo, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                else -> return "¿Subo o bajo el volumen?"
            }
            return "Hecho."
        }

        // Notas
        if (Regex("((borra|elimina|limpia) (las |mis |todas las )?notas)").containsMatchIn(t)) {
            prefs.edit().putString("notas", "").apply()
            return "Borré todas tus notas."
        }
        if (Regex("(mis notas|que (te )?(pedi|dije) (que )?(recordar|anotar)|que anotaste|lee las notas|que tengo anotado)").containsMatchIn(t)) {
            val notas = prefs.getString("notas", "").orEmpty().lines().filter { it.isNotBlank() }
            return if (notas.isEmpty()) "No tienes notas guardadas." else "Tienes ${notas.size} nota${if (notas.size == 1) "" else "s"}: " + notas.joinToString(". ") + "."
        }
        val rNota = Regex("(?:recuerdame que|recuerda que|anota que|apunta que|toma nota de que|toma nota|anota)\\s+(.+)$", RegexOption.IGNORE_CASE)
        rNota.find(t)?.let { mt ->
            val nota = rNota.find(original.trim())?.groupValues?.get(1)?.trim() ?: mt.groupValues[1]
            val anteriores = prefs.getString("notas", "").orEmpty()
            prefs.edit().putString("notas", (anteriores + "\n" + nota).trim()).apply()
            return "Anotado: $nota."
        }

        // Llamar
        Regex("^(?:llama|llamar|llamale|marca|marcar|marcale|telefonea)\\s+(?:a\\s+|al\\s+)?(.+)$").find(t)?.let { m ->
            return llamar(m.groupValues[1].trim())
        }

        // YouTube
        Regex("(?:pon|ponme|reproduce|reproducir|busca|buscar|quiero ver|muestrame)\\s+(.+?)\\s+en\\s+youtube").find(t)?.let { m ->
            return youtube(m.groupValues[1])
        }
        Regex("^youtube\\s+(.+)$").find(t)?.let { m -> return youtube(m.groupValues[1]) }

        // Mapas
        Regex("(?:como llego a|como llegar a|llevame a|navega a|navegar a|ruta a|ruta hacia|donde queda)\\s+(.+)$").find(t)?.let { m ->
            val lugar = m.groupValues[1]
            val navegar = !t.contains("donde queda")
            val uri = if (navegar) Uri.parse("google.navigation:q=" + Uri.encode(lugar))
            else Uri.parse("geo:0,0?q=" + Uri.encode(lugar))
            if (!lanzar(Intent(Intent.ACTION_VIEW, uri))) {
                lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/" + Uri.encode(lugar))))
            }
            return if (navegar) "Trazando la ruta hacia $lugar." else "Te muestro dónde queda $lugar."
        }

        // Clima
        if (Regex("(clima|que tiempo hace|temperatura|va a llover)").containsMatchIn(t)) {
            buscarWeb(original)
            return "Te muestro el pronóstico del clima."
        }

        // Abrir aplicaciones
        Regex("^(?:abre|abrir|abreme|inicia|ejecuta|lanza|entra a|entra en)\\s+(?:la app\\s+|la aplicacion\\s+|el\\s+|la\\s+|los\\s+)?(.+)$").find(t)?.let { m ->
            return abrir(m.groupValues[1].trim())
        }

        // Buscar en internet
        Regex("^(?:busca|buscar|buscame|googlea|investiga)\\s+(?:en google\\s+|en internet\\s+|sobre\\s+)?(.+)$").find(t)?.let { m ->
            buscarWeb(m.groupValues[1])
            return "Buscando ${m.groupValues[1]} en internet."
        }

        // Hora y fecha
        if (Regex("(que hora|la hora|hora es)").containsMatchIn(t) || t == "hora") {
            return "Son las ${SimpleDateFormat("h:mm a", es).format(Date())}."
        }
        if (Regex("(que dia|que fecha|la fecha|dia es hoy)").containsMatchIn(t)) {
            return "Hoy es ${SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", es).format(Date())}."
        }

        // Calculadora
        val calc = Regex("(-?\\d+(?:[.,]\\d+)?)\\s*(\\+|mas|menos|-|x|\\*|×|por|/|dividido entre|dividido por|dividido|entre)\\s*(-?\\d+(?:[.,]\\d+)?)")
            .find(t)
        if (calc != null) {
            val a = calc.groupValues[1].replace(',', '.').toDouble()
            val b = calc.groupValues[3].replace(',', '.').toDouble()
            val resultado = when (calc.groupValues[2]) {
                "+", "mas" -> a + b
                "-", "menos" -> a - b
                "x", "*", "×", "por" -> a * b
                else -> {
                    if (b == 0.0) return "No se puede dividir entre cero."
                    a / b
                }
            }
            return "El resultado es ${formatoNumero(resultado)}."
        }

        return null
    }

    // ------------------------------------------------------------------
    //  Acciones
    // ------------------------------------------------------------------

    private fun ponerAlarma(t: String): String {
        var hora: Int
        var minuto = 0
        val conY = Regex("(\\d{1,2})\\s+y\\s+(\\d{1,2})\\b").find(t)
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?\\s?m|p\\.?\\s?m|de la manana|de la tarde|de la noche)?").find(t)
            ?: return "¿A qué hora pongo la alarma? Por ejemplo: pon una alarma a las 6 y 30."
        hora = m.groupValues[1].toInt()
        m.groupValues[2].toIntOrNull()?.let { minuto = it }
        if (conY != null) minuto = conY.groupValues[2].toInt()
        if (t.contains("y media")) minuto = 30
        if (t.contains("y cuarto")) minuto = 15

        val sufijo = m.groupValues[3].ifEmpty {
            Regex("(de la manana|de la tarde|de la noche|p\\.?\\s?m|a\\.?\\s?m)").find(t)?.value.orEmpty()
        }
        if ((sufijo.startsWith("p") || sufijo.contains("tarde") || sufijo.contains("noche")) && hora < 12) hora += 12
        if (sufijo.startsWith("a") && hora == 12) hora = 0
        if (hora > 23 || minuto > 59) return "Esa hora no es válida."

        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hora)
            .putExtra(AlarmClock.EXTRA_MINUTES, minuto)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "VELKYRA")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (lanzar(i)) "Listo, alarma programada para las ${formatoHora(hora, minuto)}."
        else "No encontré una aplicación de reloj para programar la alarma."
    }

    private fun linterna(encender: Boolean): Boolean {
        val cm = act.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return false
        cm.setTorchMode(id, encender)
        return true
    }

    private fun llamar(quien: String): String {
        val numero: String
        val nombreContacto: String
        val digitos = quien.replace(Regex("[^0-9+]"), "")
        val soloNumero = digitos.length >= 3 && quien.replace(Regex("[0-9+\\s-]"), "").length <= 2

        if (soloNumero) {
            numero = digitos
            nombreContacto = digitos.chunked(3).joinToString(" ")
        } else {
            if (act.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                return "Necesito permiso para leer tus contactos. Actívalo en los ajustes de la aplicación."
            }
            val encontrado = buscarContacto(quien) ?: return "No encontré a $quien en tus contactos."
            nombreContacto = encontrado.first
            numero = encontrado.second
        }

        val puedeLlamar = act.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val accion = if (puedeLlamar) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return if (lanzar(Intent(accion, Uri.parse("tel:" + Uri.encode(numero))))) "Llamando a $nombreContacto."
        else "Este equipo no puede hacer llamadas."
    }

    private fun buscarContacto(objetivo: String): Pair<String, String>? {
        val cursor = act.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return null

        var parecido: Pair<String, String>? = null
        cursor.use { c ->
            while (c.moveToNext()) {
                val nom = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val n = normalizar(nom)
                if (n == objetivo) return nom to num
                if (parecido == null && n.length >= 3 && (n.contains(objetivo) || objetivo.contains(n))) {
                    parecido = nom to num
                }
            }
        }
        return parecido
    }

    private fun abrir(app: String): String {
        val especiales = mapOf(
            "configuracion" to Settings.ACTION_SETTINGS,
            "ajustes" to Settings.ACTION_SETTINGS,
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS
        )
        especiales[app]?.let { accion ->
            lanzar(Intent(accion))
            return "Abriendo $app."
        }

        val pm = act.packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        var elegido: ResolveInfo? = null
        for (ri in apps) {
            val etiqueta = normalizar(ri.loadLabel(pm).toString())
            if (etiqueta == app) {
                elegido = ri
                break
            }
            if (elegido == null && etiqueta.length >= 3 && (etiqueta.contains(app) || app.contains(etiqueta))) {
                elegido = ri
            }
        }
        val e = elegido ?: return "No encontré la aplicación $app en tu celular."
        val lanzador = pm.getLaunchIntentForPackage(e.activityInfo.packageName)
            ?: return "No pude abrir $app."
        return if (lanzar(lanzador)) "Abriendo ${e.loadLabel(pm)}." else "No pude abrir $app."
    }

    private fun youtube(busqueda: String): String {
        val app = Intent(Intent.ACTION_SEARCH)
            .setPackage("com.google.android.youtube")
            .putExtra("query", busqueda)
        if (!lanzar(app)) {
            lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(busqueda))))
        }
        return "Buscando $busqueda en YouTube."
    }

    private fun buscarWeb(busqueda: String) {
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, busqueda)
        if (!lanzar(i)) {
            lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(busqueda))))
        }
    }

    private fun lanzar(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        act.startActivity(i)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    // ------------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------------

    private fun saludoPorHora(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 12 -> "Buenos días"
            h < 19 -> "Buenas tardes"
            else -> "Buenas noches"
        }
    }

    fun saludoInicial(): String =
        "${saludoPorHora()}, $nombre. Todos los sistemas en línea. ¿En qué te puedo ayudar?"

    private fun formatoHora(h: Int, m: Int): String {
        val h12 = if (h % 12 == 0) 12 else h % 12
        val parte = when {
            h < 12 -> "de la mañana"
            h < 19 -> "de la tarde"
            else -> "de la noche"
        }
        return String.format(es, "%d:%02d %s", h12, m, parte)
    }

    private fun describirDuracion(seg: Int): String = when {
        seg % 3600 == 0 -> { val n = seg / 3600; "$n hora${if (n == 1) "" else "s"}" }
        seg % 60 == 0 -> { val n = seg / 60; "$n minuto${if (n == 1) "" else "s"}" }
        else -> "$seg segundos"
    }

    private fun numero(palabra: String): Double {
        palabra.toDoubleOrNull()?.let { return it }
        return when (palabra) {
            "un", "una", "uno" -> 1.0
            "dos" -> 2.0
            "tres" -> 3.0
            "cuatro" -> 4.0
            "cinco" -> 5.0
            "seis" -> 6.0
            "siete" -> 7.0
            "ocho" -> 8.0
            "nueve" -> 9.0
            "diez" -> 10.0
            "quince" -> 15.0
            "veinte" -> 20.0
            "treinta" -> 30.0
            "media" -> 0.5
            else -> 1.0
        }
    }

    private fun formatoNumero(x: Double): String =
        if (x == floor(x) && abs(x) < 1e15) x.toLong().toString()
        else String.format(es, "%.4f", x).trimEnd('0').trimEnd(',', '.')

    private fun limpiarParaVoz(s: String): String =
        s.replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
            .replace(Regex("[*#_`>]"), "")
            .replace(Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private val chistes = listOf(
        "¿Por qué la computadora fue al médico? Porque tenía un virus. Tranquilo, yo estoy vacunada.",
        "Le pregunté al wifi si me quería. Me dijo que sí, pero solo cuando estoy cerca.",
        "¿Qué le dijo un byte a otro byte? Nos vemos en el bus.",
        "Un programador va a la tienda. Su esposa le dice: compra un pan, y si hay huevos, trae seis. Volvió con seis panes.",
        "¿Cuál es el colmo de un celular? Tener muchas aplicaciones y ninguna aplicación en la vida.",
        "Mi batería y yo tenemos algo en común: al final del día las dos necesitamos recargarnos."
    )
}
