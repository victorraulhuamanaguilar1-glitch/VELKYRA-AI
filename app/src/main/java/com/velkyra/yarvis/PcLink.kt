package com.velkyra.yarvis

import android.content.Context
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Control de la laptop por voz con «VELKYRA PC Link» (programa para Windows), dentro del wifi de la casa.
 * Busca la laptop en la red, se conecta con el código de 6 números y le manda órdenes.
 */
object PcLink {

    private const val PUERTO_UDP = 47800
    private const val PUERTO_HTTP = 47801
    const val DESCARGA = "https://github.com/victorraulhuamanaguilar1-glitch/VELKYRA-AI/releases/latest/download/VELKYRA_PC_Link.exe"

    private fun sp() = Yarvis.ctx.getSharedPreferences("yarvis", Context.MODE_PRIVATE)

    val conectada get() = sp().getString("pc_token", "").orEmpty().isNotBlank()
    val nombre get() = sp().getString("pc_nombre", "su laptop").orEmpty()
    private val ip get() = sp().getString("pc_ip", "").orEmpty()

    /** Busca la laptop en el wifi. Devuelve (ip, nombre) o null. Se llama desde un hilo secundario. */
    fun buscar(): Pair<String, String>? {
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 1500
                val msg = "VELKYRA_PC?".toByteArray()
                val buf = ByteArray(256)
                repeat(3) {
                    s.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), PUERTO_UDP))
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val t = String(p.data, 0, p.length)
                        if (t.startsWith("VELKYRA_PC|")) return p.address.hostAddress.orEmpty() to t.split("|").getOrElse(1) { "laptop" }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** Se conecta con el código de 6 números que muestra la laptop. Devuelve null si salió bien, o el problema. */
    fun emparejar(ipPc: String, nombrePc: String, pin: String): String? = try {
        val r = post(ipPc, "/emparejar", JSONObject().put("pin", pin).put("telefono", "ODÍN de ${Prefs.nombre.ifBlank { "su celular" }}"))
        if (r.optBoolean("ok")) {
            sp().edit().putString("pc_ip", ipPc).putString("pc_nombre", r.optString("nombre", nombrePc)).putString("pc_token", r.getString("token")).apply()
            null
        } else r.optString("msg", "Código incorrecto")
    } catch (e: Exception) { "No pude conectarme con la laptop." }

    fun desconectar() = sp().edit().remove("pc_token").remove("pc_ip").remove("pc_nombre").apply()

    /** Manda una orden. Si cambió la IP de la laptop, la vuelve a buscar. Se llama desde un hilo secundario. */
    fun orden(accion: String, valor: String = ""): String {
        val tr = Prefs.trato
        if (!conectada) return "Aún no he conectado su laptop, $tr. Instale VELKYRA PC Link en la laptop y diga: conecta con mi laptop."
        val cuerpo = JSONObject().put("token", sp().getString("pc_token", "")).put("accion", accion).put("valor", valor)
        fun intentar(direccion: String): String? = try {
            val r = post(direccion, "/orden", cuerpo)
            r.optString("msg").ifBlank { "Listo." }
        } catch (e: java.io.IOException) {
            if (e.message?.contains("403") == true) "La laptop ya no reconoce este celular, $tr. Vuelva a conectarla." else null
        } catch (_: Exception) { null }
        intentar(ip)?.let { return it }
        val nueva = buscar() ?: return "No encuentro su laptop en el wifi, $tr. Revise que esté encendida, en la misma red y con VELKYRA PC Link abierto."
        sp().edit().putString("pc_ip", nueva.first).apply()
        return intentar(nueva.first) ?: "Su laptop no respondió, $tr."
    }

    private fun post(direccion: String, ruta: String, cuerpo: JSONObject): JSONObject {
        val c = URL("http://$direccion:$PUERTO_HTTP$ruta").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 3000
            c.readTimeout = 15000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
            val codigo = c.responseCode
            val txt = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (codigo == 403 && ruta == "/orden") throw java.io.IOException("403")
            return JSONObject(txt.ifBlank { "{}" })
        } finally { c.disconnect() }
    }
}
