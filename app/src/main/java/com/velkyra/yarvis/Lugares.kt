package com.velkyra.yarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Recordatorios por lugar: «cuando llegue a Juliaca, recuérdame comprar tinta», «cuando salga de casa…».
 * Usa las alertas de proximidad de Android (sin servicios extra). Lugares propios: «guarda este lugar como mi casa».
 */
object Lugares {

    data class Item(val id: Int, val texto: String, val lugar: String, val lat: Double, val lon: Double, val radio: Float, val entrar: Boolean)
    data class Lugar(val nombre: String, val lat: Double, val lon: Double)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("yarvis", Context.MODE_PRIVATE)

    // --- Lugares guardados («mi casa», «el trabajo») -------------------------------------------

    fun lugares(ctx: Context): List<Lugar> = try {
        val a = JSONArray(sp(ctx).getString("lugares", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Lugar(o.getString("n"), o.getDouble("a"), o.getDouble("o")) }
    } catch (_: Exception) { emptyList() }

    fun guardarLugar(ctx: Context, nombre: String, lat: Double, lon: Double) {
        val n = limpiar(nombre)
        val l = lugares(ctx).filterNot { Frase.normalizar(it.nombre) == Frase.normalizar(n) } + Lugar(n, lat, lon)
        val a = JSONArray()
        for (x in l) a.put(JSONObject().put("n", x.nombre).put("a", x.lat).put("o", x.lon))
        sp(ctx).edit().putString("lugares", a.toString()).apply()
    }

    private fun limpiar(s: String) = s.trim().replace(Regex("(?i)^(mi |la |el |al |a la |a |de |del )+"), "").trim()

    fun lugarGuardado(ctx: Context, dicho: String): Lugar? {
        val q = Frase.normalizar(limpiar(dicho))
        return lugares(ctx).firstOrNull { Frase.normalizar(it.nombre) == q }
    }

    // --- Recordatorios ------------------------------------------------------------------------

    fun lista(ctx: Context = Yarvis.ctx): List<Item> = try {
        val a = JSONArray(sp(ctx).getString("recordatorios_lugar", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Item(o.getInt("i"), o.getString("t"), o.getString("l"), o.getDouble("a"), o.getDouble("o"), o.getDouble("r").toFloat(), o.getBoolean("e"))
        }
    } catch (_: Exception) { emptyList() }

    private fun guardar(ctx: Context, l: List<Item>) {
        val a = JSONArray()
        for (x in l) a.put(JSONObject().put("i", x.id).put("t", x.texto).put("l", x.lugar).put("a", x.lat).put("o", x.lon).put("r", x.radio.toDouble()).put("e", x.entrar))
        sp(ctx).edit().putString("recordatorios_lugar", a.toString()).apply()
    }

    fun permisoListo(ctx: Context) = ctx.tiene(Manifest.permission.ACCESS_FINE_LOCATION) && ctx.tiene(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** Pide «Permitir todo el tiempo» para la ubicación (necesario para avisar con la app cerrada). */
    fun pedirPermiso(ctx: Context) {
        try {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    /** Busca las coordenadas de un lugar dicho. Primero los lugares guardados. Se llama desde un hilo secundario. */
    fun ubicar(ctx: Context, dicho: String): Pair<Lugar, Float>? {
        lugarGuardado(ctx, dicho)?.let { return it to 180f }
        val nombre = limpiar(dicho)
        val consulta = if (Regex("(?i)peru|perú").containsMatchIn(nombre)) nombre else "$nombre, Perú"
        try {
            @Suppress("DEPRECATION")
            val r = Geocoder(ctx, Locale.forLanguageTag("es-PE")).getFromLocationName(consulta, 1)
            r?.firstOrNull()?.let { a ->
                // Ciudad o distrito: radio amplio; dirección o lugar concreto: radio corto
                val radio = if (a.thoroughfare != null || a.featureName?.any { it.isDigit() } == true) 250f else 2500f
                return Lugar(nombre.replaceFirstChar { it.uppercase() }, a.latitude, a.longitude) to radio
            }
        } catch (_: Exception) {}
        try {
            val c = URL("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + Uri.encode(consulta)).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "ODIN-VELKYRA/1.0")
            c.connectTimeout = 8000; c.readTimeout = 10000
            val a = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            c.disconnect()
            if (a.length() > 0) {
                val o = a.getJSONObject(0)
                val tipo = o.optString("addresstype") + o.optString("type")
                val radio = if (Regex("city|town|village|district|administrative|county|state|municipality").containsMatchIn(tipo)) 2500f else 250f
                return Lugar(nombre.replaceFirstChar { it.uppercase() }, o.getString("lat").toDouble(), o.getString("lon").toDouble()) to radio
            }
        } catch (_: Exception) {}
        return null
    }

    fun crear(ctx: Context, texto: String, lugar: Lugar, radio: Float, entrar: Boolean): Item {
        val id = Prefs.contadorIds + 1
        Prefs.contadorIds = id
        val it = Item(id, texto, lugar.nombre, lugar.lat, lugar.lon, radio, entrar)
        guardar(ctx, lista(ctx) + it)
        registrar(ctx, it)
        return it
    }

    private fun intento(ctx: Context, i: Item) = PendingIntent.getBroadcast(
        ctx, 6000 + (i.id and 0xFFF), Intent(ctx, ReceptorLugar::class.java).setPackage(ctx.packageName).putExtra("id", i.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    @SuppressLint("MissingPermission")
    private fun registrar(ctx: Context, i: Item) {
        if (!ctx.tiene(Manifest.permission.ACCESS_FINE_LOCATION)) return
        try {
            ctx.getSystemService(LocationManager::class.java).addProximityAlert(i.lat, i.lon, i.radio, -1, intento(ctx, i))
        } catch (_: Exception) {}
    }

    fun reprogramarTodos(ctx: Context) { for (i in lista(ctx)) registrar(ctx, i) }

    fun quitar(ctx: Context, id: Int) {
        val i = lista(ctx).firstOrNull { it.id == id } ?: return
        try { ctx.getSystemService(LocationManager::class.java).removeProximityAlert(intento(ctx, i)) } catch (_: Exception) {}
        guardar(ctx, lista(ctx).filterNot { it.id == id })
    }

    fun cancelarTodos(ctx: Context): Int {
        val l = lista(ctx)
        for (i in l) quitar(ctx, i.id)
        return l.size
    }

    /** Llegó (o salió) del lugar. */
    fun alCruzar(ctx: Context, id: Int, entrando: Boolean) {
        val i = lista(ctx).firstOrNull { it.id == id } ?: return
        if (i.entrar != entrando) return
        quitar(ctx, id)
        val frase = "${Prefs.trato.replaceFirstChar { it.uppercase() }}, ${if (entrando) "llegó a" else "salió de"} ${i.lugar}. Le recuerdo: ${i.texto}."
        if (ctx.tiene(Manifest.permission.POST_NOTIFICATIONS)) {
            val n = Notification.Builder(ctx, Avisos.CANAL_RECORDATORIOS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("📍 ${if (entrando) "Llegó a" else "Salió de"} ${i.lugar}")
                .setContentText(i.texto).setAutoCancel(true).build()
            ctx.getSystemService(NotificationManager::class.java).notify(6000 + (id and 0xFFF), n)
        }
        if (Modos.puedeAnunciar) Yarvis.anunciar(frase)
    }

    /** Ubicación actual (para «guarda este lugar como mi casa»). Se llama desde un hilo secundario. */
    @SuppressLint("MissingPermission")
    fun aqui(ctx: Context): Location? {
        if (!ctx.tiene(Manifest.permission.ACCESS_FINE_LOCATION) && !ctx.tiene(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        val listo = java.util.concurrent.CountDownLatch(1)
        var r: Location? = null
        try {
            val prov = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            lm.getCurrentLocation(prov, null, ctx.mainExecutor) { l -> r = l; listo.countDown() }
            listo.await(15, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {}
        if (r == null) r = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }.maxByOrNull { it.time }
        return r
    }
}

class ReceptorLugar : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Lugares.alCruzar(ctx, intent.getIntExtra("id", 0), intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true))
    }
}
