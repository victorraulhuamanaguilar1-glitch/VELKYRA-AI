package com.velkyra.yarvis

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Publicaciones por voz en redes sociales. */
object Publicar {

    data class Red(val id: String, val nombre: String, val paquetes: List<String>)

    val REDES = listOf(
        Red("facebook", "Facebook", listOf("com.facebook.katana")),
        Red("estado", "tu estado de WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b")),
        Red("x", "X", listOf("com.twitter.android")),
        Red("telegram", "Telegram", listOf("org.telegram.messenger")),
        Red("instagram", "Instagram", listOf("com.instagram.android")),
        Red("tiktok", "TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"))
    )

    fun red(dicha: String): Red? {
        val d = Frase.normalizar(dicha)
        return when {
            d.contains("face") -> REDES[0]
            d.contains("estado") || d.contains("whats") || d.contains("wasap") -> REDES[1]
            d == "x" || d.contains("twitter") -> REDES[2]
            d.contains("telegram") -> REDES[3]
            d.contains("insta") -> REDES[4]
            d.contains("tik") -> REDES[5]
            else -> null
        }
    }

    private fun instalado(ctx: Context, r: Red): String? {
        val preferido = if (r.id == "estado") MisApps.elegida(ctx, "mensajes") else null
        return (listOfNotNull(preferido) + r.paquetes).firstOrNull { MisApps.instalada(ctx, it) }
    }

    private fun copiar(ctx: Context, texto: String) = try {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ODÍN", texto))
    } catch (_: Exception) {
    }

    /** Publica el texto. Devuelve lo que ODÍN le dice al usuario. */
    fun ejecutar(ctx: Context, r: Red, texto: String): String {
        val tr = Prefs.trato
        val pkg = instalado(ctx, r) ?: return "No encontré ${r.nombre} instalado en su celular, $tr."
        copiar(ctx, texto)
        val auto = ServicioAccesibilidad.activo
        fun lanzar(i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
        val compartir = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto).setPackage(pkg)
        return when (r.id) {
            "facebook" -> {
                if (auto) ServicioAccesibilidad.prepararPublicacion(pkg, texto, estadoWhatsapp = false, tocarPublicar = true)
                lanzar(compartir)
                if (auto) "Publicando en Facebook, $tr." else "Le abrí Facebook con el texto copiado: péguelo y toque Publicar. Si activa la accesibilidad de ODÍN, lo publicaré yo mismo."
            }
            "estado" -> {
                if (auto) ServicioAccesibilidad.prepararPublicacion(pkg, texto, estadoWhatsapp = true, tocarPublicar = true)
                lanzar(compartir)
                if (auto) "Publicando en su estado de WhatsApp, $tr." else "Le abrí WhatsApp: elija «Mi estado» y toque enviar, $tr."
            }
            "x" -> {
                if (auto) ServicioAccesibilidad.prepararPublicacion(pkg, texto, estadoWhatsapp = false, tocarPublicar = true)
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://twitter.com/intent/tweet?text=" + Uri.encode(texto))).setPackage(pkg)
                if (!lanzar(i)) lanzar(compartir)
                if (auto) "Publicando en X, $tr." else "Le abrí X con el texto listo: solo toque Postear, $tr."
            }
            "telegram" -> {
                lanzar(compartir)
                "Le abrí Telegram con el texto: elija el chat o canal donde publicarlo, $tr."
            }
            else -> {
                // Instagram y TikTok necesitan una foto o un video: el texto queda copiado para la descripción
                ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { lanzar(it) }
                "${r.nombre} necesita una foto o un video para publicar, $tr. Le abrí la app y dejé el texto copiado: péguelo como descripción."
            }
        }
    }

    /** Redacta una publicación con IA sobre un tema. */
    fun redactar(r: Red, tema: String): String? = try {
        val sistema = "Escribes publicaciones para redes sociales en nombre de ${Prefs.nombre.ifBlank { "el usuario" }}, que vive en Perú. " +
            "Escribe SOLO el texto de la publicación, en primera persona, natural y atractivo, en español. " +
            "Para ${r.nombre}: " + when (r.id) {
                "x" -> "máximo 260 caracteres, uno o dos hashtags."
                "instagram", "tiktok" -> "una descripción breve con dos o tres hashtags y algún emoji."
                "estado" -> "una o dos frases cortas."
                else -> "entre una y cuatro frases, puedes usar algún emoji."
            } + " Sin comillas ni explicaciones."
        CerebroIA.preguntar("Escribe una publicación sobre: $tema", buscar = false, sistemaFijo = sistema, recordar = false)
            .trim().trim('"', '«', '»').ifBlank { null }
    } catch (_: Exception) { null }
}
