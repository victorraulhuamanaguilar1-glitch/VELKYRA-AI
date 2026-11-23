package com.velkyra.yarvis

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Mis apps»: qué app usa ODÍN para cada cosa (música, videos, mensajes, mapas)
 * y nombres propios para abrir apps («abre mi banco»).
 */
object MisApps {

    data class Opcion(val paquete: String, val nombre: String)
    data class Categoria(val id: String, val titulo: String, val opciones: List<Opcion>)
    data class Alias(val nombre: String, val paquete: String, val etiqueta: String)

    val CATEGORIAS = listOf(
        Categoria("musica", "Música", listOf(
            Opcion("com.google.android.youtube", "YouTube"),
            Opcion("com.google.android.apps.youtube.music", "YouTube Music"),
            Opcion("com.spotify.music", "Spotify"),
            Opcion("deezer.android.app", "Deezer")
        )),
        Categoria("videos", "Videos", listOf(
            Opcion("com.google.android.youtube", "YouTube"),
            Opcion("com.zhiliaoapp.musically", "TikTok"),
            Opcion("com.ss.android.ugc.trill", "TikTok"),
            Opcion("com.facebook.katana", "Facebook")
        )),
        Categoria("mensajes", "Mensajes", listOf(
            Opcion("com.whatsapp", "WhatsApp"),
            Opcion("com.whatsapp.w4b", "WhatsApp Business")
        )),
        Categoria("mapas", "Mapas", listOf(
            Opcion("com.google.android.apps.maps", "Google Maps"),
            Opcion("com.waze", "Waze")
        ))
    )

    fun instalada(ctx: Context, pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg) != null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("yarvis", Context.MODE_PRIVATE)

    /** App elegida para una categoría (o la primera instalada). */
    fun elegida(ctx: Context, categoria: String): String? {
        val guardada = prefs(ctx).getString("app_$categoria", null)
        if (guardada != null && instalada(ctx, guardada)) return guardada
        return CATEGORIAS.firstOrNull { it.id == categoria }?.opciones?.firstOrNull { instalada(ctx, it.paquete) }?.paquete
    }

    fun elegir(ctx: Context, categoria: String, pkg: String) = prefs(ctx).edit().putString("app_$categoria", pkg).apply()

    /** Opciones a mostrar en Ajustes: las conocidas que estén instaladas + la que el usuario eligió de su lista. */
    fun opcionesDe(ctx: Context, categoria: String): List<Opcion> {
        val cat = CATEGORIAS.firstOrNull { it.id == categoria } ?: return emptyList()
        val l = cat.opciones.filter { instalada(ctx, it.paquete) }.distinctBy { it.nombre }.toMutableList()
        val g = prefs(ctx).getString("app_$categoria", null)
        if (g != null && instalada(ctx, g) && l.none { it.paquete == g }) l.add(Opcion(g, nombreDe(ctx, g)))
        return l
    }

    private val YOUTUBES = listOf(
        "com.google.android.youtube", "app.revanced.android.youtube", "app.rvx.android.youtube",
        "com.vanced.android.youtube", "com.google.android.apps.youtube.mango", "org.schabi.newpipe"
    )

    /** ¿Es una app de YouTube (oficial, ReVanced, Vanced, Go, NewPipe…)? */
    fun esYoutube(ctx: Context, pkg: String?): Boolean {
        if (pkg == null) return false
        if (pkg in YOUTUBES) return true
        val n = Frase.normalizar(nombreDe(ctx, pkg))
        return n.contains("youtube") && !n.contains("music")
    }

    /** La app de YouTube del usuario: la que eligió en Mis apps, la oficial o cualquier otra que diga «YouTube». */
    fun youtube(ctx: Context): String? {
        listOf("videos", "musica").mapNotNull { prefs(ctx).getString("app_$it", null) }
            .firstOrNull { instalada(ctx, it) && esYoutube(ctx, it) }?.let { return it }
        YOUTUBES.firstOrNull { instalada(ctx, it) }?.let { return it }
        return instaladas(ctx).firstOrNull { esYoutube(ctx, it.paquete) }?.paquete
    }

    /** Busca una app por cómo la nombró el usuario: primero sus nombres propios, luego el nombre de la app instalada. */
    fun buscarApp(ctx: Context, dicho: String): Opcion? {
        buscarAlias(ctx, dicho)?.let { return Opcion(it.paquete, it.nombre) }
        val d = Frase.normalizar(dicho).replace(Regex("^(la app (de )?|la aplicacion (de )?|el |la |mi )"), "").trim()
        if (d.length < 2) return null
        val apps = instaladas(ctx)
        return apps.firstOrNull { Frase.normalizar(it.nombre) == d }
            ?: apps.firstOrNull { Frase.normalizar(it.nombre).replace(" ", "") == d.replace(" ", "") }
            ?: apps.firstOrNull { val n = Frase.normalizar(it.nombre); d.length >= 4 && (n.startsWith("$d ") || n.contains(d)) }
    }

    fun nombreDe(ctx: Context, pkg: String?): String {
        if (pkg == null) return "Ninguna instalada"
        CATEGORIAS.flatMap { it.opciones }.firstOrNull { it.paquete == pkg }?.let { return it.nombre }
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }

    // --- Nombres propios -----------------------------------------------------

    fun alias(ctx: Context): List<Alias> = try {
        val a = JSONArray(prefs(ctx).getString("alias_apps", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Alias(o.getString("n"), o.getString("p"), o.optString("e")) }
    } catch (_: Exception) { emptyList() }

    fun agregarAlias(ctx: Context, nombre: String, pkg: String, etiqueta: String) {
        val l = alias(ctx).filter { Frase.normalizar(it.nombre) != Frase.normalizar(nombre) } + Alias(nombre.trim(), pkg, etiqueta)
        guardar(ctx, l)
    }

    fun quitarAlias(ctx: Context, nombre: String) = guardar(ctx, alias(ctx).filter { it.nombre != nombre })

    private fun guardar(ctx: Context, l: List<Alias>) {
        val a = JSONArray()
        for (x in l) a.put(JSONObject().put("n", x.nombre).put("p", x.paquete).put("e", x.etiqueta))
        prefs(ctx).edit().putString("alias_apps", a.toString()).apply()
    }

    /** Busca un nombre propio dicho por el usuario («mi banco»). */
    fun buscarAlias(ctx: Context, dicho: String): Alias? {
        val d = Frase.normalizar(dicho).replace(Regex("^(la app (de )?|la aplicacion (de )?|el |la |mi |mis )"), "").trim()
        return alias(ctx).firstOrNull {
            val n = Frase.normalizar(it.nombre).replace(Regex("^(el |la |mi |mis )"), "").trim()
            n == d || (n.length >= 3 && (d.contains(n) || n.contains(d)))
        }
    }

    /** Todas las apps instaladas que se pueden abrir, ordenadas por nombre. */
    fun instaladas(ctx: Context): List<Opcion> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { Opcion(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.paquete != ctx.packageName }
            .distinctBy { it.paquete }
            .sortedBy { it.nombre.lowercase() }
    }
}
