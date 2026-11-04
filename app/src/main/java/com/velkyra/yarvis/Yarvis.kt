package com.velkyra.yarvis

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * El centro de mando de YARVIS: coordina la palabra de activación, el oído, el cerebro y la voz,
 * y avisa a las pantallas (app y ventana flotante) de lo que está pasando.
 */
object Yarvis {

    enum class Estado { REPOSO, ESCUCHANDO, PENSANDO, HABLANDO }

    data class Mensaje(val deUsuario: Boolean, val texto: String)

    /** Respuesta del cerebro. [seguir] = vuelve a escuchar después de hablar (conversación). */
    data class Respuesta(
        val texto: String,
        val seguir: Boolean = false,
        val despues: (() -> Unit)? = null,
        /** Termina la conversación aunque esté activo el modo continuo (despedidas, apps abiertas…). */
        val cerrar: Boolean = false,
        /** Texto extra que solo se muestra en pantalla (por ejemplo, el detalle técnico de un error). */
        val detalle: String? = null
    )

    interface Observador {
        fun alEstado(estado: Estado) {}
        fun alMensaje(m: Mensaje) {}
        fun alParcial(texto: String) {}
        fun alNivel(nivel: Float) {}
    }

    lateinit var ctx: Context
        private set
    lateinit var cerebro: Cerebro
        private set

    private val principal = Handler(Looper.getMainLooper())
    private val observadores = CopyOnWriteArrayList<Observador>()
    val historial = mutableListOf<Mensaje>()

    var estado = Estado.REPOSO
        private set

    /** La pantalla principal está a la vista (si no, se usa la ventana flotante). */
    var pantallaVisible = false

    private var sesion = 0
    private var ultimaVoz = 0L
    private var enConversacion = false
    private var turnoPorVoz = false
    private var tono: ToneGenerator? = null

    private val frasesActivacion = listOf("¿Sí, %s?", "Dígame, %s.", "A sus órdenes, %s.", "Le escucho, %s.", "¿En qué le ayudo, %s?")

    fun init(c: Context) {
        ctx = c.applicationContext
        Voz.init(ctx)
        cerebro = Cerebro(ctx)
        cargarHistorial()
    }

    /** Última respuesta dicha (para «repite»). */
    val ultimaRespuesta: String get() = historial.lastOrNull { !it.deUsuario }?.texto?.substringBefore("\n[") ?: ""

    private fun cargarHistorial() {
        try {
            val a = org.json.JSONArray(ctx.getSharedPreferences("yarvis", Context.MODE_PRIVATE).getString("historial", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                historial.add(Mensaje(o.getBoolean("u"), o.getString("t")))
            }
        } catch (_: Exception) {
        }
    }

    private fun guardarHistorial() {
        try {
            val a = org.json.JSONArray()
            for (m in historial.takeLast(40)) a.put(org.json.JSONObject().put("u", m.deUsuario).put("t", m.texto))
            ctx.getSharedPreferences("yarvis", Context.MODE_PRIVATE).edit().putString("historial", a.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun observar(o: Observador) { if (!observadores.contains(o)) observadores.add(o) }
    fun dejarDeObservar(o: Observador) { observadores.remove(o) }

    private fun cambiar(e: Estado) {
        estado = e
        for (o in observadores) o.alEstado(e)
    }

    private fun agregar(m: Mensaje) {
        historial.add(m)
        while (historial.size > 60) historial.removeAt(0)
        guardarHistorial()
        for (o in observadores) o.alMensaje(m)
    }

    fun borrarHistorial() {
        historial.clear()
        guardarHistorial()
        cerebro.olvidarConversacion()
    }

    /** Despierta a YARVIS (por la palabra "Yarvis", al tocar el núcleo o desde la notificación). */
    fun activar(porPalabra: Boolean) = principal.post {
        val miSesion = ++sesion
        ultimaVoz = System.currentTimeMillis()
        enConversacion = false
        Oido.cancelar()
        Voz.detener()
        ServicioEscucha.pausar()
        if (!pantallaVisible) VentanaFlotante.mostrar(ctx)
        if (porPalabra && Prefs.responderAlActivar) {
            cambiar(Estado.HABLANDO)
            val frase = String.format(frasesActivacion.random(), Prefs.trato)
            for (o in observadores) o.alParcial(frase)
            Voz.hablar(frase) { if (miSesion == sesion) escucharOrden(miSesion) }
        } else {
            bip()
            principal.postDelayed({ if (miSesion == sesion) escucharOrden(miSesion) }, 180)
        }
    }

    private fun escucharOrden(miSesion: Int) {
        cambiar(Estado.ESCUCHANDO)
        for (o in observadores) o.alParcial(if (enConversacion) "Le escucho… (diga «eso es todo» para terminar)" else "Escuchando…")
        Oido.escuchar(ctx, object : Oido.Escucha {
            override fun parcial(texto: String) {
                if (miSesion == sesion) for (o in observadores) o.alParcial(texto)
            }

            override fun nivel(rms: Float) {
                if (miSesion == sesion) for (o in observadores) o.alNivel(rms)
            }

            override fun resultado(texto: String?) {
                if (miSesion != sesion) return
                if (texto.isNullOrBlank()) {
                    val esperado = System.currentTimeMillis() - ultimaVoz
                    if (enConversacion && esperado < Prefs.esperaConversacion * 1000L) {
                        // En conversación: sigue esperando hasta completar el tiempo configurado
                        principal.postDelayed({ if (miSesion == sesion) escucharOrden(miSesion) }, 250)
                    } else {
                        for (o in observadores) o.alParcial(if (enConversacion) "Conversación terminada. Diga «${Prefs.palabraActivacion}» cuando me necesite." else "No le escuché. Toque el núcleo o diga «${Prefs.palabraActivacion}».")
                        enConversacion = false
                        terminar()
                    }
                } else {
                    ultimaVoz = System.currentTimeMillis()
                    procesar(texto, miSesion)
                }
            }

            override fun error(mensaje: String) {
                if (miSesion != sesion) return
                responder(Respuesta(mensaje), miSesion)
            }
        })
    }

    /** Procesa una orden escrita o hablada. */
    fun procesar(texto: String, sesionDada: Int = -1) = principal.post {
        val miSesion = if (sesionDada >= 0) sesionDada else ++sesion
        turnoPorVoz = sesionDada >= 0
        if (sesionDada < 0) {
            enConversacion = false
            Oido.cancelar()
            Voz.detener()
            ServicioEscucha.pausar()
        }
        agregar(Mensaje(true, texto))
        cambiar(Estado.PENSANDO)
        for (o in observadores) o.alParcial("Procesando…")
        cerebro.procesar(texto) { r -> principal.post { if (miSesion == sesion) responder(r, miSesion) } }
    }

    private fun responder(r: Respuesta, miSesion: Int) {
        agregar(Mensaje(false, r.texto + (r.detalle?.let { "\n[$it]" } ?: "")))
        cambiar(Estado.HABLANDO)
        for (o in observadores) o.alParcial(r.texto)
        val abrioApp = cerebro.tomarAbrioApp()
        val continuar = r.seguir || (Prefs.conversacionContinua && turnoPorVoz && !r.cerrar && !abrioApp && r.despues == null)
        Voz.hablar(r.texto) {
            if (miSesion != sesion) return@hablar
            r.despues?.invoke()
            if (continuar) {
                ultimaVoz = System.currentTimeMillis()
                if (!r.seguir) enConversacion = true
                escucharOrden(miSesion)
            } else {
                enConversacion = false
                terminar()
            }
        }
    }

    /** Anuncia algo por iniciativa propia (mensajes que llegan, recordatorios). */
    fun anunciar(texto: String, guardar: Boolean = true) = principal.post {
        if (estado != Estado.REPOSO) return@post
        val miSesion = ++sesion
        ServicioEscucha.pausar()
        if (guardar) agregar(Mensaje(false, texto))
        cambiar(Estado.HABLANDO)
        for (o in observadores) o.alParcial(texto)
        Voz.hablar(texto) { if (miSesion == sesion) terminar() }
    }

    fun cancelar() = principal.post {
        sesion++
        Oido.cancelar()
        Voz.detener()
        terminar()
    }

    private fun terminar() {
        cambiar(Estado.REPOSO)
        for (o in observadores) o.alNivel(0f)
        val miSesion = sesion
        principal.postDelayed({
            if (miSesion == sesion && estado == Estado.REPOSO) ServicioEscucha.reanudar()
        }, 400)
        principal.postDelayed({
            if (miSesion == sesion && estado == Estado.REPOSO) VentanaFlotante.ocultar()
        }, 3000)
    }

    private fun bip() {
        try {
            if (tono == null) tono = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
            tono?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        } catch (_: Exception) {
        }
    }
}
