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
    private val tr get() = Yarvis.tratoActual

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

    private fun confirmarPublicacion(red: Publicar.Red, texto: String): Yarvis.Respuesta {
        val limpio = texto.trim().replaceFirstChar { it.uppercase() }
        return esperar("Voy a publicar en ${red.nombre}: «$limpio». ¿Lo publico, $tr?") { f2 ->
            val t2 = f2.t
            val cambio = Regex("^(?:no,? )?(?:mejor|cambialo|cambia|pon|escribe)(?: que| por| a)?\\s+(.+)$").find(t2)
            when {
                cambio != null -> confirmarPublicacion(red, f2.originalDeGrupo(cambio, 1))
                Regex("^(si|sip|claro|dale|ok|okay|publicalo|publica|hazlo|de acuerdo|correcto|listo|perfecto)\\b").containsMatchIn(t2) ->
                    R(Publicar.ejecutar(ctx, red, limpio), cerrar = true)
                else -> R("Entendido, $tr. No publico nada.", cerrar = true)
            }
        }
    }

    /** Pregunta algo al usuario y procesa su próxima respuesta con [siguiente] (para diálogos que inicia ODÍN). */
    fun preguntarAlUsuario(pregunta: String, siguiente: (Frase) -> Yarvis.Respuesta?): Yarvis.Respuesta = esperar(pregunta, siguiente)

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
            "(odin|odín|yarvis|jarvis|llarvis|harvis|${Regex.escape(Prefs.palabraActivacion)})\\s*[,.]?\\s*",
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

    private val rAccion = Regex("(public|postea|chatea|estado|llam|marc|telefon|mensaje|whatsapp|wasap|wsp|sms|escrib|envi|mand|respond|contest|reproduc|\\bpon\\b|ponme|video|cancion|musica|abre|abrir|abri|busca|alarma|recuerd|lleva|naveg|linterna|volumen|foto|camara|selfie|despierta|temporizador|avisa|grab|acta|recuerda|cumple|laptop|computadora|cambio|dolar|antirrobo|gast|pague|compre|correo|email|mail|modo|traduc|pantalla|camara|ves\\b|donde estas|copia|respald|resum|aplau)")

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
            // Pregunta sobre la foto que acaba de tomar («¿y de qué color es?»)
            fotoReciente() != null -> fondo(cb) {
                val foto = fotoReciente() ?: return@fondo R("", cerrar = true)
                CamaraActivity.ultimaFotoHora = System.currentTimeMillis()
                try { R(CerebroIA.verImagen(foto, "$limpio (Responde sobre esta foto que ya viste antes, de forma breve.)"), seguir = true) }
                catch (e: Exception) { R(CerebroIA.mensajeError(e), cerrar = true, detalle = CerebroIA.detalle(e)) }
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

        // Modo traductor: todo lo que oiga se traduce (hasta «termina la traducción»)
        if (Traductor.activo != null) {
            if (!Traductor.turnoOtro && f.tiene("(termina|terminar|salir|sal|deten|para|cancela|desactiva|apaga|fin)( el| la| del)? ?(modo )?(traduc|traductor)|^(eso es todo|ya esta|listo|gracias odin)$")) {
                Traductor.terminar()
                return R("Modo traductor terminado, $tr.", cerrar = true)
            }
            if (Traductor.turnoOtro && f.tiene("(stop|finish|end) (the )?transla")) { Traductor.terminar(); return R("Modo traductor terminado, $tr.", cerrar = true) }
            return fondo(cb) { Traductor.turno(f.original) }
        }

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
        if (f.tiene("^(adios|hasta luego|hasta manana|chau|chao|nos vemos|eso es todo|eso seria todo|nada mas|es todo|descansa|apagate|termina|terminar|terminamos|cierra la conversacion|fin de la conversacion|ya esta|listo eso es todo|puedes retirarte|retirate|ya no|ya no necesito nada|buenas noches yarvis|buenas noches odin)\\b") ||
            f.tiene("(eso es todo|eso seria todo|nada mas|es todo|ya no necesito nada|termina la conversacion|cierra la conversacion)$")) {
            return R(listOf("A su servicio, $tr.", "Estaré aquí cuando me necesite, $tr.", "Que tenga un excelente día, $tr.").random(), cerrar = true)
        }
        if (f.tiene("(que puedes hacer|que sabes hacer|^ayuda$|que comandos|lista de comandos|como te uso|para que sirves)")) {
            lanzar(Intent(ctx, ComandosActivity::class.java))
            return R("Puedo hacer llamadas, enviar mensajes por WhatsApp y SMS, leer sus notificaciones, programar alarmas, recordatorios y eventos, " +
                "decirle el clima, controlar la música, la linterna y el volumen, abrir aplicaciones, poner videos, tomar fotos " +
                "y responder cualquier pregunta. Le abrí la lista completa de órdenes, $tr.")
        }

        // --- Funciones nuevas: encontrar el celular, rutina, modos, traductor, cámara, pantalla, mensajes, gastos, correo, respaldo
        nuevas2(f, cb)?.let { return it }
        nuevas(f, cb)?.let { return it }

        // --- Seguridad por voz: quién puede darle órdenes ---------------------
        val esDueno = Yarvis.hablanteActual == null
        if (f.tiene("^(aprende|aprender|reconoce|registra|memoriza|graba|guarda) (bien )?mi voz")) {
            lanzar(Intent(ctx, VozDuenoActivity::class.java))
            return R("Le abrí el registro de su voz, $tr. Lea las frases que aparecen en pantalla.", cerrar = true)
        }
        Regex("^(?:aprende|aprender|reconoce|registra|memoriza|graba|guarda|agrega|anade)(?: tambien)? (?:la voz de |a )(.+)$").find(t)?.let { m ->
            if (!esDueno) return R("Solo mi dueño puede autorizar nuevas voces, $tr.")
            val nombre = f.originalDeGrupo(m, 1).replaceFirstChar { it.uppercase() }
            lanzar(Intent(ctx, VozDuenoActivity::class.java).putExtra(VozDuenoActivity.EXTRA_NOMBRE, nombre))
            return R("Muy bien. Que $nombre se acerque al celular y lea las frases de la pantalla, así aprenderé su voz.", cerrar = true)
        }
        Regex("^(?:olvida|borra|elimina|quita)(?: la voz de| a)? (.+)$").find(t)?.takeIf { m ->
            VozDueno.invitados().any { Frase.normalizar(it.nombre) == Frase.normalizar(m.groupValues[1]) }
        }?.let { m ->
            if (!esDueno) return R("Solo mi dueño puede hacer eso, $tr.")
            VozDueno.quitar(m.groupValues[1])
            return R("Listo, $tr. Ya no atenderé a ${f.originalDeGrupo(m, 1)}.")
        }
        if (f.tiene("^(solo|unicamente) (hazme caso|obedeceme|atiendeme|escuchame)( a mi)?|^(ya no le hagas caso|deja de hacerle caso|no le hagas caso)|modo privado|solo a mi")) {
            if (!esDueno) return R("Solo mi dueño puede cambiar eso, $tr.")
            Prefs.invitadoHasta = 0
            return R(if (Prefs.verificarVoz && VozDueno.hayDueno()) "Entendido, $tr. Desde ahora solo atenderé su voz${if (VozDueno.invitados().isNotEmpty()) " y la de las personas que usted registró" else ""}."
            else "Entendido, $tr. Para que solo atienda su voz, primero debo aprenderla. Diga «aprende mi voz».")
        }
        Regex("^(?:hazle caso|hagale caso|obedecele|obedece a|atiendele|atiende a|escuchale|escucha a|haz caso a|hazle caso tambien|deja que te hable|deja que te de ordenes)(?: a| tambien a)?\\s*(.*)$").find(t)?.let { m ->
            if (!esDueno) return R("Solo mi dueño puede dar ese permiso, $tr.")
            val quien = m.groupValues[1].trim()
            val minutos = Regex("(\\d+)\\s*(minuto|hora)").find(t)?.let { val n = it.groupValues[1].toInt(); if (it.groupValues[2] == "hora") n * 60 else n } ?: 30
            Prefs.invitadoHasta = System.currentTimeMillis() + minutos * 60_000L
            val registrado = VozDueno.invitados().firstOrNull { quien.isNotBlank() && Frase.normalizar(it.nombre).let { n -> quien.contains(n) || n.contains(quien) } }
            if (registrado != null) return R("${registrado.nombre} ya está autorizado, $tr. Lo reconoceré por su voz.")
            val duracion = if (minutos % 60 == 0) "${minutos / 60} hora${if (minutos >= 120) "s" else ""}" else "$minutos minutos"
            return R("De acuerdo, $tr. Durante los próximos $duracion atenderé a cualquier persona. Si quiere que la recuerde siempre, diga «aprende la voz de» y su nombre.")
        }
        if (f.tiene("(quien soy|reconoces mi voz|sabes quien soy|con quien hablas|quien te habla)")) {
            val h = Yarvis.hablanteActual
            return R(if (h != null) "Usted es $h. Su voz está registrada y mi dueño le autorizó a darme órdenes."
            else if (VozDueno.hayDueno()) "Usted es mi dueño${Prefs.nombre.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""}. Reconozco su voz perfectamente."
            else "Usted es ${Prefs.nombre.ifBlank { "mi dueño" }}. Aunque aún no he aprendido su voz; si lo desea, diga «aprende mi voz».")
        }

        // --- Chats automáticos ----------------------------------------------------
        Regex("^(?:deja de chatear|termina el chat|ya no chatees|desactiva el chat|para el chat|deja de atender los mensajes|deja de responder)(?: automatico)?(?:\\s+(?:con|de|a)\\s+(.+))?$").find(t)?.let { m ->
            val quien = m.groupValues[1].takeIf { it.isNotBlank() }
            ChatAsistido.detener(quien)
            return R(if (quien == null) "Listo, $tr. Ya no respondo ningún chat por usted." else "Listo, $tr. Ya no respondo a ${f.originalDeGrupo(m, 1)}.")
        }
        Regex("^(?:chatea|conversa|habla|atiende el chat|atiende los mensajes|responde por mi|contesta por mi|encargate del chat|encargate de los mensajes|responde automaticamente|contesta automaticamente)(?: automaticamente)?(?: por mi)?(?: con| de| a)?\\s+(.+?)(?:\\s+por\\s+(?:whatsapp|wasap|messenger|telegram|mensaje))?(?:\\s+(?:automaticamente|por mi|solo))?(?:\\s+(?:y|pero)\\s+(preguntame|consultame)(?: antes)?)?(?:\\s+(?:durante|por)\\s+(\\d+)\\s*(minutos?|horas?))?$").find(t)?.let { m ->
            if (!esDueno) return R("Solo mi dueño puede pedirme eso, $tr.")
            if (!ServicioNotificaciones.listo(ctx)) {
                lanzar(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return R("Para atender sus chats necesito acceso a sus notificaciones, $tr. Active ODÍN en la pantalla que le abrí.", cerrar = true)
            }
            val nombre = f.originalDeGrupo(m, 1).replace(Regex("(?i)^(a |al |la |el |mi )"), "").trim()
            val preguntar = m.groupValues[2].isNotBlank() || f.tiene("(preguntame|consultame)")
            val n = m.groupValues[3].toIntOrNull()
            val minutos = if (n == null) 180 else if (m.groupValues[4].startsWith("hora")) n * 60 else n
            val c = ChatAsistido.iniciar(nombre, minutos, automatico = !preguntar)
            val estilo = if (c.guia.isNotBlank()) " imitando su forma de escribir" else ""
            return R(if (preguntar) "De acuerdo, $tr. Cuando $nombre le escriba, le leeré el mensaje y le propondré una respuesta antes de enviarla."
            else "De acuerdo, $tr. Responderé a $nombre por usted$estilo. Si me escribe de dinero, citas o algo delicado, se lo consultaré primero. Para detenerme, diga «deja de chatear con $nombre».")
        }

        // --- Publicaciones en redes -------------------------------------------------
        val rRed = "(facebook|face|feis|whatsapp|wasap|estado de whatsapp|estados|estado|mi estado|instagram|insta|tiktok|tik tok|twitter|x|telegram)"
        (Regex("^(?:publica|postea|sube|comparte|haz una publicacion|haz un post|pon un estado|publicar|escribe una publicacion|escribe un post)(?:\\s+(?:esto|un estado|una publicacion|un post|un mensaje))?\\s+(?:en|a)\\s+(?:mi\\s+)?$rRed(?:\\s+(que diga|que dice|diciendo|con el texto|que|sobre|acerca de|de))?\\s*(.*)$").find(t)
            ?.let { m -> Triple(m.groupValues[1], m.groupValues[2], m.groups[3]?.let { g -> if (g.value.isBlank()) "" else f.originalEntre(g.range.first) }.orEmpty().let { c -> if (m.groupValues[3].isBlank() || Regex("^\\S+:").containsMatchIn(c) && !m.groupValues[3].contains(":")) c.replace(Regex("^\\S*:\\s*"), "") else c }) }
            ?: Regex("^(?:publica|postea|sube|comparte)\\s+(?:que\\s+)?(.+?)\\s+en\\s+(?:mi\\s+)?$rRed$").find(t)
                ?.let { m -> Triple(m.groupValues[2], "", f.originalDeGrupo(m, 1)) })?.let { (redDicha, conector, contenido) ->
            if (!esDueno) return R("Solo mi dueño puede publicar en sus redes, $tr.")
            val red = Publicar.red(redDicha) ?: return null
            val sobreTema = conector == "sobre" || conector == "acerca de" || conector == "de"
            return when {
                contenido.isBlank() -> esperar("¿Qué desea publicar en ${red.nombre}, $tr?") { f2 -> confirmarPublicacion(red, f2.original.trim()) }
                sobreTema -> fondo(cb) {
                    val texto = Publicar.redactar(red, contenido) ?: return@fondo R("No pude redactar la publicación ahora, $tr. Dígame el texto exacto y lo publico.")
                    confirmarPublicacion(red, texto)
                }
                else -> confirmarPublicacion(red, contenido)
            }
        }

        // --- Personalidad ------------------------------------------------------
        if (f.tiene("(activa|pon|modo) (el )?modo confianza|^modo confianza|hablame de tu|tuteame|habla(me)? (mas )?(relajado|normal|como amigo)|se mas relajado|deja de ser tan formal")) {
            Prefs.modoConfianza = true
            return R("¡Listo! Desde ahora te hablo de tú y en confianza. ¿Qué necesitas?", seguir = true)
        }
        if (f.tiene("(modo formal|hablame de usted|se formal|habla formal|desactiva el modo confianza)")) {
            Prefs.modoConfianza = false
            return R("Como usted prefiera, $tr. Vuelvo al trato formal.")
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
        if (f.tiene("^(hola|hey|oye|buenas|buenos dias|buenas tardes|buenas noches|que tal|saludos)( yarvis| odin)?$")) {
            return R("${saludo()}, $tr. ¿En qué puedo ayudarle?", seguir = true)
        }
        if (f.tiene("(quien eres|como te llamas|cual es tu nombre|presentate|que eres)")) {
            return R("Soy ODÍN, su asistente personal, desarrollado por VELKYRA. Estoy a su servicio las veinticuatro horas, $tr.")
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
                .putExtra(AlarmClock.EXTRA_MESSAGE, "ODÍN")
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
                .putExtra(AlarmClock.EXTRA_MESSAGE, "ODÍN")
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
        if (f.tiene("^(pausa|pausalo|pausala|pausar|deten|detenlo|detenla|detener|para|paralo|parala|parar|stop|quita|quitalo|apaga|silencia|callate)( (el|la|lo|ese|esa|este|esta))?( (musica|cancion|video|videos|reproduccion|youtube|tema|audio|sonido))?( (por favor|ya))?$") ||
            f.tiene("(pausa|pausar|deten|detener|para|parar|quita|apaga|corta|detenga|pare) (el|la|ese|esa|este|esta) (musica|cancion|video|reproduccion|tema|audio)")) {
            Medios.pausar()
            return R("En pausa, $tr.", cerrar = true)
        }
        if (f.tiene("^(continua|continuar|reanuda|reanudalo|sigue|play|dale play|dale)( (el|la))?( (musica|cancion|video|reproduccion))?$")) {
            Medios.reanudar()
            return R("Reanudando, $tr.", cerrar = true)
        }
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
            val porWaze = MisApps.elegida(ctx, "mapas") == "com.waze" &&
                lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=" + Uri.encode(lugar) + "&navigate=yes")).setPackage("com.waze"))
            if (!porWaze && !lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(lugar)))))
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
    //  Funciones nuevas (v3.2): programados, grabadora, memoria, cumpleaños, laptop, lugares, antirrobo, cambio
    // =====================================================================

    private val rLaptop = "(laptop|lap top|la pc|mi pc|computadora|compu|portatil|ordenador)"

    private fun nuevas2(f: Frase, cb: (Yarvis.Respuesta) -> Unit): Yarvis.Respuesta? {
        val t = f.t

        // --- Grabar reuniones ----------------------------------------------------------
        if (Grabadora.activa && f.tiene("((termina|terminar|deten|detener|para|parar|finaliza|corta|acaba)( la)? (grabacion|reunion|clase)|deja de grabar|ya (termino|acabo) la (reunion|clase)|fin de la (reunion|grabacion)|haz el acta)")) {
            return fondo(cb) { R(Grabadora.procesar(ctx), cerrar = true) }
        }
        if (Grabadora.activa && f.tiene("(cuanto (llevo|lleva|va) grabando|cuanto tiempo de grabacion|sigues grabando)")) {
            return R("Llevo ${Grabadora.duracionTexto()} grabando, $tr.", cerrar = true)
        }
        Regex("^(?:graba|grabar|empieza a grabar|inicia(?:r)? (?:la )?grabacion(?: de)?|comienza a grabar)\\s*(?:la |esta |el |una )?(reunion|clase|conferencia|charla|sesion|asamblea|entrevista|capacitacion|esto|audio)?(?: de (.+))?$").find(t)?.let { m ->
            if (Grabadora.activa) return R("Ya estoy grabando, $tr. Llevo ${Grabadora.duracionTexto()}.")
            val tipo = m.groupValues[1].ifBlank { "reunión" }.replace("reunion", "reunión").replace("sesion", "sesión").replace("capacitacion", "capacitación").replace("esto", "reunión").replace("audio", "reunión")
            val titulo = (tipo.replaceFirstChar { it.uppercase() } + (m.groups[2]?.let { " de " + f.originalDeGrupo(m, 2) } ?: ""))
            if (!Grabadora.iniciar(ctx, titulo)) return R("No pude empezar a grabar, $tr.")
            ServicioEscucha.reiniciar()
            return R("Grabando, $tr. Deje el celular cerca de quienes hablan. Cuando terminen, diga «${Prefs.palabraActivacion}, termina la grabación» y le preparo el acta.", cerrar = true)
        }
        if (f.tiene("(comparte|compartir|envia|manda)(me)? el acta") ) {
            return if (Grabadora.compartirActa(ctx)) R("Elija dónde compartir el acta, $tr.", cerrar = true) else R("Aún no tengo un acta, $tr. Diga: graba la reunión.")
        }
        if (f.tiene("(leeme|lee|resume) (el|la ultima) acta")) {
            val a = Grabadora.ultimaActa ?: return R("Aún no tengo un acta, $tr.")
            return R(a.substringBefore("────").take(1500), seguir = true)
        }

        // --- Mensajes programados ---------------------------------------------------------
        if (f.tiene("(mis mensajes programados|que mensajes (tengo )?programados|mensajes pendientes por enviar)")) {
            val l = Programados.lista(ctx)
            if (l.isEmpty()) return R("No tiene mensajes programados, $tr.")
            return R("Tiene ${l.size} mensaje${if (l.size > 1) "s" else ""} programado${if (l.size > 1) "s" else ""}: " +
                l.joinToString(". ") { "a ${it.quien}, ${Tiempo.describir(Calendar.getInstance().apply { timeInMillis = it.cuando })}: «${it.texto}»" } + ".")
        }
        Regex("(?:cancela|borra|elimina|anula)(?:r)? (?:el |los |todos los )?mensajes? programados?(?: (?:para|a) (.+))?$").find(t)?.let { m ->
            val q = m.groups[1]?.let { f.originalDeGrupo(m, 1) }
            val l = Programados.cancelar(ctx, q)
            return R(if (l.isEmpty()) "No encontré mensajes programados${q?.let { " para $it" } ?: ""}, $tr." else "Cancelé ${l.size} mensaje${if (l.size > 1) "s" else ""} programado${if (l.size > 1) "s" else ""}, $tr.")
        }
        programado(f)?.let { return it }

        // --- Memoria personal --------------------------------------------------------------
        Regex("^(?:recuerda|acuerdate de|guarda|anota en tu memoria|memoriza)(?: que)? (.+)$").find(t)?.let { m ->
            if (!t.startsWith("recuerdame") && Tiempo.analizar(t)?.let { it.conHora } != true) {
                val dato = f.originalDeGrupo(m, 1).trim().trimEnd('.')
                if (dato.split(" ").size >= 2 && !Regex("^(este lugar|esta ubicacion|aqui)").containsMatchIn(m.groupValues[1])) {
                    Memoria.recordar(dato.replaceFirstChar { it.uppercase() })
                    return R("Lo recordaré, $tr: ${Memoria.enSegunda(dato)}.")
                }
            }
        }
        if (f.tiene("^(donde (deje|puse|guarde|esta|estan|quedo|quedaron|estacione)|cual es (mi|la|el)|cuales son (mis|las)|que te dije (de|sobre)|te acuerdas (de|que|donde)|recuerdas (donde|que|cual)|cual era (mi|la|el)|como se llama (mi|el|la))")) {
            val datos = Memoria.buscar(t)
            if (datos.isNotEmpty()) {
                val d = datos.first()
                return R("${Memoria.enSegunda(d.texto).replaceFirstChar { it.uppercase() }}. Me lo dijo ${Tiempo.describir(Calendar.getInstance().apply { timeInMillis = d.hora }).replace(Regex("^(el|a las) "), "el ")}.".replace(" el hoy", " hoy"), cerrar = false)
            }
            if (f.tiene("^donde (deje|puse|guarde|estacione)")) return R("No me dijo dónde lo dejó, $tr. La próxima vez dígame, por ejemplo: recuerda que dejé las llaves en el cajón.")
        }
        if (f.tiene("(que (cosas )?te (pedi|dije) (que )?(recordar|recuerdes)|que recuerdas de mi|tu memoria|que tienes guardado)")) {
            val l = Memoria.lista()
            if (l.isEmpty()) return R("No me ha pedido recordar nada todavía, $tr.")
            return R("Me pidió recordar: " + l.takeLast(8).joinToString(". ") { Memoria.enSegunda(it.texto) } + ".")
        }
        Regex("^(?:olvida|olvidate de|borra de tu memoria)(?: lo de| que| de)? (.+)$").find(t)?.let { m ->
            if (f.tiene("(mi voz|la voz)")) return@let
            if (f.tiene("^(olvida|olvidate de) (todo|todo lo que te dije)$")) { Memoria.borrarTodo(); return R("Borré todo lo que me pidió recordar, $tr.") }
            val n = Memoria.olvidar(m.groupValues[1])
            if (n > 0) return R("Listo, $tr. Lo olvidé.")
        }

        // --- Cumpleaños ---------------------------------------------------------------------
        Regex("^(?:el )?cumpleanos de (.+?) es (?:el )?(\\d{1,2}) de (enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)$").find(t)?.let { m ->
            val mes = Cumpleanos.MESES.indexOf(m.groupValues[3].replace("setiembre", "septiembre")) + 1
            val nombre = f.originalDeGrupo(m, 1).replaceFirstChar { it.uppercase() }
            Cumpleanos.agregar(ctx, Cumpleanos.Cumple(nombre, m.groupValues[2].toInt(), mes))
            return R("Anotado, $tr: $nombre cumple el ${m.groupValues[2]} de ${Cumpleanos.MESES[mes - 1]}. Ese día le aviso.")
        }
        if (f.tiene("(quien(es)? cumple(n)? (anos )?(hoy|esta semana|este mes|pronto|manana)|cumpleanos (de hoy|de esta semana|de este mes|proximos)|hay cumpleanos)")) {
            val hoy = Cumpleanos.deHoy(ctx)
            val dias = when { f.tiene("mes") -> 31; f.tiene("manana") -> 1; else -> 7 }
            val prox = if (f.tiene("hoy")) emptyList() else Cumpleanos.proximos(ctx, dias)
            if (hoy.isEmpty() && prox.isEmpty()) return R("No hay cumpleaños ${if (f.tiene("hoy")) "hoy" else "próximos"} en sus contactos, $tr.")
            val partes = mutableListOf<String>()
            if (hoy.isNotEmpty()) partes.add("Hoy cumple años ${hoy.joinToString(" y ") { it.nombre }}")
            if (prox.isNotEmpty()) partes.add(prox.take(6).joinToString(", ") { (c, d) -> "${c.nombre} ${if (d == 1) "mañana" else "en $d días, el ${Cumpleanos.fecha(c)}"}" })
            if (hoy.isNotEmpty()) {
                pendiente = { f2 -> decidirSaludo(hoy.first().nombre, f2) }
                pendienteHasta = System.currentTimeMillis() + 60_000
            }
            return R(partes.joinToString(". ") + "." + if (hoy.isNotEmpty()) " ¿Le mando un saludo a ${hoy.first().nombre}?" else "", seguir = hoy.isNotEmpty())
        }
        Regex("^(?:cuando (?:es|cumple) (?:el cumpleanos de |anos )?|cuando cumple )(.+?)(?: anos)?$").find(t)?.let { m ->
            val c = Cumpleanos.de(ctx, m.groupValues[1]) ?: return@let
            return R("${c.nombre} cumple años el ${Cumpleanos.fecha(c)}, $tr.")
        }
        Regex("^(?:saluda(?:lo|la)?|felicita(?:lo|la)?|manda(?:le)? (?:un )?saludo(?: de cumpleanos)?)(?: a (.+?))?(?: por su cumpleanos)?$").find(t)?.let { m ->
            val nombre = m.groups[1]?.let { f.originalDeGrupo(m, 1) } ?: Cumpleanos.deHoy(ctx).firstOrNull()?.nombre ?: return@let
            if (!f.tiene("cumple") && m.groups[1] == null) return@let
            return decidirSaludo(nombre, Frase("si"))
        }

        // --- Laptop (VELKYRA PC Link) ----------------------------------------------------------
        if (f.tiene("(conecta|vincula|enlaza|empareja)(te)? (con )?(mi |la )?$rLaptop")) {
            return fondo(cb) {
                val pc = PcLink.buscar() ?: return@fondo R("No encontré su laptop en el wifi, $tr. En la laptop descargue y abra «VELKYRA PC Link»; el enlace está en Ajustes, sección Mi laptop. Deben estar en el mismo wifi.")
                esperar("Encontré ${pc.second}. Dígame el código de 6 números que aparece en la laptop.") { f2 ->
                    val pin = f2.t.filter { it.isDigit() }.ifBlank { Tiempo.numero(f2.t).toInt().toString() }
                    if (pin.length != 6) return@esperar R("El código debe tener 6 números, $tr. Diga «conecta con mi laptop» para intentar de nuevo.")
                    fondo(cb) {
                        val err = PcLink.emparejar(pc.first, pc.second, pin)
                        R(err?.let { "$it, $tr." } ?: "¡Listo! Ya controlo ${PcLink.nombre}. Pruebe: «pon música en la laptop» o «apaga la laptop».")
                    }
                }
            }
        }
        if (f.tiene("\\b(la|mi|tu) (laptop|lap top|pc|computadora|compu|portatil)\\b") && !f.tiene("^(que es|como|cuanto cuesta|cual es la mejor|recomiendame)")) {
            pcOrden(f)?.let { (accion, valor) -> return fondo(cb) { R(PcLink.orden(accion, valor), cerrar = true) } }
        }

        // --- Recordatorios por lugar ---------------------------------------------------------
        Regex("^(?:guarda|anota|recuerda|marca)(?: este lugar| esta ubicacion| donde estoy| aqui)? como (.+)$|^(?:aqui|este lugar) es (.+)$").find(t)?.let { m ->
            val nombre = (m.groups[1] ?: m.groups[2])?.let { f.originalDeGrupo(m, if (m.groups[1] != null) 1 else 2) } ?: return@let
            return fondo(cb) {
                val l = Lugares.aqui(ctx) ?: return@fondo R("No pude saber dónde está, $tr. Active la ubicación.")
                Lugares.guardarLugar(ctx, nombre, l.latitude, l.longitude)
                R("Guardé este lugar como «${nombre.replace(Regex("(?i)^(mi|la|el) "), "")}», $tr. Ya puede decirme: cuando llegue a ${nombre.lowercase()}, recuérdame…")
            }
        }
        val rLugar1 = Regex("^(?:cuando|apenas|en cuanto) (llegue|este|entre|salga|me vaya) (?:a|al|en|de|del) (.+?),? (?:recuerdame|avisame|dime|recordarme)(?: que| de)? (.+)$")
        val rLugar2 = Regex("^(?:recuerdame|avisame|recordarme)(?: que| de)? (.+?) (?:cuando|apenas|en cuanto) (llegue|este|entre|salga|me vaya) (?:a|al|en|de|del) (.+)$")
        val ml1 = rLugar1.find(t)
        val ml2 = if (ml1 == null) rLugar2.find(t) else null
        if (ml1 != null || ml2 != null) {
            val verbo = ml1?.groupValues?.get(1) ?: ml2!!.groupValues[2]
            val lugar = ml1?.let { f.originalDeGrupo(it, 2) } ?: f.originalDeGrupo(ml2!!, 3)
            val tarea = ml1?.let { f.originalDeGrupo(it, 3) } ?: f.originalDeGrupo(ml2!!, 1)
            val entrar = verbo !in setOf("salga", "me vaya")
            if (!Lugares.permisoListo(ctx)) {
                Lugares.pedirPermiso(ctx)
                return R("Para avisarle al llegar necesito su ubicación «todo el tiempo», $tr. En la pantalla que le abrí entre a Permisos, Ubicación, y elija «Permitir todo el tiempo». Luego repítame el recordatorio.", cerrar = true)
            }
            return fondo(cb) {
                val (sitio, radio) = Lugares.ubicar(ctx, lugar) ?: return@fondo R("No encontré «$lugar» en el mapa, $tr. Si es un lugar suyo, vaya allí y dígame: guarda este lugar como $lugar.")
                Lugares.crear(ctx, tarea.replaceFirstChar { it.uppercase() }, sitio, radio, entrar)
                R("Hecho, $tr. Cuando ${if (entrar) "llegue a" else "salga de"} ${sitio.nombre} le recordaré: $tarea.")
            }
        }
        if (f.tiene("(mis recordatorios (por|de) lugar|recordatorios de ubicacion)")) {
            val l = Lugares.lista(ctx)
            return R(if (l.isEmpty()) "No tiene recordatorios por lugar, $tr." else l.joinToString(". ") { "Al ${if (it.entrar) "llegar a" else "salir de"} ${it.lugar}: ${it.texto}" } + ".")
        }
        if (f.tiene("(cancela|borra|elimina) (los |mis )?recordatorios (por|de) (lugar|ubicacion)")) return R("Cancelé ${Lugares.cancelarTodos(ctx)} recordatorios por lugar, $tr.")

        // --- Antirrobo -------------------------------------------------------------------------
        if (f.tiene("((activa|enciende|pon|prende)( el)? (modo )?antirrobo|vigila (mi|el|este) (celular|telefono)|cuida (mi|el) celular)")) {
            return R("Modo antirrobo activado, $tr. Bloquee el celular y déjelo quieto. Si alguien lo mueve o lo desconecta del cargador, sonará una alarma. Se desactiva cuando usted lo desbloquee.", cerrar = true) { Antirrobo.armar(ctx) }
        }
        if (f.tiene("((desactiva|apaga|quita)( el)? (modo )?antirrobo)")) {
            Antirrobo.desarmar(ctx)
            return R("Antirrobo desactivado, $tr.")
        }

        // --- Tipo de cambio ------------------------------------------------------------------------
        Regex("^(?:convierte|cambia|cuanto (?:es|son|serian)|a cuanto equivale(?:n)?)\\s+(\\d+(?:[.,]\\d+)?)\\s+(soles?|dolares?|euros?|pesos? \\w+|reales?|bolivianos?|libras?|yenes?|yuanes?)\\s+(?:a|en)\\s+(soles?|dolares?|euros?|pesos? \\w+|reales?|bolivianos?|libras?|yenes?|yuanes?)$").find(t)?.let { m ->
            val monto = m.groupValues[1].replace(',', '.').toDouble()
            val de = m.groupValues[2]; val a = m.groupValues[3]
            return fondo(cb) {
                try {
                    fun soles(x: String): Double? = if (x.startsWith("sol")) 1.0 else Divisas.moneda(x)?.let { Divisas.enSoles(it.first) }
                    val sd = soles(de); val sa = soles(a)
                    if (sd == null || sa == null) return@fondo R("No conozco esa moneda, $tr.")
                    val r = monto * sd / sa
                    R("${Divisas.redondo(monto)} ${de} son unos ${Divisas.redondo(r)} ${a}, al cambio de hoy.")
                } catch (_: Exception) { R("No pude consultar el tipo de cambio, $tr.") }
            }
        }
        if (f.tiene("(tipo de cambio|a como esta|cuanto esta|precio del|cotizacion del|como esta el|valor del)") && Divisas.moneda(t) != null && !f.tiene("(madrid|partido|equipo|futbol)") || f.tiene("^(tipo de cambio|el dolar|dolar hoy)$")) {
            val (cod, nom) = Divisas.moneda(t) ?: ("USD" to "dólar")
            return fondo(cb) {
                try {
                    val v = Divisas.enSoles(cod)
                    R("El $nom está a ${Divisas.redondo(v)} soles, $tr. Es el cambio de referencia; en las casas de cambio puede variar unos céntimos.")
                } catch (_: Exception) { R("No pude consultar el tipo de cambio ahora, $tr.") }
            }
        }
        return null
    }

    /** Entiende qué quiere hacer en la laptop. */
    private fun pcOrden(f: Frase): Pair<String, String>? {
        val t = f.t
        val sinPc = Regex("\\s*(?:en|de|a|con)?\\s*(?:la|mi)?\\s*$rLaptop\\s*").replace(t, " ").trim()
        return when {
            f.tiene("cancela (el )?apagado") -> "cancelar" to ""
            f.tiene("^(apaga|apagar)") -> "apagar" to ""
            f.tiene("^(reinicia|reiniciar)") -> "reiniciar" to ""
            f.tiene("^(suspende|hiberna|pon a dormir|duerme)") -> "suspender" to ""
            f.tiene("^(bloquea|bloquear)") -> "bloquear" to ""
            f.tiene("(bateria|carga)") -> "bateria" to ""
            f.tiene("(esta (prendida|encendida)|estas conectad|funciona)") -> "estado" to ""
            f.tiene("(silencia|mutea|quita el (sonido|volumen))") -> "volumen" to "silenciar"
            f.tiene("volumen") && Regex("(\\d+)").find(t) != null -> "volumen" to Regex("(\\d+)").find(t)!!.groupValues[1]
            f.tiene("sube (el )?(volumen|sonido)") -> "volumen" to "subir"
            f.tiene("baja (el )?(volumen|sonido)") -> "volumen" to "bajar"
            f.tiene("^(pausa|pausar|continua|reanuda|play)") -> "media" to "play"
            f.tiene("^(siguiente|pasa la cancion|salta)") -> "media" to "siguiente"
            f.tiene("^(anterior|regresa la cancion)") -> "media" to "anterior"
            Regex("^(?:cierra|cerrar)\\s+(.+)$").find(sinPc) != null -> "cerrar" to Regex("^(?:cierra|cerrar)\\s+(.+)$").find(sinPc)!!.groupValues[1]
            Regex("^(?:pon(?:me)?|reproduce|reproducir|toca)\\s+(.+)$").find(sinPc) != null -> "youtube" to Regex("^(?:pon(?:me)?|reproduce|reproducir|toca)\\s+(.+)$").find(sinPc)!!.groupValues[1].replace(Regex("\\s+en youtube$"), "")
            Regex("^(?:busca|buscar|googlea)\\s+(.+)$").find(sinPc) != null -> "buscar" to Regex("^(?:busca|buscar|googlea)\\s+(.+)$").find(sinPc)!!.groupValues[1]
            Regex("^(?:escribe|escribir|dicta)\\s+(.+)$").find(sinPc) != null -> "escribir" to f.original.replace(Regex("(?i)^.*?(escribe|escribir|dicta)\\s+"), "").replace(Regex("(?i)\\s+(en|de)\\s+(la|mi)\\s+(laptop|pc|computadora|compu)\\s*$"), "")
            Regex("^(?:abre|abrir|inicia|ejecuta)\\s+(.+)$").find(sinPc) != null -> {
                val app = Regex("^(?:abre|abrir|inicia|ejecuta)\\s+(.+)$").find(sinPc)!!.groupValues[1]
                if (Regex("\\.(com|pe|org|net)").containsMatchIn(app)) "web" to app.replace(" punto ", ".").replace(" ", "") else "abrir" to app
            }
            else -> null
        }
    }

    /** «Mañana a las 7 mándale a Juan: buenos días». */
    private fun programado(f: Frase): Yarvis.Respuesta? {
        val t = f.t
        val m = Regex("^(.*?)\\b(?:mandale|enviale|escribele|programa(?:me)? un (?:mensaje|whatsapp|sms)|manda(?:me)? un (?:mensaje|whatsapp|sms)|envia(?:me)? un (?:mensaje|whatsapp|sms))\\s+(?:a |para )?(.+?)\\s+(?:que dice|que diga|que|diciendo(?:le)?(?: que)?|:)\\s+(.+)$").find(t) ?: return null
        val antes = m.groupValues[1] + " " + m.groupValues[2]
        val momento = Tiempo.analizar(antes) ?: (if (t.contains("programa")) Tiempo.analizar(t) else null) ?: return null
        if (!momento.conHora && !momento.conDia) return null
        if (momento.cal.timeInMillis < System.currentTimeMillis() + 60_000) return null
        // Nombre sin las palabras de tiempo
        val nombreCrudo = m.groupValues[2]
        val mt = Tiempo.analizar(nombreCrudo)
        var nombre = nombreCrudo
        mt?.rangos?.sortedByDescending { it.first }?.forEach { r -> if (r.last < nombre.length) nombre = nombre.removeRange(r) }
        nombre = nombre.replace(Regex("\\b(a las|para las|a la|el|hoy|manana|pasado)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        val c = Contactos.buscar(ctx, nombre)
        val quien = c?.nombre ?: nombre.split(" ").joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
        var texto = f.originalDeGrupo(m, 3).trim()
        if (t.contains("programa") && Tiempo.analizar(antes) == null) {
            // la hora venía al final del texto: la quitamos del mensaje
            Tiempo.analizar(m.groupValues[3])?.rangos?.sortedByDescending { it.first }?.forEach { r -> if (r.last < texto.length) texto = texto.removeRange(r).trim() }
        }
        val sms = f.tiene("(sms|mensaje de texto)")
        val cuando = momento.cal
        return esperar("Le enviaré a $quien ${Tiempo.describir(cuando)}: «${texto.replaceFirstChar { it.uppercase() }}». ¿Lo programo?") { f2 ->
            if (!rSiGeneral.containsMatchIn(f2.t) && !f2.tiene("^(programalo|programa|dale)")) return@esperar R("No lo programé, $tr.", cerrar = true)
            Programados.crear(ctx, quien, texto.replaceFirstChar { it.uppercase() }, cuando.timeInMillis, sms)
            R("Programado, $tr. Lo enviaré ${Tiempo.describir(cuando)}.", cerrar = true)
        }
    }

    /** Envía un mensaje programado a su hora. Devuelve qué pasó (para la notificación). */
    fun enviarProgramado(quien: String, texto: String, sms: Boolean): String {
        val c = resolverContacto(quien) ?: return "No encontré a $quien en sus contactos para enviarle: «$texto»"
        return enviarMensaje(c, texto, sms).texto
    }

    /** ¿Le mando un saludo de cumpleaños? */
    fun decidirSaludo(nombre: String, f: Frase): Yarvis.Respuesta? {
        if (f.tiene("^(no|despues|luego|ahora no|dejalo)\\b")) return R("De acuerdo, $tr.", cerrar = true)
        val propio = Regex("^(?:si,? )?(?:dile|ponle|escribele)(?: que)?\\s+(.+)$").find(f.t)
        if (propio == null && !rSiGeneral.containsMatchIn(f.t)) return null
        val c = resolverContacto(nombre) ?: return R("No encontré a $nombre en sus contactos, $tr.")
        if (propio != null) return enviarMensaje(c, f.originalDeGrupo(propio, 1), porSms = false)
        Thread {
            val saludo = Cumpleanos.redactarSaludo(c.nombre)
            val rDile = Regex("^(?:mejor |no,? )?(?:dile|ponle|escribele)(?: que)?\\s+(.+)$")
            Yarvis.decirYEscuchar(esperar("Le escribo a ${c.nombre}: «$saludo». ¿Lo envío?") { f2 ->
                val otro = rDile.find(f2.t)
                when {
                    f2.tiene("^(no|cancela|dejalo)\\b") && otro == null -> R("No lo envié, $tr.", cerrar = true)
                    otro != null -> enviarMensaje(c, f2.originalDeGrupo(otro, 1), porSms = false)
                    rSiGeneral.containsMatchIn(f2.t) || f2.tiene("^(envia|envialo|mandalo)") -> enviarMensaje(c, saludo, porSms = false)
                    else -> R("No lo envié, $tr.", cerrar = true)
                }
            })
        }.start()
        return R("Un momento, le escribo el saludo…", cerrar = true)
    }

    // =====================================================================
    //  Funciones nuevas (v3.1)
    // =====================================================================

    private val rSiGeneral = Regex("^(si|sip|claro|dale|ok|okay|okey|de acuerdo|hazlo|correcto|perfecto|listo|por favor|enviala|envialo|mandalo|mandala|leelo|leela)\\b")

    private fun nuevas(f: Frase, cb: (Yarvis.Respuesta) -> Unit): Yarvis.Respuesta? {
        val t = f.t

        // --- Encontrar el celular ------------------------------------------------
        if (Localizador.sonando && f.tiene("(ya (te|lo) encontre|deja de sonar|para de sonar|callate|silencio|apagate|ya esta)")) {
            Localizador.parar(ctx); return R("Me alegra que me encontrara, $tr.", cerrar = true)
        }
        if (f.tiene("(donde estas|donde te metiste|no te encuentro|no encuentro (mi|el) (celular|telefono|movil)|haz sonar (el|mi|este) (celular|telefono)|suena fuerte|busca(me)? mi (celular|telefono)|(perdi|se me perdio) (mi|el) (celular|telefono))")) {
            return R("¡Aquí estoy, $tr!", cerrar = true) { Localizador.sonar(ctx) }
        }
        if (f.tiene("aplau") && f.tiene("(activa|enciende|prende|usa|quiero|cuando)")) {
            Prefs.aplausos = true
            if (Prefs.modoEscucha != "siempre") Prefs.modoEscucha = "siempre"
            ServicioEscucha.reiniciar()
            return R("Listo, $tr. Si no me encuentra, aplauda dos veces seguidas y sonaré fuerte. Solo funciona con la pantalla apagada, para no confundirme con otros ruidos.")
        }
        if (f.tiene("aplau") && f.tiene("(desactiva|apaga|quita|ya no)")) {
            Prefs.aplausos = false
            ServicioEscucha.reiniciar()
            return R("Desactivé los aplausos, $tr.")
        }

        // --- Rutina de buenos días -------------------------------------------------
        val hora = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if ((f.tiene("^(buenos dias|buen dia|buenas)( odin| yarvis)?$") && hora in 4..12) ||
            f.tiene("(rutina de (la )?manana|resumen del dia|mi resumen de hoy|como empieza mi dia|informe del dia|mi informe de hoy|que me espera hoy)")) {
            return fondo(cb) { rutinaManana() }
        }

        // --- Modos -------------------------------------------------------------------
        if (f.tiene("(modo (manejo|manejar|conduccion|conducir|auto|carro|carretera)|voy a manejar|estoy manejando|voy manejando|voy a conducir|estoy conduciendo)")) return R(Modos.activar(ctx, Modos.MANEJO))
        if (f.tiene("(modo (dormir|noche|sueno|descanso)|me voy a dormir|voy a dormir|hora de dormir|ya me duermo)")) return R(Modos.activar(ctx, Modos.DORMIR), cerrar = true)
        if (f.tiene("(modo (reunion|trabajo|clase|clases|iglesia|misa)|estoy en (una )?reunion|entro a (una )?reunion|voy a entrar a (una )?reunion|estoy en clase)")) return R(Modos.activar(ctx, Modos.REUNION), cerrar = true)
        if (f.tiene("(en que modo estas|que modo tienes|modo actual)")) return R("Estoy en ${Modos.nombre()}, $tr.")
        if (Modos.actual != Modos.NORMAL && f.tiene("(modo normal|desactiva el modo|quita el modo|sal del modo|salir del modo|ya llegue|ya desperte|ya me desperte|termino la reunion|ya sali de (la )?reunion|ya termine de manejar|desactiva el modo (manejo|dormir|reunion))")) {
            return R(Modos.desactivar(ctx))
        }

        // --- Traductor ----------------------------------------------------------------
        Regex("(?:modo traductor|traductor|se mi traductor|traduce (?:mi|nuestra|la|esta) conversacion|ayudame a (?:hablar|conversar)(?: con alguien)?|quiero hablar)\\s+(?:al|del|de|en|a|con alguien en|con alguien que habla)\\s+(.+)$").find(t)?.let { m ->
            val i = Traductor.idioma(m.groupValues[1]) ?: if (f.tiene("traduc")) return R("Ese idioma todavía no lo manejo en conversación, $tr. Puedo con inglés, portugués, francés, italiano, alemán, chino, japonés, coreano y otros.") else return@let
            Traductor.iniciar(i)
            return R("Modo traductor al ${i.nombre}, $tr. Hable usted primero en español; yo lo diré en ${i.nombre} y luego escucharé a la otra persona. Para terminar, diga «termina la traducción».", seguir = true)
        }

        // --- Cámara: ¿qué ves?, leer y traducir con la cámara ----------------------------------
        val enPantalla = f.tiene("(pantalla|esta pagina|este articulo|este chat|este mensaje|esta conversacion|este correo|esta noticia|esta web|este post|esta publicacion)")
        if (!enPantalla) {
            if (f.tiene("^(que ves|que estas viendo|mira esto|que es esto|que es eso|describe lo que ves|dime que ves|que tengo (aqui|en la mano)|que hay (frente a mi|delante)|usa la camara|mira con la camara)")) {
                return camara("Describe brevemente lo que ves en esta foto (de 2 a 4 oraciones). Si es un objeto, producto, planta, animal o lugar, di qué es. Si hay texto importante, menciónalo.", "¿Qué veo?")
            }
            Regex("^que (planta|animal|marca|modelo|raza|insecto|hongo|comida|plato|flor|arbol|pajaro|ave|piedra|medicina|pastilla|billete|moneda) es (esta|este|esto|esa|ese)").find(t)?.let {
                return camara("${f.original}. Responde mirando la foto, en 2 a 4 oraciones. Si es una medicina, di qué es y recomienda consultar al médico o farmacéutico antes de tomarla.", "Mirando…")
            }
            if (f.tiene("^(lee|leeme|lee me)( con la camara)? (este|esta|el|la|lo que dice (el|la|este|esta)) ?(documento|papel|hoja|carta|cartel|letrero|libro|receta|etiqueta|boleta|recibo|factura|aviso|pizarra|cuaderno|pagina del libro|instrucciones|empaque|caja)") || f.tiene("lee con la camara")) {
                return camara("Lee en voz alta, completo y en orden, todo el texto que aparece en la foto. No describas la imagen; solo el texto, corrigiendo errores evidentes de lectura. Si pasa de 400 palabras, lee lo principal y ofrece leer el resto.", "Leyendo el documento")
            }
            Regex("^traduce(?:me)? (?:esto|este|esta|el|la)(?: (?:cartel|letrero|texto|papel|documento|menu|carta|etiqueta|aviso|instrucciones|empaque|caja))?(?: con la camara)?(?: al (.+))?$").find(t)?.let { m ->
                val idioma = m.groups[1]?.let { Traductor.idioma(it.value)?.nombre } ?: "español"
                return camara("Traduce al $idioma todo el texto que aparece en la foto. Responde solo con la traducción, natural y lista para leer en voz alta.", "Traduciendo")
            }
        }

        // --- Pantalla: leer, resumir, explicar, describir, traducir -------------------------------
        if (f.tiene("^(lee|leeme|lee me|leer) (esto|eso|aqui|la pantalla|lo que (dice|hay en|sale en) (la )?pantalla|esta pagina|este articulo|este mensaje|este chat|esta conversacion|este correo|esta noticia|este post|esta publicacion|lo que tengo en (la )?pantalla)") ||
            f.tiene("^que dice (aqui|la pantalla|esto|esta pagina|este mensaje)")) return pantalla("leer", null, cb)
        if (f.tiene("^(resume|resumeme|resumir|resumen de|hazme un resumen de|haz un resumen de|dame un resumen de) (esto|eso|la pantalla|esta pagina|este articulo|este chat|esta conversacion|este correo|esta noticia|este post|lo que hay en (la )?pantalla)") ||
            f.tiene("^de que (trata|se trata) (esto|esta pagina|este articulo|este video|esta noticia)")) return pantalla("resumir", null, cb)
        if (f.tiene("^(explicame|explica|que significa) (esto|eso|lo que hay en (la )?pantalla|esta pagina|este mensaje)")) return pantalla("explicar", null, cb)
        if (f.tiene("(que (hay|ves|sale) en (la|mi) pantalla|describe (la|mi) pantalla|que estoy viendo|mira (la|mi) pantalla)")) return pantalla("describir", null, cb)
        Regex("^traduce(?:me)? (?:la pantalla|esta pagina|este mensaje|este chat|este articulo|este correo|esta conversacion|esto que tengo en (?:la )?pantalla)(?: al (.+))?$").find(t)?.let { m ->
            return pantalla("traducir", m.groups[1]?.let { Traductor.idioma(it.value)?.nombre } ?: "español", cb)
        }

        // --- Traducción suelta: «cómo se dice gracias en inglés» -------------------------------------
        Regex("^(?:como se dice|como digo|traduce(?:me)?|traducir|dime en|como se escribe)\\s+(.+?)\\s+(?:al|en)\\s+(ingles|portugues|frances|italiano|aleman|chino|mandarin|japones|coreano|ruso|arabe|hindi|turco|neerlandes|holandes|polaco)$").find(t)?.let { m ->
            val i = Traductor.idioma(m.groupValues[2]) ?: return@let
            val texto = f.originalDeGrupo(m, 1).trim('"', '«', '»', ' ')
            return fondo(cb) {
                val tr2 = Traductor.traducir(texto, "es", i.codigo) ?: return@fondo R("No pude traducirlo ahora, $tr.")
                Yarvis.Respuesta(tr2, idiomaVoz = i.voz, detalle = "En ${i.nombre}: $tr2")
            }
        }

        // --- Mensajes: ¿qué me escribió Juan?, resumen del día --------------------------------
        Regex("^(?:que me (?:escribio|dijo|mando|puso|envio|respondio|contesto)|(?:lee(?:me)?|que dicen|repiteme) (?:los |el |sus )?(?:mensajes?|whatsapps?|chats?) (?:de|del)|(?:tengo|hay) mensajes? (?:de|del)|mensajes de)\\s+(.+?)(?:\\s+(?:por whatsapp|por messenger|hoy|ayer))?$").find(t)?.let { m ->
            val quien = f.originalDeGrupo(m, 1)
            if (Regex("^(hoy|ayer|whatsapp|todos|mis)").containsMatchIn(m.groupValues[1])) return@let
            return mensajesDe(quien, cb)
        }
        if (f.tiene("(resume|resumeme|resumir|resumen de|hazme un resumen de) (mis|los) (mensajes|whatsapps|chats)|que me escribieron( hoy)?|quien(es)? me escribi(o|eron) hoy|mensajes de hoy|que mensajes tengo hoy")) {
            return resumenMensajes(cb)
        }

        // --- Gastos e ingresos --------------------------------------------------------------
        Gastos.registrar(f)?.let { g ->
            val hoy = Gastos.entre(Gastos.periodo("hoy").first, System.currentTimeMillis() + 1).filter { !it.ingreso }.sumOf { it.monto }
            return if (g.ingreso) R("Anotado, $tr: ingreso de ${Gastos.soles(g.monto)}${if (g.concepto != "Ingreso") " por ${g.concepto.lowercase()}" else ""}.")
            else R("Anotado, $tr: ${Gastos.soles(g.monto)} en ${g.concepto.lowercase()}. Hoy lleva gastados ${Gastos.soles(hoy)}.")
        }
        if (f.tiene("(borra|elimina|quita|anula|borrar) (el )?ultimo (gasto|ingreso|movimiento|registro)")) {
            val g = Gastos.quitarUltimo() ?: return R("No tiene gastos anotados, $tr.")
            return R("Borré ${if (g.ingreso) "el ingreso" else "el gasto"} de ${Gastos.soles(g.monto)} en ${g.concepto.lowercase()}, $tr.")
        }
        if (f.tiene("(cuanto (he )?(gaste|gastado|gasto|llevo gastado|me gaste)|mis gastos|en que (he )?(gaste|gastado)|resumen de (mis )?gastos|cuanto (he )?(recibido|ingresado|cobrado)|mis ingresos|como van mis (gastos|finanzas|cuentas)|mi balance)")) {
            val concepto = Regex("(?:gast\\w*|gaste) (?:en|de) (?!que)(.+?)(?: (?:hoy|ayer|esta semana|este mes|la semana pasada|el mes pasado|este ano|en total))?$").find(t)?.groupValues?.get(1)
                ?.takeIf { !Regex("^(total|la semana|el mes|el ano|hoy|ayer)").containsMatchIn(it) }
            return R(Gastos.informe(t, concepto))
        }

        // --- Correo (Gmail) -------------------------------------------------------------------
        Regex("^(?:manda(?:le)?|envia(?:le)?|escribe(?:le)?|redacta)(?:me)? (?:un |una )?(?:correo|email|mail|e mail|e-mail|correo electronico) (?:a|para) (.+?)(?:\\s+(?:diciendo(?:le)?|que dice|que diga|con el mensaje|que|y dile que|y dile)\\s+(.+))?$").find(t)?.let { m ->
            val dest = f.originalDeGrupo(m, 1)
            val texto = m.groups[2]?.let { f.originalDeGrupo(m, 2) }
            return correoNuevo(dest, texto)
        }
        if (f.tiene("^(responde|respondele|contesta|contestale)( a)? (el|ese|este|al|su) (correo|email|mail)") || f.tiene("^(responde|respondele|contestale) por correo")) {
            val texto = Regex("(?:correo|email|mail)(?: de [a-z ]+?)?\\s+(?:diciendo(?:le)?|que dice|que|:)\\s+(.+)$").find(t)?.let { f.originalDeGrupo(it, 1) }
            return responderCorreo(texto, cb)
        }
        if (f.tiene("^(lee|leeme|que dice|abre) (el )?(primer|primero|segundo|tercer|tercero|cuarto|quinto|ultimo)? ?(correo|email|mail)") || f.tiene("^(lee|leeme|que dice) el (correo|email|mail) (de|del) ")) {
            return fondo(cb) {
                if (Correo.ultimos.isEmpty() && Correo.configurado) try { Correo.recientes() } catch (_: Exception) {}
                val m = Correo.elegir(t) ?: Correo.ultimos.firstOrNull() ?: return@fondo R(if (Correo.configurado) "No tengo correos para leer, $tr." else correoSinConfigurar())
                leerCorreo(m)
            }
        }
        if (f.tiene("((tengo|hay) (correos|emails|mails|correo)|revisa (mi|el) (correo|gmail|email)|lee(me)? (mis|los) (correos|emails|mails)|correos nuevos|mi bandeja de entrada|que correos tengo|me llego (algun )?correo|mis correos)")) {
            return correosNuevos(cb)
        }

        // --- Copia de seguridad ---------------------------------------------------------------
        if (f.tiene("((haz|has|hazme|crea|guarda|saca) (una )?(copia de seguridad|copia de respaldo|respaldo|backup)|respalda (mis datos|todo|tus datos)|guarda (mis|tus) datos)")) {
            return fondo(cb) {
                if (Respaldo.guardarEnDescargas(ctx)) R("Listo, $tr. Guardé una copia de todo (su voz, sus chats, sus apps, notas y gastos) en Descargas, carpeta ODIN. Si reinstala, restáurela desde Ajustes, Datos.")
                else R("No pude guardar la copia, $tr. Inténtelo desde Ajustes, Datos.")
            }
        }
        return null
    }

    // --- Rutina ---------------------------------------------------------------------------
    private fun rutinaManana(): Yarvis.Respuesta {
        val partes = mutableListOf<String>()
        val nombre = Prefs.nombre.substringBefore(' ').ifBlank { tr }
        val fecha = SimpleDateFormat("EEEE d 'de' MMMM", es).format(Date())
        val h = SimpleDateFormat("h:mm a", es).format(Date()).replace("a. m.", "de la mañana").replace("p. m.", "de la tarde").replace("AM", "de la mañana").replace("PM", "de la tarde")
        partes.add("${saludo()}, $nombre. Hoy es $fecha y son las $h.")
        try { partes.add(Clima.informe(ctx, null, 0)) } catch (_: Exception) {}
        try {
            val ev = Agenda.eventos(ctx, 0).orEmpty()
            val rec = Recordatorios.lista().filter { Tiempo.diasEntre(Calendar.getInstance(), Calendar.getInstance().apply { timeInMillis = it.cuando }) == 0 }
                .map { "${SimpleDateFormat("h:mm a", es).format(Date(it.cuando))}, ${it.texto}" }
            val todo = ev + rec
            partes.add(if (todo.isEmpty()) "No tiene nada en su agenda para hoy." else "En su agenda de hoy: ${todo.joinToString(". ")}.")
        } catch (_: Exception) {}
        try {
            val cumples = Cumpleanos.deHoy(ctx)
            if (cumples.isNotEmpty()) partes.add("Hoy cumple años ${cumples.joinToString(" y ") { it.nombre }}; si quiere, le mando un saludo.")
        } catch (_: Exception) {}
        try {
            val noche = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, -12) }.timeInMillis
            val msj = Bandeja.todos().filter { it.hora >= noche }
            if (msj.isNotEmpty()) partes.add("Mientras descansaba recibió ${if (msj.size == 1) "un mensaje" else "${msj.size} mensajes"}: ${Bandeja.conteo(msj)}.")
        } catch (_: Exception) {}
        try {
            val tit = Noticias.titulares(null, 3)
            if (tit.isNotEmpty()) partes.add("Las noticias: " + tit.joinToString(". ") { it.titulo } + ".")
        } catch (_: Exception) {}
        partes.add(listOf("Que tenga un excelente día.", "Estoy a su servicio para lo que necesite.", "¡A darle con todo hoy!").random())
        return R(partes.joinToString(" "), cerrar = true)
    }

    // --- Cámara ------------------------------------------------------------------------------
    private fun camara(instruccion: String, titulo: String): Yarvis.Respuesta {
        if (!CerebroIA.disponible()) return R("Para ver con la cámara necesito la inteligencia artificial activa, $tr.")
        return R("Apunte la cámara, $tr.", cerrar = true) { CamaraActivity.abrir(ctx, instruccion, titulo) }
    }

    private fun fotoReciente(): ByteArray? =
        CamaraActivity.ultimaFoto?.takeIf { System.currentTimeMillis() - CamaraActivity.ultimaFotoHora < 150_000 }

    // --- Pantalla -----------------------------------------------------------------------------
    private fun pantalla(modo: String, idioma: String?, cb: (Yarvis.Respuesta) -> Unit): Yarvis.Respuesta {
        if (!ServicioAccesibilidad.activo) {
            lanzar(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return R("Para ver su pantalla necesito el permiso de accesibilidad, $tr. Active ODÍN en la pantalla que le abrí.", cerrar = true)
        }
        return fondo(cb) {
            val texto = if (modo == "describir") null else ServicioAccesibilidad.textoPantalla()
            if (texto.isNullOrBlank() || texto.length < 40) {
                // Poco texto (una foto, un video…): mira una captura de la pantalla
                val img = capturaSinPanel() ?: return@fondo R("No pude ver su pantalla, $tr.")
                val instr = when (modo) {
                    "leer" -> "Lee en voz alta el texto principal que aparece en esta captura de pantalla de un celular (ignora menús, botones y barras)."
                    "resumir" -> "Resume en 3 a 5 oraciones el contenido principal de esta captura de pantalla de un celular."
                    "explicar" -> "Explica de forma sencilla lo que aparece en esta captura de pantalla de un celular."
                    "traducir" -> "Traduce al ${idioma ?: "español"} el texto principal de esta captura de pantalla. Responde solo con la traducción."
                    else -> "Describe brevemente lo que se ve en esta captura de pantalla de un celular: qué app es y qué muestra (imágenes, video, texto principal)."
                }
                return@fondo try { R(CerebroIA.verImagen(img, instr), seguir = true) } catch (e: Exception) { R(CerebroIA.mensajeError(e), cerrar = true, detalle = CerebroIA.detalle(e)) }
            }
            if (!CerebroIA.disponible()) {
                return@fondo if (modo == "leer") R(texto.lines().joinToString(". ").take(1200)) else R("Para eso necesito la inteligencia artificial activa, $tr.")
            }
            val sistema = when (modo) {
                "leer" -> "Recibes el texto que aparece en la pantalla del celular del usuario, mezclado con menús, botones y barras. Devuelve SOLO el contenido principal (el artículo, los mensajes, la noticia…), en orden y listo para leer en voz alta, sin comentarios tuyos. En un chat, indica quién dice cada mensaje. Si pasa de 350 palabras, entrega las primeras 350 y termina con: ¿Sigo leyendo?"
                "resumir" -> "Recibes el texto que aparece en la pantalla del celular del usuario (con menús y botones). Resume el contenido principal en 3 a 5 oraciones claras, para ser escuchadas. Sin listas ni markdown."
                "explicar" -> "Recibes el texto que aparece en la pantalla del celular del usuario. Explica de forma sencilla y breve qué significa o de qué trata el contenido principal. Sin listas ni markdown."
                else -> "Traduce al ${idioma ?: "español"} el contenido principal de este texto de una pantalla de celular (ignora menús y botones). Responde solo con la traducción."
            }
            try {
                val r = CerebroIA.preguntar(texto.take(9000), buscar = false, sistemaFijo = sistema, recordar = false)
                CerebroIA.recordar("(contenido de mi pantalla)", r)
                R(r, seguir = true)
            } catch (e: Exception) {
                if (modo == "leer") R(texto.lines().joinToString(". ").take(1200)) else R(CerebroIA.mensajeError(e), cerrar = true, detalle = CerebroIA.detalle(e))
            }
        }
    }

    /** Captura la pantalla sin la burbuja de ODÍN encima. Se llama desde un hilo secundario. */
    private fun capturaSinPanel(): ByteArray? {
        principal.post { VentanaFlotante.ocultar() }
        Thread.sleep(650)
        val listo = java.util.concurrent.CountDownLatch(1)
        var img: ByteArray? = null
        ServicioAccesibilidad.capturaPantalla { b -> img = b; listo.countDown() }
        listo.await(5, java.util.concurrent.TimeUnit.SECONDS)
        principal.post { VentanaFlotante.mostrar(ctx) }
        return img
    }

    // --- Mensajes recibidos ---------------------------------------------------------------------
    private fun mensajesDe(quien: String, cb: (Yarvis.Respuesta) -> Unit): Yarvis.Respuesta {
        val l = Bandeja.de(quien)
        if (l.isEmpty()) return R("No tengo mensajes de $quien en los últimos dos días, $tr.")
        val nombre = l.last().quien
        if (l.size <= 4 || !CerebroIA.disponible()) {
            val leer = l.takeLast(5).joinToString(" ") { "A las ${Bandeja.hora(it)}: «${it.texto}»." }
            return R("$nombre le escribió. $leer ¿Le respondo?", seguir = true).also { ofrecerResponder(nombre) }
        }
        return fondo(cb) {
            val r = try {
                CerebroIA.preguntar(Bandeja.comoTexto(l), buscar = false, recordar = false,
                    sistemaFijo = "Resume para el usuario, en 2 a 4 oraciones y en voz natural, lo que le escribió $nombre en estos mensajes (lo importante, preguntas pendientes, horarios o pedidos). Trátalo de ${if (Prefs.modoConfianza) "tú" else "usted"}. No inventes nada.")
            } catch (_: Exception) { l.takeLast(4).joinToString(" ") { "«${it.texto}»." } }
            ofrecerResponder(nombre)
            R("$r ¿Le respondo?", seguir = true)
        }
    }

    private fun ofrecerResponder(nombre: String) {
        pendiente = { f2 -> respuestaManejo(nombre, f2) }
        pendienteHasta = System.currentTimeMillis() + 90_000
    }

    private fun resumenMensajes(cb: (Yarvis.Respuesta) -> Unit): Yarvis.Respuesta {
        val l = Bandeja.deHoy()
        if (l.isEmpty()) return R("Hoy no ha recibido mensajes, $tr.")
        if (!CerebroIA.disponible()) return R("Hoy recibió ${l.size} mensajes: ${Bandeja.conteo(l)}.")
        return fondo(cb) {
            val r = try {
                CerebroIA.preguntar(Bandeja.comoTexto(l), buscar = false, recordar = false,
                    sistemaFijo = "Eres el asistente de voz del usuario. Resume los mensajes que recibió hoy, agrupados por persona, en un párrafo natural para ser escuchado (máximo 8 oraciones). Destaca preguntas que esperan respuesta, citas, horarios y pedidos. Trátalo de ${if (Prefs.modoConfianza) "tú" else "usted"}. No inventes nada. Sin listas ni markdown.")
            } catch (_: Exception) { "Hoy recibió ${l.size} mensajes: ${Bandeja.conteo(l)}." }
            CerebroIA.recordar("resume mis mensajes de hoy", r)
            R(r, seguir = true)
        }
    }

    /** Respuesta por voz a un mensaje (modo manejo o «¿Le respondo?»). */
    fun respuestaManejo(quien: String, f: Frase): Yarvis.Respuesta? {
        val t = f.t
        val directo = Regex("^(?:si,? )?(?:dile|respondele|contestale|responde|contesta|escribele|ponle)(?: que)?\\s+(.+)$").find(t)
        return when {
            directo != null -> enviarRespuesta(quien, f.originalDeGrupo(directo, 1))
            rSiGeneral.containsMatchIn(t) -> esperar("¿Qué le respondo a $quien, $tr?") { f2 ->
                val txt = f2.original.trim().replace(Regex("(?i)^(dile\\s+)?que\\s+"), "")
                if (txt.isBlank()) R("No le respondí, $tr.", cerrar = true) else enviarRespuesta(quien, txt)
            }
            f.tiene("^(no|despues|luego|ahora no|dejalo|nada)\\b") -> R("De acuerdo, $tr.", cerrar = true)
            else -> null
        }
    }

    /** Mensaje rápido a un contacto (al rechazar una llamada: «dile que lo llamo luego»). */
    fun mensajeRapido(quien: String, texto: String, paquete: String): Yarvis.Respuesta {
        val limpio = texto.trim().replace(Regex("(?i)^que\\s+"), "").replaceFirstChar { it.uppercase() }
        val c = resolverContacto(quien) ?: return R("Rechacé la llamada, $tr, pero no encontré a $quien en sus contactos para escribirle.", cerrar = true)
        val r = enviarMensaje(c, limpio, porSms = false)
        return R("Rechacé la llamada. ${r.texto}", cerrar = true)
    }

    // --- Correo --------------------------------------------------------------------------------
    private fun correoSinConfigurar(): String =
        "Para leer y enviar correos necesito que configure su Gmail en Ajustes, sección Correo, $tr. Le tomará un minuto."

    private fun correosNuevos(cb: (Yarvis.Respuesta) -> Unit): Yarvis.Respuesta = fondo(cb) {
        if (!Correo.configurado) {
            // Sin configurar: al menos lo que llegó como notificación de Gmail
            val gm = Bandeja.todos().filter { it.app.equals("Gmail", true) && it.hora > System.currentTimeMillis() - 86_400_000 }
            return@fondo if (gm.isEmpty()) R(correoSinConfigurar())
            else R("Según las notificaciones de Gmail, recibió: ${gm.takeLast(5).joinToString(". ") { "de ${it.quien}: ${it.texto.take(120)}" }}. ${correoSinConfigurar()}")
        }
        val l = try { Correo.nuevos() } catch (e: Exception) { return@fondo R("No pude revisar su correo, $tr. ${Correo.probar() ?: ""}".trim(), detalle = e.javaClass.simpleName) }
        if (l.isEmpty()) return@fondo R("No tiene correos nuevos, $tr.")
        val orden = listOf("El primero", "El segundo", "El tercero", "El cuarto", "El quinto", "El sexto", "El séptimo", "El octavo")
        val lista = l.take(5).mapIndexed { i, m -> "${orden[i]}, de ${m.de}: ${m.asunto}." }.joinToString(" ")
        pendiente = { f2 ->
            val m = Correo.elegir(f2.t) ?: if (rSiGeneral.containsMatchIn(f2.t)) Correo.ultimos.firstOrNull() else null
            if (m == null) null else fondo(cb) { leerCorreo(m) }
        }
        pendienteHasta = System.currentTimeMillis() + 90_000
        R("Tiene ${if (l.size == 1) "un correo nuevo" else "${l.size} correos nuevos"}, $tr. $lista ¿Le leo alguno?", seguir = true)
    }

    /** Se llama desde un hilo secundario. */
    private fun leerCorreo(m: Correo.Mail): Yarvis.Respuesta {
        val cuerpo = try { Correo.cuerpo(m) } catch (e: Exception) { return R("No pude abrir ese correo, $tr.", detalle = e.javaClass.simpleName) }
        val texto = if (cuerpo.length > 900 && CerebroIA.disponible()) try {
            "En resumen: " + CerebroIA.preguntar(cuerpo.take(9000), buscar = false, recordar = false,
                sistemaFijo = "Resume este correo en 3 a 5 oraciones para ser escuchado: qué quieren, fechas, montos y qué hay que hacer. Sin listas ni markdown.")
        } catch (_: Exception) { cuerpo.take(900) } else cuerpo.take(1200)
        Correo.ultimoLeido = m
        pendiente = { f2 -> responderCorreoDesdeDialogo(f2) }
        pendienteHasta = System.currentTimeMillis() + 120_000
        return R("Correo de ${m.de}, asunto: ${m.asunto}. $texto ¿Le respondo?", seguir = true)
    }

    private fun responderCorreoDesdeDialogo(f: Frase): Yarvis.Respuesta? {
        val directo = Regex("^(?:si,? )?(?:dile|respondele|contestale|responde|contesta|escribele|ponle)(?: que)?\\s+(.+)$").find(f.t)
        return when {
            directo != null -> confirmarRespuestaCorreo(f.originalDeGrupo(directo, 1))
            rSiGeneral.containsMatchIn(f.t) -> esperar("¿Qué le respondo, $tr?") { f2 -> confirmarRespuestaCorreo(f2.original) }
            f.tiene("^(no|despues|luego|ahora no|dejalo|nada)\\b") -> R("De acuerdo, $tr.", cerrar = true)
            else -> null
        }
    }

    private fun responderCorreo(texto: String?, cb: (Yarvis.Respuesta) -> Unit): Yarvis.Respuesta {
        if (!Correo.configurado) return R(correoSinConfigurar())
        if (Correo.ultimoLeido == null) return R("Primero dígame qué correo quiere responder, $tr. Por ejemplo: lee el correo de Juan.")
        return if (texto.isNullOrBlank()) esperar("¿Qué le respondo a ${Correo.ultimoLeido?.de}, $tr?") { f2 -> confirmarRespuestaCorreo(f2.original) }
        else confirmarRespuestaCorreo(texto)
    }

    private fun confirmarRespuestaCorreo(textoCrudo: String): Yarvis.Respuesta {
        val m = Correo.ultimoLeido ?: return R("No sé a qué correo responder, $tr.")
        val texto = textoCrudo.trim().replace(Regex("(?i)^(dile\\s+)?que\\s+"), "").replaceFirstChar { it.uppercase() }
        return esperar("Le respondo a ${m.de}: «$texto». ¿Lo envío?") { f2 ->
            if (!rSiGeneral.containsMatchIn(f2.t) && !f2.tiene("^(envia|envialo|mandalo|enviar)")) return@esperar R("No lo envié, $tr.", cerrar = true)
            Thread {
                val r = try {
                    val asunto = if (m.asunto.startsWith("Re:", true)) m.asunto else "Re: ${m.asunto}"
                    Correo.enviar(m.deCorreo, asunto, texto + firma(), m)
                    "Respuesta enviada a ${m.de}, $tr."
                } catch (e: Exception) { "No pude enviar la respuesta, $tr." }
                Yarvis.anunciar(r)
            }.start()
            R("Enviando…", cerrar = true)
        }
    }

    private fun firma() = Prefs.nombre.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()

    private fun correoNuevo(dest: String, texto: String?): Yarvis.Respuesta {
        if (!Correo.configurado) return R(correoSinConfigurar())
        val directo = Correo.correoDicho(dest)
        val contacto = if (directo == null) Correo.deContacto(ctx, dest) else null
        val para = directo ?: contacto?.second ?: return R("No encontré el correo de $dest en sus contactos, $tr. Puede dictármelo, por ejemplo: juan punto perez arroba gmail punto com.")
        val nombre = contacto?.first ?: para
        if (texto.isNullOrBlank()) return esperar("¿Qué le escribo a $nombre, $tr?") { f2 -> confirmarCorreo(para, nombre, f2.original) }
        return confirmarCorreo(para, nombre, texto)
    }

    private fun confirmarCorreo(para: String, nombre: String, textoCrudo: String): Yarvis.Respuesta {
        val texto = textoCrudo.trim().replace(Regex("(?i)^(que|diciendo(le)?( que)?)\\s+"), "").replaceFirstChar { it.uppercase() }
        val asunto = texto.split(Regex("[.!?\\n]")).first().take(60).trim().ifBlank { "Mensaje de ${Prefs.nombre.ifBlank { "ODÍN" }}" }
        return esperar("Le escribo a $nombre: «$texto». ¿Lo envío?") { f2 ->
            if (!rSiGeneral.containsMatchIn(f2.t) && !f2.tiene("^(envia|envialo|mandalo|enviar)")) return@esperar R("No lo envié, $tr.", cerrar = true)
            Thread {
                val r = try { Correo.enviar(para, asunto, texto + firma()); "Correo enviado a $nombre, $tr." } catch (e: Exception) { "No pude enviar el correo, $tr." }
                Yarvis.anunciar(r)
            }.start()
            R("Enviando…", cerrar = true)
        }
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
        if (!ServicioNotificaciones.listo(ctx)) {
            lanzar(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return R("Para responder mensajes necesito acceso a sus notificaciones, $tr. Active ODÍN en la pantalla que le abrí.", cerrar = true)
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
        val preferida = MisApps.elegida(ctx, "mensajes")
        val abierto = (listOfNotNull(preferida) + listOf("com.whatsapp", "com.whatsapp.w4b")).distinct().plus(listOf<String?>(null)).any { pkg ->
            lanzar(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { if (pkg != null) setPackage(pkg) })
        }
        if (!abierto) return R("No encontré WhatsApp en este celular, $tr.")
        return if (auto) R("Enviando a $nombre por WhatsApp: «$texto».")
        else R("Le dejé el mensaje listo para $nombre. Solo toque enviar, $tr. Si activa la accesibilidad de ODÍN, lo enviaré yo mismo.")
    }

    private fun leerNotificaciones(): Yarvis.Respuesta {
        if (!ServicioNotificaciones.listo(ctx)) {
            lanzar(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return R("Necesito acceso a sus notificaciones para leerlas, $tr. Active ODÍN en la pantalla que le abrí.")
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
                "youtube", "you tube" -> youtube(q, cb, MisApps.youtube(ctx))
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
        // "pon X en <cualquier app suya>" → por su nombre propio o el nombre de la app instalada
        Regex("^(?:pon(?:me)?|reproduce|reproducir|toca|busca(?:me)?|buscar|quiero ver|quiero escuchar|muestrame|ver|mira|escuchar)\\s+(.+?)\\s+en\\s+(?:la app |la aplicacion |el |mi )?([^\\s].{1,30})$").find(t)?.let { m3 ->
            if (Regex("^(una|un)\\s+(alarma|temporizador|recordatorio)").containsMatchIn(m3.groupValues[1])) return@let
            val app = MisApps.buscarApp(ctx, m3.groupValues[2]) ?: return@let
            val q = f.originalDeGrupo(m3, 1).replace(Regex("^(?i)(el|la|los|las|un|una)\\s+(video|cancion|tema)\\s+(de\\s+)?"), "")
            return reproducirEn(app.paquete, q, cb)
        }
        // "pon / reproduce X" sin sitio → la app elegida en «Mis apps» (música o videos)
        Regex("^(?:pon(?:me)?|reproduce|reproducir|toca|quiero ver|quiero escuchar|escuchar|ver)\\s+(?:(?:el|un) video de\\s+|(?:la|una) cancion de\\s+|la cancion\\s+|el video\\s+|el tema\\s+|(?:algo de |una )?musica de\\s+|(?:una )?musica\\s+)?(.+)$").find(t)?.let { m2 ->
            val q = f.originalDeGrupo(m2, 1)
            if (Regex("^(una|un)\\s+(alarma|temporizador|recordatorio)").containsMatchIn(m2.groupValues[1])) return null
            val esMusica = Regex("(musica|cancion|tema|escuchar|toca|album|canciones)").containsMatchIn(t)
            return reproducirEn(MisApps.elegida(ctx, if (esMusica) "musica" else "videos"), q, cb)
        }
        return null
    }

    /** Reproduce en la app elegida por el usuario. */
    private fun reproducirEn(pkg: String?, q: String, cb: (Yarvis.Respuesta) -> Unit): Yarvis.Respuesta = when (pkg) {
        "com.google.android.apps.youtube.music" -> {
            if (ServicioAccesibilidad.activo) ServicioAccesibilidad.prepararReproduccion()
            if (!lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(q))).setPackage(pkg))) youtube(q, cb)
            else R("Buscando $q en YouTube Music, $tr.", cerrar = true)
        }
        "com.spotify.music" -> {
            lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q))).setPackage(pkg))
            R("Buscando $q en Spotify, $tr.", cerrar = true)
        }
        "deezer.android.app" -> {
            lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("deezer://www.deezer.com/search/" + Uri.encode(q))).setPackage(pkg))
            R("Buscando $q en Deezer, $tr.", cerrar = true)
        }
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> {
            if (!lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/search?q=" + Uri.encode(q))).setPackage(pkg)))
                abrirUrl("https://www.tiktok.com/search?q=" + Uri.encode(q))
            R("Buscando $q en TikTok, $tr.", cerrar = true)
        }
        "com.facebook.katana" -> {
            if (ServicioAccesibilidad.activo) ServicioAccesibilidad.prepararReproduccion()
            abrirUrl("https://www.facebook.com/search/videos/?q=" + Uri.encode(q))
            R("Le muestro los videos de $q en Facebook, $tr.", cerrar = true)
        }
        null -> youtube(q, cb, MisApps.youtube(ctx))
        else -> if (MisApps.esYoutube(ctx, pkg)) youtube(q, cb, pkg) else reproducirEnOtra(pkg, q)
    }

    /** Cualquier otra app del usuario: le pide que busque/reproduzca; si no sabe, la abre con la búsqueda copiada. */
    private fun reproducirEnOtra(pkg: String, q: String): Yarvis.Respuesta {
        val nombre = MisApps.nombreDe(ctx, pkg)
        val intentos = listOf(
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*").putExtra(SearchManager.QUERY, q),
            Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, q),
            Intent(Intent.ACTION_WEB_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, q)
        )
        if (intentos.any { lanzar(it) }) return R("Buscando $q en $nombre, $tr.", cerrar = true)
        try {
            ctx.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("ODÍN", q))
        } catch (_: Exception) {}
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        return if (i != null && lanzar(i)) R("Abrí $nombre, $tr. Le dejé copiado «$q»: mantenga presionado el buscador y pegue.", cerrar = true)
        else R("No pude abrir $nombre, $tr.")
    }

    /** Busca el primer video en YouTube y lo reproduce directamente. */
    private fun youtube(q: String, cb: (Yarvis.Respuesta) -> Unit, pkg: String? = MisApps.youtube(ctx)): Yarvis.Respuesta = fondo(cb) {
        val yt = pkg ?: "com.google.android.youtube"
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
            val app = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")).setPackage(yt)
                .putExtra("force_fullscreen", false).putExtra("finish_on_ended", true)
            principal.post {
                // 1) su app de YouTube  2) cualquier app que abra videos de YouTube  3) recién el navegador
                if (!lanzar(app) && !lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id")).setPackage(yt)) &&
                    !lanzarSinNavegador(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id"))))
                    abrirUrl("https://www.youtube.com/watch?v=$id")
            }
            R("Reproduciendo $q en YouTube, $tr.")
        } else {
            principal.post {
                val i = Intent(Intent.ACTION_SEARCH).setPackage(yt).putExtra("query", q)
                val i2 = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))).setPackage(yt)
                if (!lanzar(i) && !lanzar(i2)) abrirUrl("https://www.youtube.com/results?search_query=" + Uri.encode(q))
            }
            R("Buscando $q en YouTube, $tr.")
        }
    }

    private fun abrirApp(nombre: String): Yarvis.Respuesta {
        MisApps.buscarAlias(ctx, nombre)?.let { a ->
            val i = ctx.packageManager.getLaunchIntentForPackage(a.paquete)
            if (i != null && lanzar(i)) return R("Abriendo ${a.nombre}, $tr.", cerrar = true)
        }
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
                    if (!nm.isNotificationPolicyAccessGranted) { pedirAccesoNoMolestar(); R("Necesito su permiso para el modo silencio, $tr. Active ODÍN en la pantalla que le abrí.") }
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
            return R("Necesito su permiso para controlar el modo no molestar, $tr. Active ODÍN en la pantalla que le abrí.")
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
            return R("Para eso necesito el permiso de accesibilidad, $tr. Active ODÍN en la pantalla que le abrí.")
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

    /** Abre el intent solo si lo atiende una app que no sea un navegador. */
    private fun lanzarSinNavegador(i: Intent): Boolean {
        val pm = ctx.packageManager
        val navegadores = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), 0)
            .map { it.activityInfo.packageName }.toSet()
        val app = pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.firstOrNull { it !in navegadores } ?: return false
        return lanzar(Intent(i).setPackage(app))
    }

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
