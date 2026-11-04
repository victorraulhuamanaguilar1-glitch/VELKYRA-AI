package com.velkyra.yarvis

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * El cerebro de YARVIS: entiende la orden y la ejecuta.
 * Primero intenta con las funciones del celular; si no es una orden, responde la IA.
 */
class Cerebro(private val ctx: Context) {

    private val es = Locale.forLanguageTag("es-PE")
    private val principal = Handler(Looper.getMainLooper())
    private val tr get() = Prefs.trato

    private val ASYNC = Yarvis.Respuesta("\u0000")
    private fun R(t: String, seguir: Boolean = false, cerrar: Boolean = false, detalle: String? = null, despues: (() -> Unit)? = null) = Yarvis.Respuesta(t, seguir, despues, cerrar, detalle)

    // ¿La última orden abrió otra app? (entonces no se sigue escuchando)
    @Volatile
    private var abrioApp = false
    fun tomarAbrioApp(): Boolean { val v = abrioApp; abrioApp = false; return v }

    fun olvidarConversacion() = CerebroIA.olvidar()

    // Conversación en varios pasos ("¿Qué le digo a Juan?")
    private var pendiente: ((Frase) -> Yarvis.Respuesta?)? = null
    private var pendienteHasta = 0L

    private fun esperar(pregunta: String, siguiente: (Frase) -> Yarvis.Respuesta?): Yarvis.Respuesta {
        pendiente = siguiente
        pendienteHasta = System.currentTimeMillis() + 90_000
        return R(pregunta, seguir = true)
    }

    /** Quita la palabra de activación y frases de cortesía: «Yarvis, por favor, ¿podrías llamar a…?» → «llamar a…» */
    fun limpiarOrden(texto: String): String {
        var t = texto.trim().trimStart('¿', '¡', ',', '.', ' ')
        val prefijos = listOf(
            "(oye|hey|ey|hola)\\s*,?\\s+",
            "(yarvis|jarvis|llarvis|harvis|${Regex.escape(Prefs.palabraActivacion)})\\s*[,.]?\\s*",
            "(por favor|porfa|porfavor)\\s*,?\\s+",
            "(puedes|podr[ií]as|me puedes|me podr[ií]as|quiero que|quisiera que|necesito que|deseo que|hazme el favor de|ay[uú]dame a|te pido que)\\s+",
            "(me\\s+)?(ayudas a|haces el favor de)\\s+"
        )
        var cambio = true
        while (cambio) {
            cambio = false
            for (p in prefijos) {
                val n = t.replaceFirst(Regex("(?i)^$p"), "")
                if (n != t) { t = n.trimStart('¿', '¡', ',', ' '); cambio = true }
            }
        }
        t = t.replace(Regex("(?i)[,\\s]+(por favor|porfa)[?.!]*$"), "").trimEnd('?', '!', ' ')
        return t.ifBlank { texto }
    }

    private val rAccion = Regex("(llam|marc|telefon|mensaje|whatsapp|wasap|wsp|sms|escrib|envi|mand|respond|contest|reproduc|\\bpon\\b|ponme|video|cancion|musica|abre|abrir|abri|busca|alarma|recuerd|lleva|naveg|linterna|volumen|foto|camara|selfie|despierta|temporizador|avisa)")

    fun procesar(texto: String, cb: (Yarvis.Respuesta) -> Unit) {
        val limpio = limpiarOrden(texto)
        val f = Frase(limpio)
        val r = try {
            local(f, cb)
        } catch (e: Exception) {
            R("Tuve un inconveniente al ejecutar esa orden, $tr.", detalle = e.javaClass.simpleName)
        }
        when {
            r === ASYNC -> {}
            r != null -> cb(r)
            // Parece una orden que no reconocí: la IA la traduce a una orden que sí sé ejecutar
            rAccion.containsMatchIn(f.t) && CerebroIA.disponible() -> fondo(cb) {
                val canonica = try { CerebroIA.interpretar(limpio) } catch (_: Exception) { null }
                val r2 = if (canonica != null) try { local(Frase(limpiarOrden(canonica)), cb) } catch (_: Exception) { null } else null
                when {
                    r2 === ASYNC -> ASYNC
                    r2 != null -> r2
                    else -> {
                        val rr = try { CerebroIA.preguntar(limpio) } catch (e: Exception) { return@fondo R(CerebroIA.mensajeError(e), cerrar = true, detalle = CerebroIA.detalle(e)) }
                        R(rr, seguir = rr.trim().endsWith("?"))
                    }
                }
            }
            else -> preguntarIA(limpio, cb)
        }
    }

    private fun fondo(cb: (Yarvis.Respuesta) -> Unit, tarea: () -> Yarvis.Respuesta): Yarvis.Respuesta {
        Thread {
            val r = try { tarea() } catch (e: Exception) { R("No pude completar eso, $tr.") }
            if (r !== ASYNC) cb(r)
        }.start()
        return ASYNC
    }

    private fun preguntarIA(texto: String, cb: (Yarvis.Respuesta) -> Unit, extra: String? = null) {
        fondo(cb) {
            if (!CerebroIA.disponible()) {
                Wikipedia.consultar(texto)?.let { return@fondo R("Según Wikipedia: $it") }
                principal.post { buscarWeb(texto) }
                return@fondo R("No encontré una respuesta directa, $tr, así que le muestro lo que hay en internet.")
            }
            try {
                val r = CerebroIA.preguntar(texto, extra)
                R(r, seguir = r.trim().endsWith("?"))
            } catch (e: Exception) {
                // Respaldo sin límites: Wikipedia
                val wiki = if (CerebroIA.hayInternet()) Wikipedia.consultar(texto) else null
                if (wiki != null) R("Según Wikipedia: $wiki", detalle = "IA no disponible: " + CerebroIA.detalle(e))
                else R(CerebroIA.mensajeError(e), cerrar = true, detalle = CerebroIA.detalle(e))
            }
        }
    }

    // =====================================================================
    //  Órdenes
    // =====================================================================

    private fun local(f: Frase, cb: (Yarvis.Respuesta) -> Unit): Yarvis.Respuesta? {
        val t = f.t
        if (t.isBlank()) return R("No le escuché bien, $tr.")

        pendiente?.let { p ->
            pendiente = null
            if (System.currentTimeMillis() < pendienteHasta) {
                if (f.tiene("^(cancela|cancelar|olvidalo|nada|no|ninguno|dejalo)$")) return R("Cancelado, $tr.", cerrar = true)
                p(f)?.let { return it }
            }
        }

        // --- Control de YARVIS ---------------------------------------------
        if (f.tiene("(deja de escuchar|desactiva la escucha|no me escuches|desactiva la palabra|apaga la escucha)")) {
            Prefs.escuchaActiva = false
            ServicioEscucha.actualizar(ctx)
            return R("Entendido, $tr. Dejaré de escuchar la palabra de activación hasta que me lo indique.")
        }
        if (f.tiene("(activa la escucha|vuelve a escuchar|escucha siempre|activa la palabra)")) {
            Prefs.escuchaActiva = true
            ServicioEscucha.actualizar(ctx)
            return R("Escucha activada, $tr. Diga «${Prefs.palabraActivacion}» cuando me necesite.")
        }
        if (f.tiene("^(cancela|cancelar|olvidalo|olvida eso|nada|no nada|ninguna|dejalo|no importa)$")) {
            return R("Como desee, $tr.", cerrar = true)
        }
        if (f.tiene("^(adios|hasta luego|hasta manana|chau|chao|nos vemos|eso es todo|eso seria todo|nada mas|es todo|descansa|apagate|termina|terminar|terminamos|cierra la conversacion|fin de la conversacion|ya esta|listo eso es todo|puedes retirarte|retirate|ya no|ya no necesito nada|buenas noches yarvis)\\b") ||
            f.tiene("(eso es todo|eso seria todo|nada mas|es todo|ya no necesito nada|termina la conversacion|cierra la conversacion)$")) {
            return R(listOf("A su servicio, $tr.", "Estaré aquí cuando me necesite, $tr.", "Que tenga un excelente día, $tr.").random(), cerrar = true)
        }
        if (f.tiene("(que puedes hacer|que sabes hacer|^ayuda$|que comandos|lista de comandos|como te uso|para que sirves)")) {
            lanzar(Intent(ctx, ComandosActivity::class.java))
            return R("Puedo hacer llamadas, enviar mensajes por WhatsApp y SMS, leer sus notificaciones, programar alarmas, recordatorios y eventos, " +
                "decirle el clima, controlar la música, la linterna y el volumen, abrir aplicaciones, poner videos, tomar fotos " +
                "y responder cualquier pregunta. Le abrí la lista completa de órdenes, $tr.")
        }

        // --- Voz de YARVIS -----------------------------------------------------
        if (f.tiene("^(repite|repitelo|repitemelo|que dijiste|otra vez|puedes repetir|repite eso|no te entendi)")) {
            val u = Yarvis.ultimaRespuesta
            return if (u.isBlank()) R("Aún no le he dicho nada que repetir, $tr.") else R(u)
        }
        if (f.tiene("(habla|hablas|ve|lee|narra)( un poco| mas)? (mas )?(despacio|lento)|mas despacio|mas lento")) {
            Prefs.velocidad = (Prefs.velocidad - 0.12f).coerceAtLeast(0.7f); Voz.aplicarVoz()
            return R("Entendido, $tr. Hablaré más despacio.")
        }
        if (f.tiene("(habla|hablas|ve|lee|narra)( un poco)? (mas )?(rapido|deprisa)|mas rapido")) {
            Prefs.velocidad = (Prefs.velocidad + 0.12f).coerceAtMost(1.3f); Voz.aplicarVoz()
            return R("Entendido, $tr. Hablaré más rápido.")
        }
        if (f.tiene("(cambia|cambiar|usa|pon|quiero) (de |tu |la |una |otra )?voz")) {
            val mujer = f.tiene("(mujer|femenina|chica|dama)")
            val hombre = f.tiene("(hombre|masculina|varon|chico|caballero)")
            if ((mujer || hombre) && Prefs.claveIA.isNotBlank()) {
                val opciones = VozPremium.VOCES.filter { it.hombre == hombre }
                val sig = opciones.getOrNull((opciones.indexOfFirst { it.id == Prefs.vozPremium } + 1) % opciones.size) ?: opciones[0]
                Prefs.vozPremium = sig.id
                return R("Desde ahora hablaré con la voz de ${sig.nombre}, $tr. ¿Qué le parece?", seguir = true)
            }
            lanzar(Intent(ctx, AjustesActivity::class.java))
            return R("Le abrí la lista de voces, $tr. Toque una para escucharla.", cerrar = true)
        }

        // --- Conversación ----------------------------------------------------
        if (f.tiene("^(hola|hey|oye|buenas|buenos dias|buenas tardes|buenas noches|que tal|saludos)( yarvis)?$")) {
            return R("${saludo()}, $tr. ¿En qué puedo ayudarle?", seguir = true)
        }
        if (f.tiene("(quien eres|como te llamas|cual es tu nombre|presentate|que eres)")) {
            return R("Soy YARVIS, su asistente personal, desarrollado por VELKYRA. Estoy a su servicio las veinticuatro horas, $tr.")
        }
        if (f.tiene("(quien te (creo|hizo|desarrollo|programo|diseno|invento|fabrico)|quien es tu (creador|dueno|jefe|amo|programador|padre)|quien te dio vida|de quien eres|a quien perteneces|quien te construyo)")) {
            return if (Prefs.soyCreador) {
                val n = Prefs.nombre.ifBlank { "usted" }
                R(listOf(
                    "Usted me creó, $tr. Soy su creación, diseñada y programada por usted bajo su compañía VELKYRA. Y es un honor servirle.",
                    "Fue usted, $tr. ${if (n != "usted") "$n, " else ""}mi creador. Me dio vida a través de VELKYRA, y existo para ayudarle.",
                    "Mi creador es usted, $tr. Cada línea de mi código lleva su firma, a través de VELKYRA."
                ).random())
            } else R("Fui desarrollado por VELKYRA, $tr. Para soporte puede escribir a velkyra3 arroba gmail punto com.")
        }
        if (f.tiene("(como estas|como te encuentras|como te va|todo bien)")) {
            return R("Todos mis sistemas funcionan al cien por ciento, $tr. Gracias por preguntar.")
        }
        if (f.tiene("^(gracias|muchas gracias|excelente|buen trabajo|perfecto|genial|te pasaste)")) {
            return R(listOf("Para eso estoy, $tr. ¿Algo más?", "Siempre a su servicio, $tr. ¿Necesita algo más?", "Es un placer, $tr. ¿Le ayudo con algo más?").random())
        }
        if (f.tiene("(como me llamo|quien soy|sabes mi nombre)")) {
            return if (Prefs.nombre.isNotBlank()) R("Usted es ${Prefs.nombre}, $tr. Lo tengo muy presente.") else R("Aún no me ha dicho su nombre, $tr. Puede configurarlo en ajustes.")
        }
        Regex("^(?:a partir de ahora |desde ahora )?(?:llamame|dime|quiero que me llames|me llamo)\\s+(.+)$").find(t)?.let { m ->
            val nuevo = f.originalDeGrupo(m, 1).trim()
            if (t.startsWith("me llamo")) { Prefs.nombre = nuevo; if (Prefs.tratoGuardado.isBlank()) Prefs.tratoGuardado = nuevo }
            else Prefs.tratoGuardado = nuevo
            return R("Entendido. A partir de ahora le diré $nuevo.")
        }
        if (f.tiene("(chiste|hazme reir|algo gracioso|dime algo divertido)")) return R(chistes.random())
        if (f.tiene("(lanza|tira) una moneda|cara o sello|cara o cruz")) return R("Ha salido ${listOf("cara", "sello").random()}, $tr.")
        if (f.tiene("(lanza|tira) (un )?dado")) return R("Ha salido un ${(1..6).random()}, $tr.")
        Regex("numero (?:al azar|aleatorio)(?: (?:del|entre(?: el)?) (\\d+) (?:al|y(?: el)?) (\\d+))?").find(t)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: 1
            val b = m.groupValues[2].toIntOrNull() ?: 100
            return R("El número es ${(minOf(a, b)..maxOf(a, b)).random()}, $tr.")
        }

        // --- Alarmas y temporizadores ---------------------------------------
        if (f.tiene("(alarma|despiertame|levantame)") && !f.tiene("(quita|borra|elimina|cancela|apaga|mis alarmas)")) {
            val m = Tiempo.analizar(t) ?: return R("¿A qué hora pongo la alarma, $tr?", seguir = true)
            val c = m.cal
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, c.get(Calendar.HOUR_OF_DAY))
                .putExtra(AlarmClock.EXTRA_MINUTES, c.get(Calendar.MINUTE))
                .putExtra(AlarmClock.EXTRA_MESSAGE, "YARVIS")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (m.conDia && Tiempo.diasEntre(Calendar.getInstance(), c) > 1) {
                i.putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(c.get(Calendar.DAY_OF_WEEK)))
            }
            return if (lanzar(i)) R("Alarma programada para ${Tiempo.describir(c)}, $tr.") else R("No encontré una aplicación de reloj, $tr.")
        }
        if (f.tiene("(mis alarmas|que alarmas tengo|ver alarmas)")) {
            lanzar(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            return R("Aquí tiene sus alarmas, $tr.")
        }
        if (f.tiene("(temporizador|cronometro|cuenta regresiva|avisame en|timer)")) {
            val d = Tiempo.duracion(t) ?: return R("¿De cuánto tiempo, $tr?", seguir = true)
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, d.first)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "YARVIS")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            return if (lanzar(i)) R("Temporizador de ${duracionTexto(d.first)} en marcha, $tr.") else R("No encontré una aplicación de reloj, $tr.")
        }

        // --- Recordatorios ---------------------------------------------------
        if (f.tiene("(mis recordatorios|que recordatorios|recordatorios pendientes|que me tienes que recordar)")) {
            val l = Recordatorios.lista()
            if (l.isEmpty()) return R("No tiene recordatorios pendientes, $tr.")
            val txt = l.take(5).joinToString(". ") { "${it.texto}, ${Tiempo.describir(Calendar.getInstance().apply { timeInMillis = it.cuando })}" }
            return R("Tiene ${l.size} recordatorio${if (l.size == 1) "" else "s"}: $txt.")
        }
        if (f.tiene("(borra|elimina|cancela|quita) (todos )?(los |mis )?recordatorios")) {
            Recordatorios.borrarTodos(ctx)
            return R("He eliminado todos sus recordatorios, $tr.")
        }
        Regex("^(?:recuerdame|recordarme|recuerda(?:me)?|avisame|haz(?:me)? acuerdo|pon(?:me)? un recordatorio(?: para)?)\\b\\s*(.*)$").find(t)?.let { m ->
            val resto = m.groupValues[1]
            val momento = Tiempo.analizar(t)
            if (momento == null || !(momento.conHora || momento.conDia)) {
                if (Regex("^que\\s").containsMatchIn(resto) || resto.isNotBlank()) {
                    // Sin hora: se guarda como nota
                    val nota = f.originalEntre(m.groups[1]!!.range.first).replace(Regex("^(?i)que\\s+"), "")
                    guardarNota(nota)
                    return R("Anotado, $tr: $nota. Si desea que se lo recuerde a una hora, dígame cuándo.")
                }
                return R("¿Qué desea que le recuerde y cuándo, $tr?", seguir = true)
            }
            val rangos = momento.rangos + listOf(m.groups[0]!!.range.first until (m.groups[1]?.range?.first ?: 0))
            var tarea = f.originalSin(rangos).trim()
                .replace(Regex("^(?i)(que|de|para)\\s+"), "").trim()
            if (tarea.isBlank()) tarea = "lo que me pidió"
            Recordatorios.crear(ctx, momento.cal.timeInMillis, tarea)
            return R("Hecho, $tr. Le recordaré «$tarea» ${Tiempo.describir(momento.cal)}.")
        }

        // --- Agenda ------------------------------------------------------------
        if (f.tiene("(que tengo|mi agenda|mis eventos|mis citas|tengo algo|que hay en mi (agenda|calendario))")) {
            val dia = if (f.tiene("pasado manana")) 2 else if (f.tiene("manana")) 1 else 0
            val ev = Agenda.eventos(ctx, dia) ?: return R("Necesito permiso para ver su calendario, $tr.")
            val cuando = listOf("hoy", "mañana", "pasado mañana")[dia]
            val rec = Recordatorios.lista().filter {
                Tiempo.diasEntre(Calendar.getInstance(), Calendar.getInstance().apply { timeInMillis = it.cuando }) == dia
            }.map { "recordatorio: ${it.texto}" }
            val todo = ev + rec
            return if (todo.isEmpty()) R("No tiene nada programado para $cuando, $tr.")
            else R("Para $cuando tiene: ${todo.joinToString(". ")}.")
        }
        Regex("^(?:agenda(?:me)?|agendar|anota en (?:mi|el) calendario|crea(?:me)? un evento|programa(?:me)? una (?:reunion|cita)|pon en mi (?:agenda|calendario))\\s+(.*)$").find(t)?.let { m ->
            val momento = Tiempo.analizar(t) ?: return R("¿Para qué día y hora lo agendo, $tr?", seguir = true)
            var titulo = f.originalSin(momento.rangos + listOf(0 until m.groups[1]!!.range.first)).trim()
                .replace(Regex("^(?i)(un|una|el|la|para|de)\\s+"), "").replaceFirstChar { it.uppercase() }
            if (titulo.isBlank()) titulo = "Evento"
            return if (Agenda.crear(ctx, titulo, momento.cal)) R("Agendado, $tr: $titulo, ${Tiempo.describir(momento.cal)}.")
            else {
                lanzar(Intent(Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI)
                    .putExtra(android.provider.CalendarContract.Events.TITLE, titulo)
                    .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, momento.cal.timeInMillis))
                R("Le abrí el calendario para confirmar el evento, $tr.")
            }
        }

        // --- Notas -----------------------------------------------------------
        if (f.tiene("(borra|elimina|limpia) (todas )?(las |mis )?notas")) {
            Prefs.notas = ""
            return R("He borrado todas sus notas, $tr.")
        }
        if (f.tiene("(mis notas|lee (las|mis) notas|que (te )?(pedi|dije) que (anotaras|recordaras)|que anotaste|que tengo anotado)")) {
            val n = Prefs.notas.lines().filter { it.isNotBlank() }
            return if (n.isEmpty()) R("No tiene notas guardadas, $tr.") else R("Tiene ${n.size} nota${if (n.size == 1) "" else "s"}: ${n.joinToString(". ")}.")
        }
        Regex("^(?:anota|apunta|toma nota|guarda una nota|escribe una nota|nota)(?: de)?(?: que)?\\s+(.+)$").find(t)?.let { m ->
            val nota = f.originalDeGrupo(m, 1)
            guardarNota(nota)
            return R("Anotado, $tr.")
        }

        // --- Mensajes: WhatsApp y SMS ----------------------------------------
        if (f.tiene("(lee|leeme|que dicen|tengo|revisa) (mis |los |algun |algunos )?(mensajes|notificaciones|whatsapps?)|mensajes nuevos|quien me escribio|alguien me escribio")) {
            return leerNotificaciones()
        }
        responderMensaje(f)?.let { return it }
        mensaje(f)?.let { return it }

        // --- Llamadas ----------------------------------------------------------
        Regex("^(?:llama|llamar|llames|llamale|llamarle|marca|marcale|marcar|marques|telefonea|comunicame con|contacta a|hazme una llamada|haz una llamada|has una llamada|realiza una llamada|realizar una llamada|hacer una llamada|haz llamada|una llamada)(?:\\s+telefonica)?\\s+(?:por\\s+(telefono|celular|whatsapp|wasap|wsp|guasap)\\s+)?(?:a\\s+|al\\s+|a la\\s+|con\\s+|para\\s+)?(.+?)(?:\\s+por\\s+(telefono|celular|whatsapp|wasap|wsp|guasap))?$").find(t)?.let { m ->
            val porWa = (m.groupValues[1] + m.groupValues[3]).let { it.contains("wa") || it.contains("wsp") || it.contains("whats") }
            val quien = f.originalDeGrupo(m, 2)
            return if (porWa) llamarWhatsapp(quien) else llamar(quien)
        }

        // --- Hora, fecha y cálculos de días ----------------------------------
        if (f.tiene("(que hora|la hora|hora es|horas son)") || t == "hora") {
            return R("Son las ${SimpleDateFormat("h:mm a", es).format(Date())}, $tr.")
        }
        if (f.tiene("(que dia|que fecha|la fecha|dia es hoy|a cuantos estamos)")) {
            return R("Hoy es ${SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", es).format(Date())}, $tr.")
        }
        Regex("cuantos dias faltan para (?:el |la )?(.+)$").find(t)?.let { m ->
            val obj = m.groupValues[1]
            val hoy = Calendar.getInstance()
            val fecha = when {
                obj.contains("navidad") -> Calendar.getInstance().apply { set(Calendar.MONTH, Calendar.DECEMBER); set(Calendar.DAY_OF_MONTH, 25) }
                obj.contains("ano nuevo") -> Calendar.getInstance().apply { add(Calendar.YEAR, 1); set(Calendar.MONTH, Calendar.JANUARY); set(Calendar.DAY_OF_MONTH, 1) }
                obj.contains("fiestas patrias") -> Calendar.getInstance().apply { set(Calendar.MONTH, Calendar.JULY); set(Calendar.DAY_OF_MONTH, 28) }
                else -> Tiempo.analizar(obj)?.cal
            } ?: return null
            if (Tiempo.diasEntre(hoy, fecha) < 0) fecha.add(Calendar.YEAR, 1)
            val d = Tiempo.diasEntre(hoy, fecha)
            return R(if (d == 0) "¡Es hoy, $tr!" else "Faltan $d día${if (d == 1) "" else "s"}, $tr.")
        }

        // --- Clima -----------------------------------------------------------
        if (f.tiene("(clima|que tiempo hace|temperatura|va a llover|llovera|hace frio|hace calor|pronostico)")) {
            val ciudad = Regex("(?:en|de|para)\\s+([a-z ]{3,})$").find(t)?.groupValues?.get(1)
                ?.replace(Regex("\\b(hoy|manana|pasado manana|ahora)\\b"), "")?.trim()
                ?.takeIf { it.isNotBlank() && it !in listOf("la ciudad", "mi ciudad", "aqui", "mi zona") }
            val dia = if (f.tiene("pasado manana")) 2 else if (f.tiene("\\bmanana\\b")) 1 else 0
            return fondo(cb) { R(Clima.informe(ctx, ciudad, dia)) }
        }
        if (f.tiene("(donde estoy|mi ubicacion|en que lugar estoy|en que ciudad estoy)")) {
            return fondo(cb) {
                val l = Clima.ubicacion(ctx) ?: return@fondo R("No pude obtener su ubicación, $tr. Verifique que el GPS esté activado.")
                R("Se encuentra en ${Clima.nombreDe(ctx, l.latitude, l.longitude)}, $tr.")
            }
        }

        // --- Noticias ------------------------------------------------------------
        if (f.tiene("(noticias|titulares|que hay de nuevo|que paso hoy en|novedades de|que se sabe de)") && !f.tiene("(mis mensajes|notificaciones)")) {
            val tema = Regex("(?:noticias|titulares|novedades|que hay de nuevo|que paso hoy|que se sabe)\\s+(?:de|del|sobre|en|acerca de)\\s+(?:la |el |los |las )?(.+)$").find(t)
                ?.let { f.originalDeGrupo(it, 1) }
                ?.takeIf { !Regex("^(hoy|ahora|del dia|de hoy|ultima hora)$").containsMatchIn(Frase.normalizar(it)) }
            return fondo(cb) {
                val inf = Noticias.informe(tema)
                CerebroIA.recordar(f.original, inf)
                R(inf, seguir = true)
            }
        }

        // --- Información del teléfono -------------------------------------------
        InfoTelefono.responder(ctx, f)?.let { return R(it) }

        // --- Linterna, batería, volumen, brillo, sonido ----------------------
        if (f.tiene("(linterna|flash)") || f.tiene("(prende|enciende|apaga) (la )?luz")) {
            val on = !f.tiene("(apaga|desactiva|quita)")
            return if (linterna(on)) R(if (on) "Linterna encendida, $tr." else "Linterna apagada, $tr.") else R("Este equipo no tiene flash disponible, $tr.")
        }
        if (f.tiene("(bateria|carga del (celular|telefono)|cuanta carga)")) {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val n = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val c = bm.isCharging
            var r = "La batería está al $n por ciento" + if (c) " y cargando, $tr." else ", $tr."
            if (n <= 15 && !c) r += " Le sugiero conectarlo pronto."
            return R(r)
        }
        if (f.tiene("(volumen|silencia|sonido)") && !f.tiene("modo")) return volumen(f)
        if (f.tiene("modo (silencio|silencioso|vibracion|vibrar|normal|sonido)|pon(lo)? en (silencio|vibracion)")) return modoSonido(f)
        if (f.tiene("no molestar")) return noMolestar(!f.tiene("(desactiva|quita|apaga)"))
        if (f.tiene("brillo")) return brillo(f)

        // --- Conectividad ----------------------------------------------------
        if (f.tiene("\\b(wifi|wi fi|wi-fi)\\b")) { lanzar(Intent(Settings.Panel.ACTION_WIFI)); return R("Aquí tiene el panel de wifi, $tr.") }
        if (f.tiene("(datos moviles|internet movil|los datos)")) { lanzar(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)); return R("Aquí tiene la conexión a internet, $tr.") }
        if (f.tiene("bluetooth")) { lanzar(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return R("Aquí tiene el bluetooth, $tr.") }
        if (f.tiene("(gps|ubicacion)") && f.tiene("(activa|desactiva|prende|apaga|enciende)")) { lanzar(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return R("Aquí tiene la ubicación, $tr.") }
        if (f.tiene("modo avion")) { lanzar(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)); return R("Aquí puede cambiar el modo avión, $tr.") }

        // --- Pantalla (accesibilidad) ------------------------------------------
        if (f.tiene("(bloquea|apaga) (el celular|la pantalla|el telefono)|bloquear (el celular|pantalla)")) {
            return global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Bloqueando, $tr.")
        }
        if (f.tiene("(captura de pantalla|pantallazo|screenshot|toma (una )?captura)")) {
            VentanaFlotante.ocultar()
            return R("Capturando la pantalla, $tr.") {
                VentanaFlotante.ocultar()
                principal.postDelayed({ ServicioAccesibilidad.accion(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 700)
            }
        }
        if (f.tiene("^(ve al inicio|pantalla de inicio|ir al inicio|inicio|al inicio|home)$")) return global(AccessibilityService.GLOBAL_ACTION_HOME, "Listo, $tr.")
        if (f.tiene("^(atras|regresa|vuelve atras|retrocede)$")) return global(AccessibilityService.GLOBAL_ACTION_BACK, "Listo, $tr.")
        if (f.tiene("(apps recientes|aplicaciones recientes|multitarea)")) return global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Aquí están, $tr.")
        if (f.tiene("(abre|muestra|baja) (las )?notificaciones")) return global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Aquí están sus notificaciones, $tr.")
        if (f.tiene("(ajustes|configuracion) rapid")) return global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Listo, $tr.")
        if (f.tiene("pantalla dividida")) return global(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN, "Pantalla dividida, $tr.")

        // --- Música ------------------------------------------------------------
        if (f.tiene("^(pausa|pausar|deten|detener|para)( la)?( musica| cancion| video| reproduccion)?$") || f.tiene("(pausa|deten|para) la (musica|cancion)")) {
            return tecla(KeyEvent.KEYCODE_MEDIA_PAUSE, "En pausa, $tr.")
        }
        if (f.tiene("^(continua|reanuda|sigue|play|dale play)( la)?( musica| cancion| reproduccion)?$")) return tecla(KeyEvent.KEYCODE_MEDIA_PLAY, "Reanudando, $tr.")
        if (f.tiene("(siguiente|pasa la|salta la|otra) cancion|^siguiente$|cambia de cancion")) return tecla(KeyEvent.KEYCODE_MEDIA_NEXT, "Siguiente canción, $tr.")
        if (f.tiene("cancion anterior|^anterior$|regresa la cancion")) return tecla(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Canción anterior, $tr.")
        if (f.tiene("^(pon|reproduce|toca) (algo de )?musica$")) return tecla(KeyEvent.KEYCODE_MEDIA_PLAY, "Reproduciendo música, $tr.")

        // --- Cámara --------------------------------------------------------------
        if (f.tiene("(selfie|selfi|camara frontal|autofoto)")) {
            val i = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .putExtra("android.intent.extras.CAMERA_FACING", 1)
                .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            return if (lanzar(i)) R("Cámara frontal lista. Sonría, $tr.") else R("No encontré la cámara, $tr.")
        }
        if (f.tiene("(toma|tomame|saca|sacame) (una )?foto|abre la camara|^camara$")) {
            return if (lanzar(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) R("Cámara lista, $tr.") else R("No encontré la cámara, $tr.")
        }
        if (f.tiene("graba (un )?video|grabar video")) {
            return if (lanzar(Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA))) R("Cámara de video lista, $tr.") else R("No encontré la cámara, $tr.")
        }
        if (f.tiene("(mis fotos|la galeria|abre galeria)")) {
            lanzar(Intent(Intent.ACTION_VIEW).setType("image/*"))
            return R("Aquí tiene sus fotos, $tr.")
        }

        // --- Mapas -------------------------------------------------------------
        Regex("(?:como llego a|como llegar a|llevame a|navega a|navegar a|ruta (?:a|hacia|para)|direcciones (?:a|para)|vamos a)\\s+(.+)$").find(t)?.let { m ->
            val lugar = f.originalDeGrupo(m, 1)
            if (!lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(lugar)))))
                abrirUrl("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(lugar))
            return R("Trazando la ruta hacia $lugar, $tr.")
        }
        Regex("(?:donde queda|donde esta|ubica|muestrame en el mapa)\\s+(.+)$").find(t)?.let { m ->
            val lugar = f.originalDeGrupo(m, 1)
            if (!lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(lugar)))))
                abrirUrl("https://www.google.com/maps/search/" + Uri.encode(lugar))
            return R("Le muestro $lugar en el mapa, $tr.")
        }
        Regex("(.+?)\\s+(?:cerca|cercanos|cercanas|cerca de aqui|cerca de mi)$").find(t)?.let { m ->
            val q = f.originalDeGrupo(m, 1).replace(Regex("^(?i)(busca|buscame|hay|donde hay|muestrame)\\s+"), "")
            lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
            return R("Buscando $q cerca de usted, $tr.")
        }

        // --- Videos, música y sitios -------------------------------------------
        sitios(f, cb)?.let { return it }

        // --- Abrir aplicaciones y páginas --------------------------------------
        Regex("^(?:abre|abrir|abreme|inicia|ejecuta|lanza|entra a|entra en|ingresa a|ve a)\\s+(?:la app(?:licacion)?\\s+(?:de\\s+)?|el\\s+|la\\s+|los\\s+|las\\s+|mi\\s+)?(?:pagina\\s+(?:de\\s+|web\\s+)?|sitio\\s+(?:web\\s+)?|web\\s+)?(.+)$").find(t)?.let { m ->
            val nombre = m.groupValues[1].trim()
            if (t.contains("pagina") || t.contains("sitio") || t.contains("web") || Regex("(punto|\\.)(com|pe|org|net|edu|gob)").containsMatchIn(nombre)) {
                return abrirPagina(nombre)
            }
            return abrirApp(nombre)
        }

        // --- Calculadora ---------------------------------------------------------
        val calc = Regex("(-?\\d+(?:[.,]\\d+)?)\\s*(\\+|mas|menos|-|x|\\*|×|por|/|dividido entre|dividido por|dividido|entre|elevado a(?:l)?)\\s*(-?\\d+(?:[.,]\\d+)?)").find(t)
        if (calc != null && !f.tiene("^(llama|marca|manda|envia)")) {
            val a = calc.groupValues[1].replace(',', '.').toDouble()
            val b = calc.groupValues[3].replace(',', '.').toDouble()
            val r = when (calc.groupValues[2]) {
                "+", "mas" -> a + b
                "-", "menos" -> a - b
                "x", "*", "×", "por" -> a * b
                "elevado a", "elevado al" -> Math.pow(a, b)
                else -> { if (b == 0.0) return R("No se puede dividir entre cero, $tr."); a / b }
            }
            return R("El resultado es ${numero(r)}, $tr.")
        }
        Regex("(?:el )?(\\d+(?:[.,]\\d+)?)\\s*(?:%|por ciento) de (\\d+(?:[.,]\\d+)?)").find(t)?.let { m ->
            val r = m.groupValues[1].replace(',', '.').toDouble() * m.groupValues[2].replace(',', '.').toDouble() / 100
            return R("Es ${numero(r)}, $tr.")
        }
        Regex("raiz cuadrada de (\\d+(?:[.,]\\d+)?)").find(t)?.let { m ->
            return R("Es ${numero(Math.sqrt(m.groupValues[1].replace(',', '.').toDouble()))}, $tr.")
        }

        // --- Wikipedia (sin límite diario) -----------------------------------------
        Regex("(?:que dice wikipedia (?:de|sobre)|busca en wikipedia|en wikipedia|wikipedia)\\s+(.+)$").find(t)?.let { m ->
            val q = f.originalDeGrupo(m, 1)
            return fondo(cb) { Wikipedia.consultar(q)?.let { R("Según Wikipedia: $it") } ?: R("No encontré «$q» en Wikipedia, $tr.") }
        }

        // --- Búsquedas ----------------------------------------------------------
        Regex("^(?:busca|buscame|buscar|googlea)\\s+en\\s+(?:google|internet|la web)\\s+(.+)$").find(t)?.let { m ->
            val q = f.originalDeGrupo(m, 1)
            buscarWeb(q)
            return R("Buscando $q en Google, $tr.")
        }
        Regex("^(?:busca|buscame|buscar|googlea|investiga)\\s+(?:sobre\\s+|informacion (?:de|sobre)\\s+)?(.+)$").find(t)?.let { m ->
            val q = f.originalDeGrupo(m, 1)
            if (!CerebroIA.disponible()) { buscarWeb(q); return R("Buscando $q en internet, $tr.") }
            preguntarIA("Busca en internet y resume brevemente: $q", cb)
            return ASYNC
        }
        Regex("^(?:traduce|traducir|como se dice)\\s+(.+)$").find(t)?.let {
            if (!CerebroIA.disponible()) {
                abrirUrl("https://translate.google.com/?sl=auto&tl=en&text=" + Uri.encode(f.original))
                return R("Le abrí el traductor, $tr.")
            }
            preguntarIA(f.original, cb, "Si te piden traducir, responde la traducción y cómo se pronuncia de forma sencilla.")
            return ASYNC
        }

        return null
    }

    // =====================================================================
    //  Acciones
    // =====================================================================

    private val rCanal = Regex("\\s*\\b(?:por|en|via|de|al)?\\s*(?:whatsapp|whats app|wasap|wasa|guasap|wsp|sms|mensaje de texto|mensajito de texto)\\b")
    private val rSeparador = Regex("(?:\\s+|:\\s*)(?:y\\s+)?(?:que|diciendo(?:le)?(?: que)?|dile que|con el mensaje|el mensaje|con el texto|lo siguiente|esto)(?:\\s+|:\\s*)|:\\s+")

    /** Entiende órdenes de mensajes dichas de muchas formas. */
    private fun mensaje(f: Frase): Yarvis.Respuesta? {
        val t = f.t
        val intencion = Regex("^(?:por favor\\s+)?(?:quiero\\s+|puedes\\s+|podrias\\s+)?(manda(?:le|me|r)?|envia(?:le|r)?|escribe(?:le)?|escribir(?:le)?|dile|avisale|mensaje|whatsapp|wasap|wsp)\\b").find(t) ?: return null
        val verbo = intencion.groupValues[1]
        val esMensaje = rCanal.containsMatchIn(t) || t.contains("mensaje") || verbo.startsWith("dile") || verbo.startsWith("avisale") || verbo.startsWith("escribe")
        if (!esMensaje) return null
        val porSms = Regex("(sms|mensaje de texto|mensajito de texto)").containsMatchIn(t)

        // Destinatario: lo que sigue a "a / al / para"
        val desde = intencion.range.last + 1
        val marca = Regex("\\b(?:a|al|a la|para|pa)\\s+(?!las?\\s+\\d)").find(t, desde)
        if (marca == null) return pedirDestinatario(porSms, null)
        val inicio = marca.range.last + 1

        var quien: String
        var texto = ""
        val sep = rSeparador.find(t, inicio)
        if (sep != null && sep.range.first > inicio) {
            quien = f.originalEntre(inicio, sep.range.first)
            texto = f.originalEntre(sep.range.last + 1)
        } else {
            // Sin "que": se busca el contacto con las primeras palabras y el resto es el mensaje
            val palabras = Regex("\\S+").findAll(t, inicio).toList()
            quien = ""
            for (k in minOf(4, palabras.size) downTo 1) {
                val candRango = palabras[0].range.first until palabras[k - 1].range.last + 1
                val cand = rCanal.replace(t.substring(candRango.first, candRango.last + 1), "").trim()
                if (cand.isNotBlank() && Contactos.buscar(ctx, cand, estricto = true) != null) {
                    quien = f.originalEntre(candRango.first, candRango.last + 1)
                    if (k < palabras.size) texto = f.originalEntre(palabras[k].range.first)
                    break
                }
            }
            if (quien.isBlank()) {
                if (palabras.size <= 3) quien = f.originalEntre(inicio) else {
                    quien = f.originalEntre(palabras[0].range.first, palabras[0].range.last + 1)
                    texto = f.originalEntre(palabras[1].range.first)
                }
            }
        }
        quien = quitarCanal(quien)
        texto = quitarCanal(texto).trim()

        val c = resolverContacto(quien) ?: return if (!ctx.tiene(Manifest.permission.READ_CONTACTS))
            R("Necesito permiso para ver sus contactos, $tr.")
        else pedirDestinatario(porSms, texto.ifBlank { null }, "No encontré a $quien en sus contactos, $tr. ¿A quién se lo envío?")
        if (texto.isBlank()) return pedirTexto(c, porSms)
        return enviarMensaje(c, texto, porSms)
    }

    /** «Responde a Juan que ya voy», «respóndele que sí», «contesta el mensaje de Rosa diciendo que mañana». */
    private fun responderMensaje(f: Frase): Yarvis.Respuesta? {
        val t = f.t
        val m = Regex("^(?:responde|respondele|responder|contesta|contestale|contestar|respondele a|contestale a)\\b\\s*(.*)$").find(t) ?: return null
        var resto = m.groupValues[1]
        val inicio = m.groups[1]!!.range.first
        var quien: String? = null
        var texto: String
        // ¿Dice a quién? «a Juan / al mensaje de Juan / el whatsapp de Juan»
        val dest = Regex("^(?:(?:a|al)\\s+)?(?:(?:el|al|ultimo)\\s+)?(?:mensaje|whatsapp|wasap|chat)?\\s*(?:de|a|al)\\s+(.+?)\\s+(?:que|diciendo(?:le)?(?: que)?|con)\\s+(.+)$").find(resto)
            ?: Regex("^(?:a|al)\\s+(.+?)\\s+(?:que|diciendo(?:le)?(?: que)?|con)\\s+(.+)$").find(resto)
        if (dest != null) {
            quien = f.originalEntre(inicio + dest.groups[1]!!.range.first, inicio + dest.groups[1]!!.range.last + 1)
            texto = f.originalEntre(inicio + dest.groups[2]!!.range.first)
        } else {
            resto = resto.replace(Regex("^(?:(?:el|al|ultimo)\\s+)?(?:mensaje|whatsapp|wasap)\\s*"), "")
            val q = Regex("^(?:que|diciendo(?:le)?(?: que)?|con)\\s+").find(resto)
            texto = if (resto.isBlank()) "" else f.originalEntre(t.length - resto.length + (q?.range?.last?.plus(1) ?: 0))
        }
        texto = texto.trim().trim('"', '«', '»')
        if (!ServicioNotificaciones.activo) {
            lanzar(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return R("Para responder mensajes necesito acceso a sus notificaciones, $tr. Active YARVIS en la pantalla que le abrí.", cerrar = true)
        }
        val destino = quien ?: ServicioNotificaciones.ultimoRemitente()
            ?: return R("No tengo mensajes recientes para responder, $tr. Puedo enviarle uno nuevo: diga, por ejemplo, manda un WhatsApp a Juan.")
        if (texto.isBlank()) return esperar("¿Qué le respondo a $destino, $tr?") { f2 -> enviarRespuesta(destino, f2.original) }
        return enviarRespuesta(destino, texto)
    }

    private fun enviarRespuesta(quien: String, texto: String): Yarvis.Respuesta {
        val limpio = texto.trim().replace(Regex("(?i)^(dile\\s+)?que\\s+"), "").replaceFirstChar { it.uppercase() }
        val nombre = ServicioNotificaciones.responder(ctx, quien, limpio)
        if (nombre != null) return R("Respondido a $nombre: «$limpio».")
        // No hay notificación para responder: se envía como mensaje nuevo por WhatsApp
        val c = resolverContacto(quien) ?: return R("No encontré un mensaje reciente de $quien ni el contacto en su agenda, $tr.")
        return enviarMensaje(c, limpio, false)
    }

    private fun llamarWhatsapp(quien: String): Yarvis.Respuesta {
        val c = resolverContacto(quien) ?: return if (!ctx.tiene(Manifest.permission.READ_CONTACTS))
            R("Necesito permiso para ver sus contactos, $tr.") else R("No encontré a $quien en sus contactos, $tr.")
        if (!ServicioAccesibilidad.activo) {
            return R("Para llamar por WhatsApp sin tocar la pantalla necesito el permiso de accesibilidad, $tr. Mientras tanto le hago una llamada normal.") {
                lanzar(Intent(if (ctx.tiene(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.numero))))
            }
        }
        ServicioAccesibilidad.prepararLlamadaWhatsapp()
        val url = "https://api.whatsapp.com/send?phone=${Contactos.internacional(c.numero)}"
        val ok = listOf("com.whatsapp", "com.whatsapp.w4b").any { pkg -> lanzar(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)) }
        return if (ok) R("Llamando a ${c.nombre} por WhatsApp, $tr.", cerrar = true) else R("No encontré WhatsApp en este celular, $tr.")
    }

    private fun quitarCanal(s: String): String {
        var r = s.trim()
        r = r.replace(Regex("(?i)^(por|en|via|al)?\\s*(whatsapp|whats app|wasap|wasa|guasap|wsp|sms|mensaje de texto)\\s+"), "")
        r = r.replace(Regex("(?i)\\s+(por|en|via|al)?\\s*(whatsapp|whats app|wasap|wasa|guasap|wsp|sms|mensaje de texto)$"), "")
        r = r.replace(Regex("(?i)^(que|diciendo(le)?( que)?|dile que)\\s+"), "")
        return r.trim().trim(':', ',', '.').trim()
    }

    private fun resolverContacto(quien: String): Contactos.Contacto? {
        if (quien.isBlank()) return null
        Contactos.buscar(ctx, quien)?.let { return it }
        val dig = quien.filter { it.isDigit() || it == '+' }
        if (dig.count { it.isDigit() } >= 6 && quien.count { it.isLetter() } <= 2) return Contactos.Contacto(dig.chunked(3).joinToString(" "), dig)
        return null
    }

    private fun pedirDestinatario(sms: Boolean, texto: String?, pregunta: String = "¿A quién le envío el mensaje, $tr?"): Yarvis.Respuesta =
        esperar(pregunta) { f2 ->
            val q = quitarCanal(f2.original.replace(Regex("(?i)^(a|al|a la|para)\\s+"), ""))
            val c = resolverContacto(q)
            when {
                c == null -> pedirDestinatario(sms, texto, "No encontré a $q en sus contactos, $tr. ¿A quién se lo envío?")
                texto.isNullOrBlank() -> pedirTexto(c, sms)
                else -> enviarMensaje(c, texto, sms)
            }
        }

    private fun pedirTexto(c: Contactos.Contacto, sms: Boolean): Yarvis.Respuesta =
        esperar("¿Qué le digo a ${c.nombre}, $tr?") { f2 ->
            val texto = f2.original.trim().replace(Regex("(?i)^(dile\\s+)?que\\s+"), "")
            if (texto.isBlank()) pedirTexto(c, sms) else enviarMensaje(c, texto, sms)
        }

    private fun enviarMensaje(c: Contactos.Contacto, textoCrudo: String, porSms: Boolean): Yarvis.Respuesta {
        val texto = textoCrudo.trim().replaceFirstChar { it.uppercase() }
        val numero = c.numero
        val nombre = c.nombre
        if (porSms) {
            if (!ctx.tiene(Manifest.permission.SEND_SMS)) return R("Necesito permiso para enviar mensajes de texto, $tr.")
            return try {
                val sms = ctx.getSystemService(SmsManager::class.java)
                sms.sendMultipartTextMessage(numero, null, sms.divideMessage(texto), null, null)
                R("Mensaje de texto enviado a $nombre, $tr.")
            } catch (e: Exception) {
                R("No pude enviar el mensaje de texto, $tr.")
            }
        }
        val url = "https://api.whatsapp.com/send?phone=${Contactos.internacional(numero)}&text=" + Uri.encode(texto)
        val auto = Prefs.whatsappAutomatico && ServicioAccesibilidad.activo
        if (auto) ServicioAccesibilidad.prepararEnvio()
        val abierto = listOf("com.whatsapp", "com.whatsapp.w4b", null).any { pkg ->
            lanzar(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { if (pkg != null) setPackage(pkg) })
        }
        if (!abierto) return R("No encontré WhatsApp en este celular, $tr.")
        return if (auto) R("Enviando a $nombre por WhatsApp: «$texto».")
        else R("Le dejé el mensaje listo para $nombre. Solo toque enviar, $tr. Si activa la accesibilidad de YARVIS, lo enviaré yo mismo.")
    }

    private fun leerNotificaciones(): Yarvis.Respuesta {
        if (!ServicioNotificaciones.activo) {
            lanzar(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return R("Necesito acceso a sus notificaciones para leerlas, $tr. Active YARVIS en la pantalla que le abrí.")
        }
        val l = ServicioNotificaciones.recientes()
        if (l.isEmpty()) return R("No tiene mensajes nuevos, $tr.")
        val txt = l.takeLast(6).reversed().joinToString(". ") { "${it.titulo} por ${it.app}: ${it.texto}" }
        ServicioNotificaciones.marcarLeidas()
        return R("Tiene ${l.size} mensaje${if (l.size == 1) "" else "s"}. $txt.")
    }

    private fun llamar(quien: String): Yarvis.Respuesta {
        val digitos = quien.filter { it.isDigit() || it == '+' }
        val soloNumero = digitos.count { it.isDigit() } >= 3 && quien.count { it.isLetter() } <= 2
        val numero: String
        val nombre: String
        if (soloNumero) {
            numero = digitos
            nombre = digitos.chunked(3).joinToString(" ")
        } else {
            if (!ctx.tiene(Manifest.permission.READ_CONTACTS)) return R("Necesito permiso para ver sus contactos, $tr.")
            val c = Contactos.buscar(ctx, quien) ?: return R("No encontré a $quien en sus contactos, $tr.")
            numero = c.numero
            nombre = c.nombre
        }
        val accion = if (ctx.tiene(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val i = Intent(accion, Uri.parse("tel:" + Uri.encode(numero)))
        return R("Llamando a $nombre, $tr.") { lanzar(i) }
    }

    private fun sitios(f: Frase, cb: (Yarvis.Respuesta) -> Unit): Yarvis.Respuesta? {
        val t = f.t
        val sitio = "(youtube|you tube|tiktok|tik tok|facebook|spotify|instagram|google|netflix|wikipedia|mercado libre|amazon|twitter|x)"
        val m = Regex("^(?:pon(?:me)?|reproduce|reproducir|toca|busca(?:me)?|buscar|quiero ver|quiero escuchar|muestrame|abre|ver|mira|escuchar)\\s+(.+?)\\s+(?:en|de)\\s+$sitio$").find(t)
        if (m != null) {
            val q = f.originalDeGrupo(m, 1).replace(Regex("^(?i)(el|la|los|las|un|una)\\s+(video|cancion|tema)\\s+(de\\s+)?"), "")
            return when (m.groupValues[2]) {
                "youtube", "you tube" -> youtube(q, cb)
                "tiktok", "tik tok" -> { abrirUrl("https://www.tiktok.com/search?q=" + Uri.encode(q)); R("Buscando $q en TikTok, $tr.") }
                "facebook" -> {
                    if (ServicioAccesibilidad.activo) ServicioAccesibilidad.prepararReproduccion()
                    abrirUrl("https://www.facebook.com/search/videos/?q=" + Uri.encode(q))
                    R("Le muestro los videos de $q en Facebook, $tr. Toque el que desee y lo reproduzco.", cerrar = true)
                }
                "instagram" -> { abrirUrl("https://www.instagram.com/explore/search/keyword/?q=" + Uri.encode(q)); R("Buscando $q en Instagram, $tr.") }
                "spotify" -> {
                    if (!lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q)))))
                        abrirUrl("https://open.spotify.com/search/" + Uri.encode(q))
                    R("Buscando $q en Spotify, $tr.")
                }
                "netflix" -> { abrirUrl("https://www.netflix.com/search?q=" + Uri.encode(q)); R("Buscando $q en Netflix, $tr.") }
                "wikipedia" -> { abrirUrl("https://es.wikipedia.org/w/index.php?search=" + Uri.encode(q)); R("Buscando $q en Wikipedia, $tr.") }
                "mercado libre" -> { abrirUrl("https://listado.mercadolibre.com.pe/" + Uri.encode(q)); R("Buscando $q en Mercado Libre, $tr.") }
                "amazon" -> { abrirUrl("https://www.amazon.com/s?k=" + Uri.encode(q)); R("Buscando $q en Amazon, $tr.") }
                "twitter", "x" -> { abrirUrl("https://x.com/search?q=" + Uri.encode(q)); R("Buscando $q en X, $tr.") }
                else -> { buscarWeb(q); R("Buscando $q en Google, $tr.") }
            }
        }
        // "pon / reproduce X" sin sitio → YouTube
        Regex("^(?:pon(?:me)?|reproduce|reproducir|toca|quiero ver|quiero escuchar)\\s+(?:el video de\\s+|la cancion de\\s+|la cancion\\s+|el video\\s+|el tema\\s+|musica de\\s+)?(.+)$").find(t)?.let { m2 ->
            val q = f.originalDeGrupo(m2, 1)
            if (Regex("^(una|un)\\s+(alarma|temporizador|recordatorio)").containsMatchIn(m2.groupValues[1])) return null
            return youtube(q, cb)
        }
        return null
    }

    /** Busca el primer video en YouTube y lo reproduce directamente. */
    private fun youtube(q: String, cb: (Yarvis.Respuesta) -> Unit): Yarvis.Respuesta = fondo(cb) {
        val id = try {
            val c = URL("https://www.youtube.com/results?sp=EgIQAQ%253D%253D&search_query=" + Uri.encode(q)).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            c.setRequestProperty("Accept-Language", "es-PE,es;q=0.9")
            c.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
            c.connectTimeout = 10000
            c.readTimeout = 12000
            val html = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            (Regex("\"videoRenderer\":\\{\"videoId\":\"([\\w-]{11})\"").find(html)
                ?: Regex("\"videoId\":\"([\\w-]{11})\"").find(html)
                ?: Regex("/watch\\?v=([\\w-]{11})").find(html))?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
        if (id != null) {
            if (ServicioAccesibilidad.activo) ServicioAccesibilidad.prepararReproduccion()
            val app = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")).setPackage("com.google.android.youtube")
                .putExtra("force_fullscreen", false).putExtra("finish_on_ended", true)
            principal.post { if (!lanzar(app)) abrirUrl("https://www.youtube.com/watch?v=$id") }
            R("Reproduciendo $q en YouTube, $tr.")
        } else {
            principal.post {
                val i = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q)
                if (!lanzar(i)) abrirUrl("https://www.youtube.com/results?search_query=" + Uri.encode(q))
            }
            R("Buscando $q en YouTube, $tr.")
        }
    }

    private fun abrirApp(nombre: String): Yarvis.Respuesta {
        val especiales = mapOf(
            "configuracion" to Settings.ACTION_SETTINGS, "ajustes" to Settings.ACTION_SETTINGS,
            "ajustes de yarvis" to "", "configuracion de yarvis" to ""
        )
        if (nombre.contains("yarvis") && (nombre.contains("ajuste") || nombre.contains("configuracion"))) {
            lanzar(Intent(ctx, AjustesActivity::class.java)); return R("Aquí tiene mis ajustes, $tr.")
        }
        especiales[nombre]?.let { if (it.isNotEmpty()) { lanzar(Intent(it)); return R("Abriendo ajustes, $tr.") } }
        if (nombre in listOf("camara", "la camara")) { lanzar(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); return R("Cámara lista, $tr.") }

        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val alias = mapOf("whatsapp" to listOf("wasap", "guasap", "wsp", "whats app", "wasa"), "youtube" to listOf("you tube", "yutub"), "tiktok" to listOf("tik tok"), "facebook" to listOf("feis", "face"))
        var objetivo = nombre
        for ((real, vars) in alias) if (vars.any { objetivo == it }) objetivo = real
        var mejor: ResolveInfo? = null
        var puntaje = 0
        for (ri in apps) {
            val et = Frase.normalizar(ri.loadLabel(pm).toString())
            val p = when {
                et == objetivo -> 100
                et.replace(" ", "") == objetivo.replace(" ", "") -> 95
                et.startsWith(objetivo) -> 80
                et.length >= 3 && et.contains(objetivo) -> 60
                et.length >= 4 && objetivo.contains(et) -> 50
                else -> 0
            }
            if (p > puntaje) { puntaje = p; mejor = ri }
        }
        val elegido = mejor ?: return if (Regex("\\.(com|pe|org|net)").containsMatchIn(nombre)) abrirPagina(nombre) else R("No encontré la aplicación $nombre en su celular, $tr.")
        val i = pm.getLaunchIntentForPackage(elegido.activityInfo.packageName) ?: return R("No pude abrir $nombre, $tr.")
        return if (lanzar(i)) R("Abriendo ${elegido.loadLabel(pm)}, $tr.") else R("No pude abrir $nombre, $tr.")
    }

    private fun abrirPagina(nombre: String): Yarvis.Respuesta {
        var n = nombre.replace(" punto ", ".").replace("punto ", ".").replace(" ", "")
        if (!n.contains(".")) {
            abrirUrl("https://www.google.com/search?btnI=1&q=" + Uri.encode(nombre))
            return R("Abriendo la página de $nombre, $tr.")
        }
        if (!n.startsWith("http")) n = "https://$n"
        abrirUrl(n)
        return R("Abriendo $nombre, $tr.")
    }

    private fun linterna(on: Boolean): Boolean = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        if (id == null) false else { cm.setTorchMode(id, on); true }
    } catch (_: Exception) {
        false
    }

    private fun volumen(f: Frase): Yarvis.Respuesta {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s)
        Regex("(?:volumen )?(?:al |en )?(\\d{1,3})\\s*(?:%|por ciento)").find(f.t)?.let {
            val p = it.groupValues[1].toInt().coerceIn(0, 100)
            am.setStreamVolume(s, (max * p / 100.0).toInt(), AudioManager.FLAG_SHOW_UI)
            return R("Volumen al $p por ciento, $tr.")
        }
        when {
            f.tiene("(maximo|al tope|full|a todo)") -> am.setStreamVolume(s, max, AudioManager.FLAG_SHOW_UI)
            f.tiene("(minimo)") -> am.setStreamVolume(s, 1, AudioManager.FLAG_SHOW_UI)
            f.tiene("(sube|subir|aumenta|mas)") -> repeat(3) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            f.tiene("(baja|bajar|disminuye|menos)") -> repeat(3) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            f.tiene("(silencia|silencio|mute|quita el sonido)") -> {
                return R("Silenciando, $tr.") { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI) }
            }
            else -> return R("¿Subo o bajo el volumen, $tr?", seguir = true)
        }
        return R("Hecho, $tr.")
    }

    private fun modoSonido(f: Frase): Yarvis.Respuesta {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return try {
            when {
                f.tiene("vibra") -> { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE; R("Modo vibración activado, $tr.") }
                f.tiene("silencio") -> {
                    if (!nm.isNotificationPolicyAccessGranted) { pedirAccesoNoMolestar(); R("Necesito su permiso para el modo silencio, $tr. Active YARVIS en la pantalla que le abrí.") }
                    else { am.ringerMode = AudioManager.RINGER_MODE_SILENT; R("Modo silencio activado, $tr.") }
                }
                else -> { am.ringerMode = AudioManager.RINGER_MODE_NORMAL; R("Sonido normal activado, $tr.") }
            }
        } catch (_: SecurityException) {
            pedirAccesoNoMolestar()
            R("Necesito su permiso para cambiar el modo de sonido, $tr.")
        }
    }

    private fun noMolestar(activar: Boolean): Yarvis.Respuesta {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            pedirAccesoNoMolestar()
            return R("Necesito su permiso para controlar el modo no molestar, $tr. Active YARVIS en la pantalla que le abrí.")
        }
        nm.setInterruptionFilter(if (activar) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return R(if (activar) "Modo no molestar activado, $tr." else "Modo no molestar desactivado, $tr.")
    }

    private fun pedirAccesoNoMolestar() = lanzar(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))

    private fun brillo(f: Frase): Yarvis.Respuesta {
        if (!Settings.System.canWrite(ctx)) {
            lanzar(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            return R("Necesito permiso para cambiar el brillo, $tr. Actívelo en la pantalla que le abrí y vuelva a pedírmelo.")
        }
        val cr = ctx.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val actual = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val nuevo = when {
            f.tiene("(maximo|al tope|full)") -> 255
            f.tiene("(minimo)") -> 10
            f.tiene("automatico") -> {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                return R("Brillo automático activado, $tr.")
            }
            f.tiene("(sube|aumenta|mas)") -> (actual + 60).coerceAtMost(255)
            f.tiene("(baja|disminuye|menos)") -> (actual - 60).coerceAtLeast(10)
            else -> Regex("(\\d{1,3})").find(f.t)?.groupValues?.get(1)?.toIntOrNull()?.let { (it.coerceIn(1, 100) * 255 / 100) } ?: return R("¿Subo o bajo el brillo, $tr?", seguir = true)
        }
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, nuevo)
        return R("Brillo ajustado, $tr.")
    }

    private fun global(accion: Int, ok: String): Yarvis.Respuesta {
        if (!ServicioAccesibilidad.activo) {
            lanzar(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return R("Para eso necesito el permiso de accesibilidad, $tr. Active YARVIS en la pantalla que le abrí.")
        }
        return R(ok) { principal.postDelayed({ ServicioAccesibilidad.accion(accion) }, 300) }
    }

    private fun tecla(codigo: Int, ok: String): Yarvis.Respuesta {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ahora = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(ahora, ahora, KeyEvent.ACTION_DOWN, codigo, 0))
        am.dispatchMediaKeyEvent(KeyEvent(ahora, ahora, KeyEvent.ACTION_UP, codigo, 0))
        return R(ok)
    }

    private fun guardarNota(nota: String) {
        val limpia = nota.trim().replaceFirstChar { it.uppercase() }
        Prefs.notas = (Prefs.notas + "\n" + limpia).trim()
    }

    private fun buscarWeb(q: String) {
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        if (!lanzar(i)) abrirUrl("https://www.google.com/search?q=" + Uri.encode(q))
    }

    private fun abrirUrl(url: String) = lanzar(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    fun lanzar(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(ctx.packageManager) == null && i.`package` != null) false
        else {
            ctx.startActivity(i)
            if (i.component?.packageName != ctx.packageName) abrioApp = true
            true
        }
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    // =====================================================================
    //  Utilidades
    // =====================================================================

    private fun saludo(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 12 -> "Buenos días"
            h < 19 -> "Buenas tardes"
            else -> "Buenas noches"
        }
    }

    fun saludoInicial(): String {
        val extra = listOf(
            "Todos los sistemas en línea.", "Sistemas operativos al cien por ciento.", "Es un placer verle de nuevo."
        ).random()
        return "${saludo()}, $tr. $extra ¿En qué puedo ayudarle?"
    }

    private fun duracionTexto(seg: Int): String {
        val h = seg / 3600
        val m = (seg % 3600) / 60
        val s = seg % 60
        val partes = mutableListOf<String>()
        if (h > 0) partes.add("$h hora${if (h == 1) "" else "s"}")
        if (m > 0) partes.add("$m minuto${if (m == 1) "" else "s"}")
        if (s > 0) partes.add("$s segundo${if (s == 1) "" else "s"}")
        return partes.joinToString(" y ")
    }

    private fun numero(x: Double): String =
        if (x == floor(x) && abs(x) < 1e15) x.toLong().toString()
        else String.format(es, "%.4f", x).trimEnd('0').trimEnd(',', '.')

    private val chistes = listOf(
        "¿Por qué la computadora fue al médico? Porque tenía un virus. No se preocupe, yo estoy vacunado.",
        "Le pregunté al wifi si me quería. Me dijo que sí, pero solo cuando estoy cerca.",
        "¿Qué le dijo un bit a otro bit? Nos vemos en el bus.",
        "Un programador va a la tienda. Su esposa le dice: compra un pan, y si hay huevos, trae seis. Volvió con seis panes.",
        "Mi batería y yo tenemos algo en común: al final del día, ambos necesitamos recargarnos.",
        "¿Cuál es el colmo de un celular? Tener muchas aplicaciones y ninguna aplicación en la vida.",
        "Intenté organizar un torneo de escondidas entre computadoras, pero fue imposible: los buenos escondites siempre estaban ocupados por los archivos temporales."
    )
}
