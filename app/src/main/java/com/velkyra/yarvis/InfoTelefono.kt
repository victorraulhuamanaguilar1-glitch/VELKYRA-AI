package com.velkyra.yarvis

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import java.net.URLEncoder
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Información del celular del usuario. */
object InfoTelefono {

    private fun gb(bytes: Long) = String.format(Locale.forLanguageTag("es-PE"), "%.1f", bytes / 1_073_741_824.0).replace(".0", "")

    fun marca(): String = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    fun modelo(): String = if (Build.MODEL.lowercase().startsWith(Build.MANUFACTURER.lowercase())) Build.MODEL else "${marca()} ${Build.MODEL}"

    fun almacenamiento(): Pair<Long, Long> {
        val st = StatFs(Environment.getDataDirectory().path)
        return st.availableBytes to st.totalBytes
    }

    fun memoria(ctx: Context): Pair<Long, Long> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem to mi.totalMem
    }

    fun red(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "sin conexión a internet"
        return when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "conectado por wifi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "conectado por datos móviles"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "conectado por cable"
            else -> "conectado a internet"
        } + (c.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.let { ", con unos ${it / 1000} megabits de bajada" } ?: "")
    }

    fun bateria(ctx: Context): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} por ciento" + if (bm.isCharging) ", cargando" else ""
    }

    fun encendido(): String {
        val h = SystemClock.elapsedRealtime() / 3_600_000
        return if (h >= 24) "${h / 24} días y ${h % 24} horas" else "$h horas"
    }

    /** Resumen compacto para la IA. */
    fun resumen(ctx: Context): String {
        val (libre, total) = almacenamiento()
        val (ramLibre, ramTotal) = memoria(ctx)
        return "${modelo()}, Android ${Build.VERSION.RELEASE}, almacenamiento ${gb(libre)} GB libres de ${gb(total)} GB, " +
            "memoria RAM ${gb(ramLibre)} GB libres de ${gb(ramTotal)} GB, batería ${bateria(ctx)}, ${red(ctx)}, encendido hace ${encendido()}."
    }

    /** Respuesta hablada a preguntas sobre el teléfono. Devuelve null si no es una de ellas. */
    fun responder(ctx: Context, f: Frase): String? {
        val tr = Prefs.trato
        val t = f.t
        val sobreCelular = Regex("(celular|telefono|movil|equipo|dispositivo|smartphone)").containsMatchIn(t)
        return when {
            Regex("(espacio|almacenamiento|memoria interna|cuanto me queda de memoria|gigas libres)").containsMatchIn(t) -> {
                val (l, tt) = almacenamiento()
                val pct = (l * 100 / tt.coerceAtLeast(1)).toInt()
                "Tiene ${gb(l)} gigabytes libres de ${gb(tt)}, es decir, el $pct por ciento, $tr." + if (pct < 10) " Le sugiero liberar espacio." else ""
            }
            Regex("(memoria ram|\\bram\\b)").containsMatchIn(t) -> {
                val (l, tt) = memoria(ctx)
                "Su celular tiene ${gb(tt)} gigabytes de memoria RAM, y en este momento hay ${gb(l)} disponibles, $tr."
            }
            Regex("(version de android|que android|android tengo)").containsMatchIn(t) ->
                "Su celular tiene Android ${Build.VERSION.RELEASE}, $tr."
            sobreCelular && Regex("(modelo|marca|que celular|que telefono)").containsMatchIn(t) ->
                "Su celular es un ${modelo()}, con Android ${Build.VERSION.RELEASE}, $tr."
            Regex("(estoy conectado|tengo internet|mi conexion|tipo de conexion|wifi o datos)").containsMatchIn(t) ->
                "Está ${red(ctx)}, $tr."
            Regex("(cuanto tiempo|hace cuanto).*(encendido|prendido)").containsMatchIn(t) ->
                "Su celular está encendido desde hace ${encendido()}, $tr."
            sobreCelular && Regex("(informacion|estado|datos|como esta|diagnostico|resumen)").containsMatchIn(t) -> {
                val (l, tt) = almacenamiento()
                val (rl, rt) = memoria(ctx)
                val brillo = try { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255 } catch (_: Exception) { -1 }
                "Su celular es un ${modelo()} con Android ${Build.VERSION.RELEASE}. Tiene ${gb(l)} gigabytes libres de ${gb(tt)} de almacenamiento, " +
                    "${gb(rl)} de ${gb(rt)} gigabytes de memoria RAM disponibles, batería al ${bateria(ctx)}, y está ${red(ctx)}." +
                    (if (brillo >= 0) " El brillo está al $brillo por ciento." else "") + " Lleva encendido ${encendido()}, $tr."
            }
            else -> null
        }
    }
}

/** Noticias reales del día (Google Noticias, gratis y sin límite). */
object Noticias {

    data class Titular(val titulo: String, val fuente: String)

    fun titulares(tema: String?, cantidad: Int = 5): List<Titular> {
        val url = if (tema.isNullOrBlank()) "https://news.google.com/rss?hl=es-419&gl=PE&ceid=PE:es-419"
        else "https://news.google.com/rss/search?hl=es-419&gl=PE&ceid=PE:es-419&q=" + URLEncoder.encode("$tema when:2d", "UTF-8")
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) YARVIS")
        c.connectTimeout = 12000
        c.readTimeout = 15000
        try {
            val doc = c.inputStream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
            val items = doc.getElementsByTagName("item")
            val r = mutableListOf<Titular>()
            for (i in 0 until items.length) {
                if (r.size >= cantidad) break
                val el = items.item(i) as org.w3c.dom.Element
                val completo = el.getElementsByTagName("title").item(0)?.textContent?.trim() ?: continue
                val fuente = el.getElementsByTagName("source").item(0)?.textContent?.trim()
                    ?: completo.substringAfterLast(" - ", "")
                val titulo = if (fuente.isNotBlank()) completo.removeSuffix(" - $fuente").trim() else completo
                r.add(Titular(titulo, fuente))
            }
            return r
        } finally {
            c.disconnect()
        }
    }

    fun informe(tema: String?): String {
        val tr = Prefs.trato
        val l = try { titulares(tema) } catch (_: Exception) { emptyList() }
        if (l.isEmpty()) return "No pude obtener las noticias en este momento, $tr."
        val intro = if (tema.isNullOrBlank()) "Estas son las noticias principales de hoy, $tr." else "Esto es lo más reciente sobre $tema, $tr."
        val cuerpo = l.mapIndexed { i, n ->
            val orden = listOf("Primero", "Segundo", "Tercero", "Cuarto", "Quinto", "Sexto").getOrElse(i) { "Además" }
            "$orden: ${n.titulo}" + if (n.fuente.isNotBlank()) ", según ${n.fuente}." else "."
        }.joinToString(" ")
        return "$intro $cuerpo ¿Desea que le amplíe alguna?"
    }
}
