package com.velkyra.yarvis

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chats automáticos: ODÍN contesta por el usuario los chats que él elija (WhatsApp, Messenger, Telegram…),
 * imitando su forma de escribir a partir de un fragmento real de su chat con esa persona.
 * Modo automático (responde solo) o modo "preguntarme antes". Los temas delicados siempre se consultan.
 */
object ChatAsistido {

    data class Chat(val nombre: String, val automatico: Boolean, val guia: String, val hasta: Long) {
        val activo get() = System.currentTimeMillis() < hasta
    }

    private val principal = Handler(Looper.getMainLooper())
    private val historial = HashMap<String, MutableList<Pair<Boolean, String>>>() // (esDeElla, texto)
    private val ultimoEnvio = HashMap<String, Long>()
    private val enviosHora = ArrayDeque<Long>()
    private const val CONSULTAR = "[CONSULTAR]"

    // --- Lista de chats --------------------------------------------------------

    private fun sp() = Yarvis.ctx.getSharedPreferences("yarvis", Context.MODE_PRIVATE)

    fun chats(): List<Chat> = try {
        val a = JSONArray(sp().getString("chats_auto", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Chat(o.getString("n"), o.optBoolean("a", true), o.optString("g"), o.optLong("h", 0))
        }
    } catch (_: Exception) { emptyList() }

    private fun guardarTodos(l: List<Chat>) {
        val a = JSONArray()
        for (c in l) a.put(JSONObject().put("n", c.nombre).put("a", c.automatico).put("g", c.guia).put("h", c.hasta))
        sp().edit().putString("chats_auto", a.toString()).apply()
    }

    private fun igual(a: String, b: String): Boolean {
        val x = Frase.normalizar(a)
        val y = Frase.normalizar(b)
        return x.isNotBlank() && y.isNotBlank() && (x == y || x.contains(y) || y.contains(x))
    }

    fun buscar(nombre: String): Chat? = chats().firstOrNull { igual(it.nombre, nombre) }

    fun guardar(c: Chat) = guardarTodos(chats().filterNot { igual(it.nombre, c.nombre) } + c)

    fun quitar(nombre: String) = guardarTodos(chats().filterNot { igual(it.nombre, nombre) })

    /** Activa (o crea) el chat automático con una persona. Conserva su guía de estilo si ya existía. */
    fun iniciar(nombre: String, minutos: Int, automatico: Boolean): Chat {
        val previo = buscar(nombre)
        val c = Chat(previo?.nombre ?: nombre, automatico, previo?.guia.orEmpty(), System.currentTimeMillis() + minutos * 60_000L)
        guardar(c)
        return c
    }

    /** Desactiva uno (o todos si [nombre] es null), conservando sus guías. */
    fun detener(nombre: String? = null) {
        guardarTodos(chats().map { if (nombre == null || igual(it.nombre, nombre)) it.copy(hasta = 0) else it })
    }

    fun activos() = chats().filter { it.activo }

    /** ¿Hay un chat automático activo para este remitente? */
    fun coincide(titulo: String): Boolean = activos().any { igual(it.nombre, titulo) }

    // --- Mensajes ----------------------------------------------------------------

    private fun anotar(quien: String, deElla: Boolean, texto: String) = synchronized(historial) {
        val l = historial.getOrPut(Frase.normalizar(quien)) { mutableListOf() }
        l.add(deElla to texto)
        while (l.size > 16) l.removeAt(0)
    }

    /** Llega un mensaje de alguien con chat automático (se llama desde el lector de notificaciones). */
    fun mensajeRecibido(titulo: String, app: String, texto: String) {
        val chat = activos().firstOrNull { igual(it.nombre, titulo) } ?: return
        anotar(titulo, true, texto)
        Thread {
            val propuesta = try {
                if (!CerebroIA.disponible()) null else redactar(chat, titulo, app)
            } catch (_: Exception) { null }
            val delicado = propuesta != null && propuesta.contains(CONSULTAR)
            if (chat.automatico && propuesta != null && !delicado && puedeEnviar(titulo)) {
                // Responde solo, con una pausa natural como si estuviera escribiendo
                val espera = 4000L + (texto.length * 60L).coerceAtMost(9000L) + (0..6000).random()
                principal.postDelayed({
                    val ok = ServicioNotificaciones.responder(Yarvis.ctx, titulo, propuesta)
                    if (ok != null) {
                        anotar(titulo, false, propuesta)
                        registrarEnvio(titulo)
                        avisar("Respondí a $ok", "«$texto» → «$propuesta»")
                    } else {
                        avisar("No pude responder a $titulo", "Abrió la conversación o la notificación ya no está.")
                    }
                }, espera)
                return@Thread
            }
            // Modo «preguntarme antes», tema delicado o sin IA: ODÍN consulta al usuario
            val tr = Prefs.trato
            val r = when {
                propuesta == null -> Yarvis.cerebro.preguntarAlUsuario("$titulo le escribe por $app: «$texto». ¿Qué le respondo, $tr?") { f -> decidir(titulo, null, f) }
                delicado -> Yarvis.cerebro.preguntarAlUsuario("$titulo le escribe por $app: «$texto». Es un tema que prefiero consultarle, $tr. ¿Qué le respondo?") { f -> decidir(titulo, null, f) }
                else -> Yarvis.cerebro.preguntarAlUsuario("$titulo le escribe por $app: «$texto». Le propongo responder: «$propuesta». ¿Se lo envío?") { f -> decidir(titulo, propuesta, f) }
            }
            if (delicado) avisar("$titulo le escribió", "«$texto» — lo dejé para que usted responda.")
            Yarvis.decirYEscuchar(r)
        }.start()
    }

    /** Límite para que nunca se descontrole: una respuesta cada 15 s por chat y máximo 40 por hora. */
    private fun puedeEnviar(titulo: String): Boolean = synchronized(enviosHora) {
        val ahora = System.currentTimeMillis()
        while (enviosHora.isNotEmpty() && ahora - enviosHora.first() > 3_600_000) enviosHora.removeFirst()
        val ultimo = ultimoEnvio[Frase.normalizar(titulo)] ?: 0L
        enviosHora.size < 40 && ahora - ultimo > 15_000
    }

    private fun registrarEnvio(titulo: String) = synchronized(enviosHora) {
        val ahora = System.currentTimeMillis()
        enviosHora.addLast(ahora)
        ultimoEnvio[Frase.normalizar(titulo)] = ahora
    }

    /** Redacta la respuesta imitando el estilo del usuario. Devuelve null si la IA no está disponible. */
    private fun redactar(chat: Chat, titulo: String, app: String): String? {
        val conv = synchronized(historial) { historial[Frase.normalizar(titulo)]?.toList().orEmpty() }
            .joinToString("\n") { (deElla, t) -> (if (deElla) "$titulo: " else "Yo: ") + t }
        val yo = Prefs.nombre.ifBlank { "el usuario" }
        val guia = chat.guia.trim().takeLast(7000)
        val sistema = buildString {
            appendLine("Eres $yo, que vive en Perú, chateando por $app con $titulo. Escribe EXACTAMENTE como lo haría $yo.")
            if (guia.isNotBlank()) {
                appendLine("Este es un fragmento real de su chat con $titulo. Copia fielmente la forma de escribir de $yo:")
                appendLine("largo de los mensajes, mayúsculas o minúsculas, signos, emojis, abreviaturas, muletillas, apodos y el trato que se dan.")
                appendLine("<<<")
                appendLine(guia)
                appendLine(">>>")
            } else {
                appendLine("Escribe breve (una o dos frases), natural y en español coloquial peruano.")
            }
            appendLine("Escribe SOLO el texto del mensaje: sin comillas, sin nombres delante, sin explicaciones. Nunca digas que eres una IA ni un asistente.")
            appendLine("Si el mensaje trata de dinero, préstamos o pagos; de citas, encuentros o compromisos con fecha u hora; de algo urgente o una emergencia;")
            appendLine("de una decisión importante; o te preguntan algo que no puedes saber (dónde está, qué está haciendo, datos personales), responde exactamente: $CONSULTAR")
        }
        val r = CerebroIA.preguntar("Conversación reciente:\n$conv\n\nEscribe mi respuesta al último mensaje de $titulo.", buscar = false, sistemaFijo = sistema, recordar = false)
        val limpio = r.lines().filter { it.isNotBlank() }.joinToString("\n").trim().trim('"', '«', '»')
            .replace(Regex("^(Yo|$yo)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        return limpio.take(600).ifBlank { null }
    }

    private val rSi = Regex("^(si|sip|claro|dale|ok|okay|okey|envia|enviala|enviasela|mandala|mandasela|perfecto|de acuerdo|hazlo|correcto|esta bien|listo)\\b")
    private val rNo = Regex("^(no|nada|cancela|no la envies|no le respondas|dejalo|ignora|ignoralo|despues|luego)\\b")
    private val rPropio = Regex("^(?:no,? |mejor |entonces )?(?:dile|respondele|responde|contestale|contesta|escribele|escribe|ponle|pon)(?: que)?\\s+(.+)$")

    private fun decidir(titulo: String, propuesta: String?, f: Frase, segundaVez: Boolean = false): Yarvis.Respuesta {
        val t = f.t
        val tr = Prefs.trato
        val propio = rPropio.find(t)?.let { f.originalDeGrupo(it, 1) }
        return when {
            propio != null -> enviar(titulo, propio)
            propuesta != null && rSi.containsMatchIn(t) -> enviar(titulo, propuesta)
            rNo.containsMatchIn(t) -> Yarvis.Respuesta("Entendido, $tr. No le respondo.", cerrar = true)
            propuesta == null && t.isNotBlank() -> enviar(titulo, f.original)
            !segundaVez -> Yarvis.cerebro.preguntarAlUsuario("¿Se lo envío, $tr? Diga sí, no, o dígame qué le respondo.") { f2 -> decidir(titulo, propuesta, f2, true) }
            else -> Yarvis.Respuesta("No le respondí, $tr.", cerrar = true)
        }
    }

    private fun enviar(titulo: String, texto: String): Yarvis.Respuesta {
        val limpio = texto.trim().replaceFirstChar { it.uppercase() }
        val ok = ServicioNotificaciones.responder(Yarvis.ctx, titulo, limpio)
        return if (ok != null) {
            anotar(titulo, false, limpio)
            Yarvis.Respuesta("Enviado a $ok.", cerrar = true)
        } else Yarvis.Respuesta("No pude enviarlo desde la notificación, ${Prefs.trato}. Puede que ya la haya abierto.", cerrar = true)
    }

    /** Notificación silenciosa con lo que ODÍN hizo, para que el usuario siempre lo sepa. */
    private fun avisar(titulo: String, texto: String) {
        val ctx = Yarvis.ctx
        if (!ctx.tiene(android.Manifest.permission.POST_NOTIFICATIONS)) return
        val n = Notification.Builder(ctx, Avisos.CANAL_SISTEMA)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(3000 + (titulo.hashCode() and 0xFFF), n)
    }
}
