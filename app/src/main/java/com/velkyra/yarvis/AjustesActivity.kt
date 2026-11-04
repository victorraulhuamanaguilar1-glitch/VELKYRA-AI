package com.velkyra.yarvis

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Ajustes de YARVIS. */
class AjustesActivity : Base() {

    private lateinit var cuerpo: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cuerpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        montar(pantallaConTitulo("Ajustes", cuerpo))
    }

    override fun onResume() {
        super.onResume()
        dibujar()
    }

    private fun seccion(t: String): LinearLayout {
        val c = tarjeta(t)
        cuerpo.agregar(c, arriba = dp(14))
        return c
    }

    private fun estadoPermiso(ok: Boolean, txtOk: String, txtNo: String, alTocar: () -> Unit): TextView =
        texto(if (ok) "✓  $txtOk" else "○  $txtNo  ›", 16f, if (ok) Ui.VERDE else Ui.AMBAR, Ui.fuerte).apply {
            setPadding(0, dp(8), 0, dp(8))
            if (!ok) setOnClickListener { alTocar() }
        }

    private fun dibujar() {
        cuerpo.removeAllViews()

        // PERFIL
        val perfil = seccion("Perfil")
        perfil.addView(texto("Su nombre", 14f, Ui.TEXTO_SUAVE))
        val cNombre = campo("Su nombre", Prefs.nombre)
        perfil.agregar(cNombre, arriba = dp(4))
        perfil.agregar(texto("Cómo le llamo (señor, jefe, su nombre…)", 14f, Ui.TEXTO_SUAVE), arriba = dp(12))
        val cTrato = campo("señor", Prefs.tratoGuardado)
        perfil.agregar(cTrato, arriba = dp(4))
        perfil.agregar(texto("Ciudad para el clima (si no hay GPS)", 14f, Ui.TEXTO_SUAVE), arriba = dp(12))
        val cCiudad = campo("Ejemplo: Juliaca", Prefs.ciudad)
        perfil.agregar(cCiudad, arriba = dp(4))
        perfil.agregar(boton("GUARDAR PERFIL", false) {
            Prefs.nombre = cNombre.text.toString()
            Prefs.tratoGuardado = cTrato.text.toString()
            Prefs.ciudad = cCiudad.text.toString()
            Voz.hablar("Perfil guardado, ${Prefs.trato}.")
        }, arriba = dp(14))

        // VOZ
        val voz = seccion("Voz")
        val actual = Voz.opciones().firstOrNull { it.voz.name == Voz.vozActual() }?.etiqueta ?: "Predeterminada"
        voz.addView(texto("Voz actual: $actual", 16f, Ui.TEXTO))
        voz.agregar(boton("CAMBIAR VOZ", false) { elegirVoz() }, arriba = dp(10))
        voz.agregar(texto("Tono (grave ← → agudo)", 14f, Ui.TEXTO_SUAVE), arriba = dp(14))
        voz.addView(deslizador(80, ((Prefs.tono - 0.5f) * 100).toInt()) { Prefs.tono = 0.5f + it / 100f; Voz.aplicarVoz() })
        voz.agregar(texto("Velocidad", 14f, Ui.TEXTO_SUAVE), arriba = dp(10))
        voz.addView(deslizador(60, ((Prefs.velocidad - 0.7f) * 100).toInt()) { Prefs.velocidad = 0.7f + it / 100f; Voz.aplicarVoz() })
        voz.agregar(boton("PROBAR", false) { Voz.hablar("Buenas noches, ${Prefs.trato}. Así sueno ahora.") }, arriba = dp(10))
        voz.agregar(interruptor("Responder al activarme", "Decir «¿Sí, ${Prefs.trato}?» al escuchar «Yarvis». Si lo apaga, solo sonará un tono.", Prefs.responderAlActivar) { Prefs.responderAlActivar = it }, arriba = dp(8))

        // ACTIVACIÓN
        val act = seccion("Activación por voz")
        act.addView(interruptor("Conversación continua", "Después de responder sigo escuchándole, sin decir «Yarvis». Para terminar diga «eso es todo», «gracias, es todo» o «adiós».", Prefs.conversacionContinua) { Prefs.conversacionContinua = it })
        act.addView(interruptor("Escuchar «Yarvis»", "Funciona sin internet. Nada de lo que escucho sale de su celular.", Prefs.escuchaActiva) {
            Prefs.escuchaActiva = it
            if (it) ServicioEscucha.iniciar(this) else ServicioEscucha.actualizar(this)
        })
        act.agregar(texto("¿Cuándo escuchar?", 14f, Ui.TEXTO_SUAVE), arriba = dp(10))
        val modos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modos.addView(chip("Siempre (incluso bloqueado)", Prefs.modoEscucha == "siempre") { Prefs.modoEscucha = "siempre"; ServicioEscucha.actualizar(this); dibujar() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        modos.addView(chip("Pantalla encendida", Prefs.modoEscucha == "pantalla") { Prefs.modoEscucha = "pantalla"; ServicioEscucha.actualizar(this); dibujar() })
        act.agregar(modos, arriba = dp(8))
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        act.agregar(estadoPermiso(pm.isIgnoringBatteryOptimizations(packageName), "Sin restricciones de batería", "Permitir funcionar sin restricciones") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }, arriba = dp(8))
        act.agregar(estadoPermiso(Settings.canDrawOverlays(this), "Puede aparecer sobre otras apps", "Permitir aparecer sobre otras apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        act.agregar(texto("En Xiaomi, Huawei, Oppo, Vivo o Samsung, active también «Inicio automático» y quite las restricciones de batería de YARVIS en los ajustes del celular para que nunca se detenga.", 13f, Ui.TEXTO_SUAVE), arriba = dp(4))

        // PRUEBA DE ACTIVACIÓN
        val prueba = seccion("Prueba de la palabra «Yarvis»")
        prueba.addView(texto("Diga «Yarvis» cerca del celular. Aquí verá lo que entiende el reconocedor sin internet:", 15f, Ui.TEXTO))
        val oido = texto(if (ServicioEscucha.escuchandoPalabra) "…" else "La escucha no está activa.", 18f, Ui.CIAN_CLARO, Ui.fuerte)
        prueba.agregar(oido, arriba = dp(8))
        ServicioEscucha.alOir = { t -> runOnUiThread { oido.text = "«$t»" + if (ServicioEscucha.contienePalabra(t)) "  ✓" else "" } }
        prueba.agregar(texto("Si al decir «Yarvis» aparece otra palabra (por ejemplo «llar vis»), escríbala aquí para que también me active. Puede poner varias separadas por coma.", 14f, Ui.TEXTO_SUAVE), arriba = dp(10))
        val cExtra = campo("Ejemplo: llar vis, yar bis", Prefs.palabrasExtra)
        prueba.agregar(cExtra, arriba = dp(6))
        prueba.agregar(boton("GUARDAR PALABRAS", false) { Prefs.palabrasExtra = cExtra.text.toString(); Voz.hablar("Guardado, ${Prefs.trato}.") }, arriba = dp(10))

        // IA
        val ia = seccion("Inteligencia artificial")
        ia.addView(texto("Tengo varios cerebros gratuitos que se turnan: si uno agota su cupo, sigo con el siguiente.", 15f, Ui.TEXTO))
        for ((nombre, ok) in CerebroIA.estado()) {
            ia.agregar(texto((if (ok) "●  " else "○  ") + nombre + if (ok) "  · activo" else "  · sin clave", 16f, if (ok) Ui.VERDE else Ui.TEXTO_SUAVE, Ui.fuerte), arriba = dp(6))
        }
        if (CerebroIA.ultimoProveedor.isNotBlank()) ia.agregar(texto("Última respuesta: ${CerebroIA.ultimoProveedor}", 13f, Ui.TEXTO_SUAVE), arriba = dp(6))
        ia.agregar(texto("Claves propias (opcional, reemplazan a las incluidas)", 14f, Ui.TEXTO_SUAVE), arriba = dp(14))
        val cGroq = campo("Groq · gsk_…", Prefs.claveGroqPropia, clave = true)
        ia.agregar(cGroq, arriba = dp(6))
        val cCere = campo("Cerebras · csk-…", Prefs.claveCerebrasPropia, clave = true)
        ia.agregar(cCere, arriba = dp(6))
        val cClave = campo("Google Gemini · AIza…", Prefs.claveIAPropia, clave = true)
        ia.agregar(cClave, arriba = dp(6))
        val filaIA = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filaIA.agregar(boton("GUARDAR", false) {
            Prefs.claveGroqPropia = cGroq.text.toString()
            Prefs.claveCerebrasPropia = cCere.text.toString()
            Prefs.claveIAPropia = cClave.text.toString()
            dibujar()
        }, ancho = 0, peso = 1f)
        filaIA.addView(android.view.View(this), LinearLayout.LayoutParams(dp(10), 1))
        filaIA.agregar(boton("PROBAR", false) {
            Prefs.claveGroqPropia = cGroq.text.toString()
            Prefs.claveCerebrasPropia = cCere.text.toString()
            Prefs.claveIAPropia = cClave.text.toString()
            Yarvis.procesar("Preséntate en una sola oración.")
            finish()
        }, ancho = 0, peso = 1f)
        ia.agregar(filaIA, arriba = dp(12))

        // MENSAJES
        val msj = seccion("Mensajes y notificaciones")
        msj.addView(interruptor("Leer mensajes en voz alta", "WhatsApp, Telegram, Messenger, SMS…", Prefs.leerMensajes) { Prefs.leerMensajes = it })
        msj.addView(interruptor("Leer todas las notificaciones", "También las de otras apps (no solo mensajes).", Prefs.leerTodasLasApps) { Prefs.leerTodasLasApps = it })
        msj.addView(estadoPermiso(ServicioNotificaciones.permisoConcedido(this), "Acceso a notificaciones concedido", "Conceder acceso a notificaciones") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        msj.addView(interruptor("Enviar WhatsApp automáticamente", "YARVIS toca «Enviar» por usted.", Prefs.whatsappAutomatico) { Prefs.whatsappAutomatico = it })
        msj.addView(estadoPermiso(ServicioAccesibilidad.permisoConcedido(this), "Accesibilidad activada", "Activar accesibilidad de YARVIS") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        // PERMISOS
        val per = seccion("Permisos")
        per.addView(texto("Revise y conceda todos los permisos que YARVIS necesita.", 15f, Ui.TEXTO))
        per.agregar(boton("REVISAR PERMISOS", false) {
            startActivity(Intent(this, BienvenidaActivity::class.java).putExtra(BienvenidaActivity.EXTRA_SOLO_PERMISOS, true))
        }, arriba = dp(10))

        // DATOS
        val datos = seccion("Datos")
        datos.addView(boton("BORRAR CONVERSACIÓN", false) { Yarvis.borrarHistorial(); Voz.hablar("Conversación borrada.") })
        datos.agregar(boton("BORRAR NOTAS", false) { Prefs.notas = ""; Voz.hablar("Notas borradas.") }, arriba = dp(8))
        datos.agregar(boton("BORRAR RECORDATORIOS", false) { Recordatorios.borrarTodos(this); Voz.hablar("Recordatorios borrados.") }, arriba = dp(8))
        datos.agregar(boton("REPETIR CONFIGURACIÓN INICIAL", false) {
            Prefs.configurado = false
            startActivity(Intent(this, BienvenidaActivity::class.java))
            finishAffinity()
        }, arriba = dp(8))

        // ACERCA DE
        val acerca = seccion("Acerca de")
        val logo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        logo.addView(NucleoView(this).apply { compacto = true }, LinearLayout.LayoutParams(dp(72), dp(72)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        info.addView(texto("YARVIS", 22f, Ui.CIAN, Ui.titulo, 0.3f))
        info.addView(texto("Versión ${BuildConfig.VERSION_NAME}", 14f, Ui.TEXTO_SUAVE))
        logo.addView(info)
        acerca.addView(logo)
        acerca.agregar(texto("Desarrollado por VELKYRA", 18f, Ui.TEXTO, Ui.fuerte), arriba = dp(12))
        acerca.agregar(texto("Soporte y contacto: ${Prefs.CORREO_SOPORTE}", 16f, Ui.CIAN_CLARO).apply {
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Prefs.CORREO_SOPORTE}"))
                        .putExtra(Intent.EXTRA_SUBJECT, "Soporte YARVIS ${BuildConfig.VERSION_NAME}"))
                } catch (_: Exception) {}
            }
        }, arriba = dp(6))
        acerca.agregar(texto("© 2026 VELKYRA. Todos los derechos reservados.", 13f, Ui.TEXTO_SUAVE), arriba = dp(12))
        acerca.agregar(texto("Reconocimiento de voz sin conexión: Vosk (Apache 2.0). Clima: Open-Meteo. Fuentes Orbitron y Rajdhani (SIL OFL).", 12f, Ui.TEXTO_SUAVE), arriba = dp(6))
    }

    override fun onPause() {
        ServicioEscucha.alOir = null
        super.onPause()
    }

    private fun elegirVoz() {
        val ops = Voz.opciones()
        if (ops.isEmpty()) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts"))) } catch (_: Exception) {}
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Elija una voz")
            .setItems(ops.map { it.etiqueta }.toTypedArray()) { _, i ->
                Prefs.voz = ops[i].voz.name
                Voz.aplicarVoz()
                Voz.hablar("Buenas noches, ${Prefs.trato}. ¿Le gusta esta voz?")
                dibujar()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }
}
