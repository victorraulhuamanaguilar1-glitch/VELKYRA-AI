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
        perfil.agregar(interruptor("Modo confianza", "Le hablo de tú, relajado y directo, como un amigo.", Prefs.modoConfianza) { Prefs.modoConfianza = it }, arriba = dp(8))
        perfil.agregar(interruptor("Yo soy el creador de ODÍN", "Si le pregunta «¿quién te creó?», responderá que fue usted.", Prefs.soyCreador) { Prefs.soyCreador = it }, arriba = dp(8))
        perfil.agregar(boton("GUARDAR PERFIL", false) {
            Prefs.nombre = cNombre.text.toString()
            Prefs.tratoGuardado = cTrato.text.toString()
            Prefs.ciudad = cCiudad.text.toString()
            Voz.hablar("Perfil guardado, ${Prefs.trato}.")
        }, arriba = dp(14))

        // VOZ
        val voz = seccion("Voz")
        voz.addView(selectorVoz())
        voz.agregar(interruptor("Responder al activarme", "Decir «¿Sí, ${Prefs.trato}?» al escuchar «${Prefs.palabraActivacion}». Si lo apaga, solo sonará un tono.", Prefs.responderAlActivar) { Prefs.responderAlActivar = it }, arriba = dp(8))

        // ACTIVACIÓN
        val act = seccion("Activación por voz")
        act.addView(texto("Tiempo de espera en conversación", 14f, Ui.TEXTO_SUAVE))
        val esperas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (seg in listOf(20, 30, 60)) esperas.addView(chip("$seg segundos", Prefs.esperaConversacion == seg) { Prefs.esperaConversacion = seg; dibujar() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        act.agregar(esperas, arriba = dp(6))
        act.addView(interruptor("Conversación continua", "Después de responder sigo escuchándole, sin decir «${Prefs.palabraActivacion}». Para terminar diga «eso es todo», «gracias, es todo» o «adiós».", Prefs.conversacionContinua) { Prefs.conversacionContinua = it })
        act.addView(interruptor("Escuchar «${Prefs.palabraActivacion}»", "Funciona sin internet. Nada de lo que escucho sale de su celular.", Prefs.escuchaActiva) {
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
        act.agregar(texto("En Xiaomi, Huawei, Oppo, Vivo o Samsung, active también «Inicio automático» y quite las restricciones de batería de ODÍN en los ajustes del celular para que nunca se detenga.", 13f, Ui.TEXTO_SUAVE), arriba = dp(4))

        // SEGURIDAD POR VOZ
        val seg = seccion("Seguridad por voz")
        val hayDueno = VozDueno.hayDueno()
        seg.addView(texto(if (hayDueno) "✓ Su voz está registrada" else "○ Aún no conozco su voz", 16f, if (hayDueno) Ui.VERDE else Ui.AMBAR, Ui.fuerte))
        seg.agregar(interruptor("Solo obedecer mi voz", "Si alguien más dice «${Prefs.palabraActivacion}», no le haré caso (salvo a las personas que usted autorice).", Prefs.verificarVoz && hayDueno) { on ->
            if (on && !VozDueno.hayDueno()) startActivity(android.content.Intent(this, VozDuenoActivity::class.java))
            Prefs.verificarVoz = on
        }, arriba = dp(6))
        seg.agregar(boton(if (hayDueno) "VOLVER A APRENDER MI VOZ" else "APRENDER MI VOZ", !hayDueno) {
            startActivity(android.content.Intent(this, VozDuenoActivity::class.java))
        }, arriba = dp(8))
        seg.agregar(texto("Exigencia al reconocer su voz", 14f, Ui.TEXTO_SUAVE), arriba = dp(14))
        val exig = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((nombre, valor) in listOf("Flexible" to 0.22f, "Normal" to 0.30f, "Estricta" to 0.40f)) {
            val activo = kotlin.math.abs(Prefs.umbralVoz - valor) < 0.04f
            exig.addView(chip(nombre, activo) { Prefs.umbralVoz = valor; dibujar() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        seg.agregar(exig, arriba = dp(6))
        seg.agregar(texto("Si a veces no le reconoce, elija «Flexible». Si otra persona logra activarme, elija «Estricta».", 13f, Ui.TEXTO_SUAVE), arriba = dp(4))
        if (ServicioEscucha.ultimaSimilitud >= 0) seg.agregar(texto("Última voz escuchada: ${(ServicioEscucha.ultimaSimilitud * 100).toInt()}% de coincidencia (se exige ${(Prefs.umbralVoz * 100).toInt()}%)", 13f, Ui.CIAN_CLARO), arriba = dp(4))

        seg.agregar(texto("Personas autorizadas", 14f, Ui.TEXTO_SUAVE), arriba = dp(16))
        val inv = VozDueno.invitados()
        if (inv.isEmpty()) seg.agregar(texto("Ninguna todavía. También puede decir: «${Prefs.palabraActivacion}, aprende la voz de María».", 14f, Ui.TEXTO_SUAVE), arriba = dp(4))
        for (p in inv) {
            val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            fila.agregar(texto("●  ${p.nombre}", 17f, Ui.TEXTO, Ui.fuerte), ancho = 0, peso = 1f)
            fila.addView(texto("Quitar", 15f, Ui.ROJO, Ui.fuerte).apply { setPadding(dp(12), dp(8), 0, dp(8)); setOnClickListener { VozDueno.quitar(p.nombre); dibujar() } })
            seg.agregar(fila, arriba = dp(4))
        }
        val cInv = campo("Nombre de la persona")
        seg.agregar(cInv, arriba = dp(8))
        seg.agregar(boton("AGREGAR PERSONA", false) {
            val n = cInv.text.toString().trim()
            if (n.isNotBlank()) startActivity(android.content.Intent(this, VozDuenoActivity::class.java).putExtra(VozDuenoActivity.EXTRA_NOMBRE, n))
        }, arriba = dp(8))
        if (VozDueno.modoInvitado) {
            val hasta = java.text.SimpleDateFormat("h:mm a", java.util.Locale.forLanguageTag("es-PE")).format(java.util.Date(Prefs.invitadoHasta))
            seg.agregar(texto("Modo invitado activo hasta las $hasta: atiendo a cualquier persona.", 14f, Ui.AMBAR, Ui.fuerte), arriba = dp(12))
            seg.agregar(boton("DESACTIVAR MODO INVITADO", false) { Prefs.invitadoHasta = 0; dibujar() }, arriba = dp(6))
        }
        seg.agregar(interruptor("Avisar cuando no reconozco una voz", null, Prefs.avisarVozDesconocida) { Prefs.avisarVozDesconocida = it }, arriba = dp(8))

        // PALABRA DE ACTIVACIÓN
        val pal = seccion("Palabra de activación")
        val entrenada = Prefs.muestras().isNotEmpty()
        pal.addView(texto("Palabra actual: «${Prefs.palabraActivacion}»", 18f, Ui.TEXTO, Ui.fuerte))
        pal.agregar(texto(if (entrenada) "✓ Entrenada con su voz" else "○ Sin entrenar (se recomienda entrenarla)", 15f, if (entrenada) Ui.VERDE else Ui.AMBAR, Ui.fuerte), arriba = dp(4))
        pal.agregar(boton(if (entrenada) "VOLVER A ENTRENAR / CAMBIAR PALABRA" else "ENTRENAR MI PALABRA", !entrenada) {
            startActivity(android.content.Intent(this, EntrenamientoActivity::class.java))
        }, arriba = dp(12))
        pal.agregar(texto("Prueba en vivo — diga su palabra y mire lo que entiendo:", 14f, Ui.TEXTO_SUAVE), arriba = dp(14))
        val oido = texto(if (ServicioEscucha.escuchandoPalabra) "…" else "La escucha no está activa en este momento.", 17f, Ui.CIAN_CLARO, Ui.fuerte)
        pal.agregar(oido, arriba = dp(4))
        ServicioEscucha.alOir = { t -> runOnUiThread { oido.text = "«$t»" + if (ServicioEscucha.contienePalabra(t)) "  ✓ ¡me activo!" else "" } }

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
        msj.addView(interruptor("Enviar WhatsApp automáticamente", "ODÍN toca «Enviar» por usted.", Prefs.whatsappAutomatico) { Prefs.whatsappAutomatico = it })
        msj.addView(estadoPermiso(ServicioAccesibilidad.permisoConcedido(this), "Accesibilidad activada", "Activar accesibilidad de ODÍN") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        // CHATS AUTOMÁTICOS
        val ch = seccion("Chats automáticos")
        ch.addView(texto("ODÍN responde por usted los chats que elija (WhatsApp, Messenger, Telegram), imitando su forma de escribir. Los temas de dinero, citas o emergencias siempre se los consulta.", 14f, Ui.TEXTO_SUAVE))
        val lista = ChatAsistido.chats()
        if (lista.isEmpty()) ch.agregar(texto("Ningún chat todavía. También puede decir: «Odín, chatea con Juan por mí».", 14f, Ui.TEXTO_SUAVE), arriba = dp(8))
        for (c in lista) {
            val fila = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Ui.panel(this@AjustesActivity, 12f, Ui.PANEL_FUERTE, Ui.BORDE); setPadding(dp(14), dp(10), dp(14), dp(10)) }
            fila.addView(texto(c.nombre, 17f, Ui.TEXTO, Ui.fuerte))
            val hasta = java.text.SimpleDateFormat("h:mm a", java.util.Locale.forLanguageTag("es-PE")).format(java.util.Date(c.hasta))
            fila.addView(texto((if (c.activo) "● Activo hasta las $hasta" else "○ Inactivo") + " · " + (if (c.automatico) "responde solo" else "me pregunta antes") +
                (if (c.guia.isNotBlank()) " · con guía de estilo" else " · sin guía de estilo"), 13f, if (c.activo) Ui.VERDE else Ui.TEXTO_SUAVE))
            val botones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            botones.addView(chip(if (c.activo) "Detener" else "Activar 3 h") {
                if (c.activo) ChatAsistido.detener(c.nombre) else ChatAsistido.guardar(c.copy(hasta = System.currentTimeMillis() + 3 * 3_600_000L))
                dibujar()
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            botones.addView(chip("Editar") { editarChat(c) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            botones.addView(chip("Quitar") { ChatAsistido.quitar(c.nombre); dibujar() })
            fila.agregar(botones, arriba = dp(8))
            ch.agregar(fila, arriba = dp(8))
        }
        ch.agregar(boton("AGREGAR CHAT", false) { editarChat(null) }, arriba = dp(10))

        // MIS APPS
        val apps = seccion("Mis apps")
        apps.addView(texto("Elija qué app uso para cada cosa: cualquiera de las que tiene instaladas (toque «+ Otra app…»). Por ejemplo, si elige YouTube para música, «pon música de Shakira» siempre se abrirá ahí. También puede decir «pon … en» el nombre de cualquier app suya.", 14f, Ui.TEXTO_SUAVE))
        for (cat in MisApps.CATEGORIAS) {
            val instaladas = MisApps.opcionesDe(this, cat.id)
            apps.agregar(texto(cat.titulo, 14f, Ui.CIAN_CLARO, Ui.fuerte), arriba = dp(10))
            val fila = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val actual = MisApps.elegida(this, cat.id)
            for (o in instaladas) chips.addView(chip(o.nombre, o.paquete == actual) { MisApps.elegir(this, cat.id, o.paquete); dibujar() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            chips.addView(chip("+ Otra app…") { elegirOtraApp(cat.id, cat.titulo) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            fila.addView(chips)
            apps.agregar(fila, arriba = dp(4))
        }
        apps.agregar(texto("Apps con nombre propio", 14f, Ui.CIAN_CLARO, Ui.fuerte), arriba = dp(14))
        for (a in MisApps.alias(this)) {
            val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            fila.agregar(texto("«${a.nombre}» → ${a.etiqueta}", 16f, Ui.TEXTO), ancho = 0, peso = 1f)
            fila.addView(texto("Quitar", 15f, Ui.ROJO, Ui.fuerte).apply { setPadding(dp(12), dp(8), 0, dp(8)); setOnClickListener { MisApps.quitarAlias(this@AjustesActivity, a.nombre); dibujar() } })
            apps.agregar(fila, arriba = dp(4))
        }
        apps.agregar(texto("Ejemplo: llame «mi banco» a la app de su banco y diga «Odín, abre mi banco».", 13f, Ui.TEXTO_SUAVE), arriba = dp(4))
        apps.agregar(boton("AGREGAR APP CON NOMBRE PROPIO", false) { agregarAlias() }, arriba = dp(10))

        // MODOS Y EXTRAS
        val secModos = seccion("Modos")
        secModos.addView(texto("Manejo: le leo todo en voz alta y responde con la voz. Dormir: no le interrumpo (las alarmas sí suenan). Reunión: vibración y silencio. También puede decir «Odín, modo manejo».", 14f, Ui.TEXTO_SUAVE))
        val filaModos = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chipsModos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((id, nom) in listOf(Modos.NORMAL to "Normal", Modos.MANEJO to "Manejo", Modos.DORMIR to "Dormir", Modos.REUNION to "Reunión")) {
            chipsModos.addView(chip(nom, Modos.actual == id) {
                val dicho = if (id == Modos.NORMAL) Modos.desactivar(this) else Modos.activar(this, id)
                Voz.hablar(dicho); dibujar()
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        filaModos.addView(chipsModos)
        secModos.agregar(filaModos, arriba = dp(8))
        secModos.agregar(interruptor("Encontrarme con dos aplausos", "Si no encuentra el celular, aplauda dos veces y sonaré fuerte. Solo con la pantalla apagada.", Prefs.aplausos) {
            Prefs.aplausos = it
            if (it) Prefs.modoEscucha = "siempre"
            ServicioEscucha.reiniciar()
        }, arriba = dp(10))

        // CORREO (GMAIL)
        val co = seccion("Correo (Gmail)")
        co.addView(estadoPermiso(Correo.configurado, "Gmail conectado: ${Prefs.correoUsuario}", "Gmail sin configurar") {})
        co.agregar(texto("Para leer y responder sus correos por voz:\n1. Active la «Verificación en 2 pasos» en su cuenta de Google.\n2. Cree una «contraseña de aplicación» con el nombre ODÍN.\n3. Escriba aquí su correo y pegue las 16 letras.", 14f, Ui.TEXTO_SUAVE), arriba = dp(4))
        co.agregar(boton("CREAR CONTRASEÑA DE APLICACIÓN", false) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/apppasswords"))) } catch (_: Exception) {}
        }, arriba = dp(8))
        val cCorreo = campo("su.correo@gmail.com", Prefs.correoUsuario).apply { inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or android.text.InputType.TYPE_CLASS_TEXT }
        val cClaveCorreo = campo("Contraseña de aplicación (16 letras)", Prefs.correoClave, clave = true)
        co.agregar(cCorreo, arriba = dp(8))
        co.agregar(cClaveCorreo, arriba = dp(6))
        co.agregar(boton("GUARDAR Y PROBAR", true) {
            Prefs.correoUsuario = cCorreo.text.toString()
            Prefs.correoClave = cClaveCorreo.text.toString()
            android.widget.Toast.makeText(this, "Probando conexión con Gmail…", android.widget.Toast.LENGTH_SHORT).show()
            Thread {
                val err = if (Correo.configurado) Correo.probar() else "Escriba su correo y la contraseña de aplicación."
                runOnUiThread {
                    Voz.hablar(err ?: "Listo. Su Gmail quedó conectado. Diga: Odín, ¿tengo correos nuevos?")
                    android.widget.Toast.makeText(this, err ?: "✓ Gmail conectado", android.widget.Toast.LENGTH_LONG).show()
                    dibujar()
                }
            }.start()
        }, arriba = dp(8))
        if (Correo.configurado) co.agregar(boton("DESCONECTAR GMAIL", false) { Prefs.correoUsuario = ""; Prefs.correoClave = ""; dibujar() }, arriba = dp(8))

        // PERMISOS
        val per = seccion("Permisos")
        per.addView(texto("Revise y conceda todos los permisos que ODÍN necesita.", 15f, Ui.TEXTO))
        per.agregar(boton("REVISAR PERMISOS", false) {
            startActivity(Intent(this, BienvenidaActivity::class.java).putExtra(BienvenidaActivity.EXTRA_SOLO_PERMISOS, true))
        }, arriba = dp(10))

        // DATOS
        val datos = seccion("Datos")
        val ultimo = getSharedPreferences("yarvis", MODE_PRIVATE).getLong("respaldo_ultimo", 0L)
        datos.addView(texto("Copia de seguridad: su voz registrada, la palabra entrenada, sus chats con estilo, sus apps, notas, gastos y ajustes. Se guarda sola cada día en Descargas › ODIN." +
            if (ultimo > 0) "\nÚltima copia: " + java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.forLanguageTag("es-PE")).format(java.util.Date(ultimo)) else "", 14f, Ui.TEXTO_SUAVE))
        datos.agregar(boton("GUARDAR COPIA AHORA", true) {
            Thread {
                val ok = Respaldo.guardarEnDescargas(this)
                runOnUiThread { Voz.hablar(if (ok) "Copia guardada en Descargas, carpeta ODIN." else "No pude guardar la copia."); dibujar() }
            }.start()
        }, arriba = dp(8))
        datos.agregar(boton("GUARDAR COPIA EN… (Drive, USB…)", false) {
            try {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, Respaldo.ARCHIVO), 61)
            } catch (_: Exception) {}
        }, arriba = dp(8))
        datos.agregar(boton("RESTAURAR UNA COPIA", false) {
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"), 62)
            } catch (_: Exception) {}
        }, arriba = dp(8))
        datos.agregar(boton("BORRAR MENSAJES GUARDADOS", false) { Bandeja.borrar(); Voz.hablar("Mensajes guardados borrados.") }, arriba = dp(8))
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
        info.addView(texto("ODÍN", 22f, Ui.CIAN, Ui.titulo, 0.3f))
        info.addView(texto("Versión ${BuildConfig.VERSION_NAME}", 14f, Ui.TEXTO_SUAVE))
        logo.addView(info)
        acerca.addView(logo)
        acerca.agregar(texto("Desarrollado por VELKYRA", 18f, Ui.TEXTO, Ui.fuerte), arriba = dp(12))
        acerca.agregar(texto("Soporte y contacto: ${Prefs.CORREO_SOPORTE}", 16f, Ui.CIAN_CLARO).apply {
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Prefs.CORREO_SOPORTE}"))
                        .putExtra(Intent.EXTRA_SUBJECT, "Soporte ODÍN ${BuildConfig.VERSION_NAME}"))
                } catch (_: Exception) {}
            }
        }, arriba = dp(6))
        acerca.agregar(texto("© 2026 VELKYRA. Todos los derechos reservados.", 13f, Ui.TEXTO_SUAVE), arriba = dp(12))
        acerca.agregar(texto("Reconocimiento de voz sin conexión: Vosk (Apache 2.0). Clima: Open-Meteo. Fuentes Orbitron y Rajdhani (SIL OFL).", 12f, Ui.TEXTO_SUAVE), arriba = dp(6))
    }

    @Deprecated("Requerido para los selectores de archivos")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            61 -> Voz.hablar(if (Respaldo.escribir(this, uri)) "Copia de seguridad guardada." else "No pude guardar la copia.")
            62 -> {
                val n = Respaldo.leer(this, uri)
                if (n < 0) { Voz.hablar("Ese archivo no es una copia de ODÍN."); return }
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Copia restaurada")
                    .setMessage("Restauré $n datos. ODÍN se reiniciará para usarlos.")
                    .setPositiveButton("Reiniciar") { _, _ -> Respaldo.reiniciar(this) }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    override fun onPause() {
        ServicioEscucha.alOir = null
        super.onPause()
    }

    private fun editarChat(c: ChatAsistido.Chat?) {
        val pad = dp(18)
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        caja.addView(texto("Nombre del contacto (tal como aparece en WhatsApp)", 13f, Ui.TEXTO_SUAVE))
        val cNombre = campo("Ejemplo: Juan", c?.nombre ?: "")
        caja.agregar(cNombre, arriba = dp(4))
        var automatico = c?.automatico ?: true
        caja.agregar(interruptor("Responder solo", "Si lo apaga, le propondré la respuesta y la enviaré solo si me dice que sí.", automatico) { automatico = it }, arriba = dp(6))
        caja.agregar(texto("Guía de estilo: pegue aquí un trozo real de su chat con esa persona (mientras más largo, mejor imito cómo escribe usted).", 13f, Ui.TEXTO_SUAVE), arriba = dp(8))
        caja.agregar(texto("En WhatsApp: abra el chat → ⋮ → Más → Exportar chat → Sin archivos, y copie una parte. O mantenga presionados varios mensajes y cópielos.", 12f, Ui.TEXTO_SUAVE), arriba = dp(2))
        val cGuia = android.widget.EditText(this).apply {
            setText(c?.guia ?: "")
            hint = "Pegue aquí la conversación…"
            minLines = 5
            maxLines = 10
            gravity = android.view.Gravity.TOP
            textSize = 14f
            setTextColor(Ui.TEXTO)
            setHintTextColor(Ui.TEXTO_SUAVE)
            background = Ui.panel(this@AjustesActivity, 12f, Ui.PANEL_FUERTE, Ui.BORDE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        caja.agregar(cGuia, arriba = dp(6))
        val scroll = android.widget.ScrollView(this).apply { addView(caja) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (c == null) "Nuevo chat automático" else "Editar chat")
            .setView(scroll)
            .setPositiveButton("Guardar y activar") { _, _ ->
                val n = cNombre.text.toString().trim()
                if (n.isNotBlank()) {
                    if (c != null && c.nombre != n) ChatAsistido.quitar(c.nombre)
                    ChatAsistido.guardar(ChatAsistido.Chat(n, automatico, cGuia.text.toString().take(20000), System.currentTimeMillis() + 3 * 3_600_000L))
                    dibujar()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun elegirOtraApp(categoria: String, titulo: String) {
        val apps = MisApps.instaladas(this)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("App para $titulo")
            .setItems(apps.map { it.nombre }.toTypedArray()) { _, i -> MisApps.elegir(this, categoria, apps[i].paquete); dibujar() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun agregarAlias() {
        val apps = MisApps.instaladas(this)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Elija la app")
            .setItems(apps.map { it.nombre }.toTypedArray()) { _, i ->
                val app = apps[i]
                val cNombre = campo("Ejemplo: mi banco")
                val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0); addView(cNombre) }
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("¿Cómo llamará a ${app.nombre}?")
                    .setView(caja)
                    .setPositiveButton("Guardar") { _, _ ->
                        val n = cNombre.text.toString().trim()
                        if (n.isNotBlank()) { MisApps.agregarAlias(this, n, app.paquete, app.nombre); dibujar() }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
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
