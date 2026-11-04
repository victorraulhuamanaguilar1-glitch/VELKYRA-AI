package com.velkyra.yarvis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/** Clima real con Open-Meteo (gratis y sin clave). */
object Clima {

    data class Lugar(val lat: Double, val lon: Double, val nombre: String)
    data class Actual(val temp: Int, val descripcion: String, val lugar: String)

    private val es = Locale.forLanguageTag("es-PE")

    private fun leer(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 15000
        try {
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    fun ubicacion(ctx: Context): Location? {
        val ok = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
    }

    @Suppress("DEPRECATION")
    fun nombreDe(ctx: Context, lat: Double, lon: Double): String = try {
        val d = Geocoder(ctx, es).getFromLocation(lat, lon, 1)?.firstOrNull()
        d?.locality ?: d?.subAdminArea ?: d?.adminArea ?: "su ubicación"
    } catch (_: Exception) {
        "su ubicación"
    }

    fun buscarLugar(nombre: String): Lugar? {
        val j = leer("https://geocoding-api.open-meteo.com/v1/search?count=1&language=es&name=" + URLEncoder.encode(nombre, "UTF-8"))
        val r = j.optJSONArray("results")?.optJSONObject(0) ?: return null
        val region = r.optString("admin1").takeIf { it.isNotBlank() && it != r.optString("name") }
        return Lugar(r.getDouble("latitude"), r.getDouble("longitude"), r.getString("name") + (region?.let { ", $it" } ?: ""))
    }

    fun lugar(ctx: Context, ciudad: String?): Lugar? {
        if (!ciudad.isNullOrBlank()) return buscarLugar(ciudad)
        ubicacion(ctx)?.let { return Lugar(it.latitude, it.longitude, nombreDe(ctx, it.latitude, it.longitude)) }
        if (Prefs.ciudad.isNotBlank()) return buscarLugar(Prefs.ciudad)
        return null
    }

    private fun pronostico(l: Lugar): JSONObject = leer(
        "https://api.open-meteo.com/v1/forecast?latitude=${l.lat}&longitude=${l.lon}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=3"
    )

    fun actual(ctx: Context): Actual? = try {
        val l = lugar(ctx, null)
        if (l == null) null else {
            val c = pronostico(l).getJSONObject("current")
            Actual(c.getDouble("temperature_2m").roundToInt(), descripcion(c.getInt("weather_code")), l.nombre.substringBefore(","))
        }
    } catch (_: Exception) {
        null
    }

    /** Respuesta hablada. [dia] 0 = hoy, 1 = mañana, 2 = pasado mañana. */
    fun informe(ctx: Context, ciudad: String?, dia: Int): String {
        val tr = Prefs.trato
        val l = try { lugar(ctx, ciudad) } catch (_: Exception) { null }
            ?: return if (ciudad != null) "No encontré la ciudad $ciudad, $tr."
            else "No pude obtener su ubicación, $tr. Dígame, por ejemplo: clima en Juliaca."
        return try {
            val j = pronostico(l)
            val d = j.getJSONObject("daily")
            val max = d.getJSONArray("temperature_2m_max").getDouble(dia).roundToInt()
            val min = d.getJSONArray("temperature_2m_min").getDouble(dia).roundToInt()
            val lluvia = d.getJSONArray("precipitation_probability_max").optInt(dia, 0)
            val desc = descripcion(d.getJSONArray("weather_code").getInt(dia))
            val consejo = when {
                lluvia >= 60 -> " Le sugiero llevar paraguas."
                min <= 3 -> " Hará bastante frío; abríguese bien."
                max >= 28 -> " Hará calor; manténgase hidratado."
                else -> ""
            }
            if (dia == 0) {
                val c = j.getJSONObject("current")
                val t = c.getDouble("temperature_2m").roundToInt()
                val s = c.getDouble("apparent_temperature").roundToInt()
                val ahora = descripcion(c.getInt("weather_code"))
                "En ${l.nombre} hay $t grados y está $ahora, con sensación térmica de $s. " +
                    "Hoy la máxima será de $max y la mínima de $min, con $lluvia por ciento de probabilidad de lluvia.$consejo"
            } else {
                val cuando = if (dia == 1) "Mañana" else "Pasado mañana"
                "$cuando en ${l.nombre} se espera $desc, con máxima de $max y mínima de $min grados, " +
                    "y $lluvia por ciento de probabilidad de lluvia.$consejo"
            }
        } catch (_: Exception) {
            "No pude consultar el clima en este momento, $tr."
        }
    }

    fun descripcion(c: Int): String = when (c) {
        0 -> "despejado"
        1 -> "mayormente despejado"
        2 -> "parcialmente nublado"
        3 -> "nublado"
        45, 48 -> "con neblina"
        51, 53, 55 -> "con llovizna"
        56, 57 -> "con llovizna helada"
        61 -> "con lluvia ligera"
        63 -> "lluvioso"
        65 -> "con lluvia intensa"
        66, 67 -> "con lluvia helada"
        71 -> "con nevada ligera"
        73 -> "nevando"
        75 -> "con nevada intensa"
        77 -> "con granizo fino"
        80, 81 -> "con chubascos"
        82 -> "con chubascos fuertes"
        85, 86 -> "con chubascos de nieve"
        95 -> "con tormenta eléctrica"
        96, 99 -> "con tormenta y granizo"
        else -> "variable"
    }
}
