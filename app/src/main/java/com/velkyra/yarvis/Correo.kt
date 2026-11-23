package com.velkyra.yarvis

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import java.util.Date
import java.util.Properties
import javax.mail.Address
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Transport
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.FlagTerm

/**
 * Gmail por voz: lee los correos nuevos, los lee completos, responde y envía correos nuevos.
 * Usa la «contraseña de aplicación» de Google (Ajustes → Correo), sin servidores de terceros.
 */
object Correo {

    data class Mail(
        val uid: Long, val de: String, val deCorreo: String, val asunto: String, val fecha: Long,
        val messageId: String?, var cuerpo: String? = null
    )

    @Volatile var ultimos: List<Mail> = emptyList()
        private set
    @Volatile var ultimoLeido: Mail? = null

    val configurado get() = Prefs.correoUsuario.isNotBlank() && Prefs.correoClave.isNotBlank()

    private fun sesion(): Session {
        val p = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", "imap.gmail.com")
            put("mail.imaps.port", "993")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "20000")
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "465")
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "20000")
        }
        return Session.getInstance(p)
    }

    private fun <T> conBandeja(escritura: Boolean, accion: (Folder) -> T): T {
        val store = sesion().getStore("imaps")
        store.connect("imap.gmail.com", Prefs.correoUsuario.trim(), Prefs.correoClave.replace(" ", ""))
        try {
            val f = store.getFolder("INBOX")
            f.open(if (escritura) Folder.READ_WRITE else Folder.READ_ONLY)
            try { return accion(f) } finally { try { f.close(false) } catch (_: Exception) {} }
        } finally {
            try { store.close() } catch (_: Exception) {}
        }
    }

    private fun nombre(a: Array<Address>?): Pair<String, String> {
        val x = a?.firstOrNull() as? InternetAddress ?: return "Desconocido" to ""
        return (x.personal?.takeIf { it.isNotBlank() } ?: x.address.substringBefore('@')) to x.address
    }

    /** Correos sin leer (los más recientes primero). Se llama desde un hilo secundario. */
    fun nuevos(max: Int = 8): List<Mail> = conBandeja(false) { f ->
        val uf = f as UIDFolder
        val msgs = f.search(FlagTerm(Flags(Flags.Flag.SEEN), false)).sortedByDescending { it.receivedDate ?: it.sentDate ?: Date(0) }.take(max)
        msgs.map { m ->
            val (n, c) = nombre(m.from)
            Mail(uf.getUID(m), n, c, m.subject.orEmpty().ifBlank { "sin asunto" }, (m.receivedDate ?: m.sentDate ?: Date()).time,
                (m as? MimeMessage)?.messageID)
        }.also { ultimos = it }
    }

    /** Los últimos correos de la bandeja (leídos o no). */
    fun recientes(max: Int = 8): List<Mail> = conBandeja(false) { f ->
        val uf = f as UIDFolder
        val total = f.messageCount
        if (total == 0) return@conBandeja emptyList<Mail>()
        f.getMessages(maxOf(1, total - max + 1), total).reversed().map { m ->
            val (n, c) = nombre(m.from)
            Mail(uf.getUID(m), n, c, m.subject.orEmpty().ifBlank { "sin asunto" }, (m.receivedDate ?: m.sentDate ?: Date()).time,
                (m as? MimeMessage)?.messageID)
        }.also { ultimos = it }
    }

    /** Lee el texto completo de un correo (y lo marca como leído). */
    fun cuerpo(m: Mail): String {
        m.cuerpo?.let { return it }
        val t = conBandeja(true) { f ->
            val msg = (f as UIDFolder).getMessageByUID(m.uid) ?: return@conBandeja ""
            val txt = texto(msg).trim()
            try { msg.setFlag(Flags.Flag.SEEN, true) } catch (_: Exception) {}
            txt
        }
        m.cuerpo = t
        ultimoLeido = m
        return t
    }

    private fun texto(p: Part): String = try {
        when {
            p.isMimeType("text/plain") -> p.content.toString()
            p.isMimeType("text/html") -> sinHtml(p.content.toString())
            p.isMimeType("multipart/alternative") -> {
                val mp = p.content as Multipart
                var html: String? = null
                var plano: String? = null
                for (i in 0 until mp.count) {
                    val b = mp.getBodyPart(i)
                    if (b.isMimeType("text/plain") && plano == null) plano = b.content.toString()
                    else if (b.isMimeType("text/html") && html == null) html = b.content.toString()
                    else if (b.isMimeType("multipart/*")) plano = plano ?: texto(b)
                }
                plano ?: html?.let { sinHtml(it) } ?: ""
            }
            p.isMimeType("multipart/*") -> {
                val mp = p.content as Multipart
                (0 until mp.count).map { mp.getBodyPart(it) }
                    .filter { Part.ATTACHMENT != it.disposition }
                    .joinToString("\n") { texto(it) }
            }
            else -> ""
        }
    } catch (_: Exception) { "" }

    private fun sinHtml(h: String): String = h
        .replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()

    /** Envía un correo (o responde a [respondeA]). Se llama desde un hilo secundario. */
    fun enviar(para: String, asunto: String, texto: String, respondeA: Mail? = null) {
        val s = sesion()
        val m = MimeMessage(s)
        val yo = Prefs.correoUsuario.trim()
        m.setFrom(InternetAddress(yo, Prefs.nombre.ifBlank { yo }, "UTF-8"))
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(para))
        m.setSubject(asunto, "UTF-8")
        m.setText(texto, "UTF-8")
        respondeA?.messageId?.let {
            m.setHeader("In-Reply-To", it)
            m.setHeader("References", it)
        }
        m.sentDate = Date()
        Transport.send(m, yo, Prefs.correoClave.replace(" ", ""))
    }

    /** Comprueba la cuenta. Devuelve null si todo está bien, o el problema. */
    fun probar(): String? = try {
        conBandeja(false) { it.messageCount }
        null
    } catch (e: javax.mail.AuthenticationFailedException) {
        "Google no aceptó el correo o la contraseña de aplicación. Revise que sea la contraseña de 16 letras, no su contraseña normal."
    } catch (e: Exception) {
        "No pude conectarme a Gmail: ${e.message?.take(120) ?: e.javaClass.simpleName}"
    }

    // --- Ayudas -----------------------------------------------------------------------------

    /** «juan punto perez arroba gmail punto com» → juan.perez@gmail.com */
    fun correoDicho(s: String): String? {
        val t = Frase.normalizar(s)
            .replace(Regex("\\s*arroba\\s*"), "@")
            .replace(Regex("\\s*guion bajo\\s*"), "_")
            .replace(Regex("\\s*(guion|guión)\\s*"), "-")
            .replace(Regex("\\s*punto\\s*"), ".")
            .replace(" ", "")
        return if (Regex("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$").matches(t)) t else null
    }

    /** Correo electrónico de un contacto de la agenda. */
    fun deContacto(ctx: Context, nombre: String): Pair<String, String>? {
        if (!ctx.tiene(Manifest.permission.READ_CONTACTS)) return null
        val obj = Frase.normalizar(nombre).replace(Regex("^(a mi |mi |al |a la |a |la |el )"), "").trim()
        if (obj.isEmpty()) return null
        val c = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS),
            null, null, null
        ) ?: return null
        var mejor: Pair<String, String>? = null
        c.use {
            while (it.moveToNext()) {
                val n = it.getString(0) ?: continue
                val d = it.getString(1) ?: continue
                val nn = Frase.normalizar(n)
                if (nn == obj) return n to d
                if (mejor == null && (nn.startsWith(obj) || nn.contains(obj))) mejor = n to d
            }
        }
        return mejor
    }

    /** Busca entre los últimos correos uno por remitente o por número («el segundo»). */
    fun elegir(dicho: String): Mail? {
        val l = ultimos
        if (l.isEmpty()) return null
        val t = Frase.normalizar(dicho)
        val orden = listOf("primer", "segundo", "tercer", "cuarto", "quinto", "sexto")
        orden.forEachIndexed { i, o -> if (Regex("\\b$o").containsMatchIn(t)) return l.getOrNull(i) }
        if (Regex("\\bultimo\\b").containsMatchIn(t)) return l.firstOrNull()
        val q = Regex("(?:de|del)\\s+(.+)$").find(t)?.groupValues?.get(1)?.trim()
        if (q != null) l.firstOrNull { Frase.normalizar(it.de).contains(q) || Frase.normalizar(it.deCorreo).contains(q.replace(" ", "")) || Frase.normalizar(it.asunto).contains(q) }?.let { return it }
        return null
    }
}
