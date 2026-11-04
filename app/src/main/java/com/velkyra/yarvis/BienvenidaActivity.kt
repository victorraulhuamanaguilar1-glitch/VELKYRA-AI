package com.velkyra.yarvis

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Configuración inicial: nombre, trato, voz, IA y permisos. */
class BienvenidaActivity : Base() {

    companion object {
        const val EXTRA_SOLO_PERMISOS = "solo_permisos"

        fun permisosNormales(): Array<String> {
            val l = mutableListOf(
                Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
            return l.toTypedArray()
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var contenedor: FrameLayout
    private lateinit var puntos: LinearLayout
    private var paso = 0
    private val totalPasos = 7
    private var soloPermisos = false

    // Datos que se van llenando
    private var nombre = Prefs.nombre
    private var trato = Prefs.tratoGuardado.ifBlank { "señor" }

    // Asistente de permisos especiales
    private var asistente = false
    private val intentados = mutableSetOf<String>()
    private var refrescarPermisos: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        soloPermisos = intent.getBooleanExtra(EXTRA_SOLO_PERMISOS, false)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        puntos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(6)) }
        col.addView(puntos)
        contenedor = FrameLayout(this)
        col.agregar(contenedor, alto = 0, peso = 1f)
        montar(col)
        if (soloPermisos) { puntos.visibility = View.GONE; mostrar(4) } else mostrar(0)
    }

    private fun dibujarPuntos() {
        puntos.removeAllViews()
        for (i in 0 until totalPasos) {
            val v = View(this).apply { background = Ui.panel(this@BienvenidaActivity, 4f, if (i <= paso) Ui.CIAN else 0x33FFFFFF, 0x00000000, 0f) }
            puntos.addView(v, LinearLayout.LayoutParams(if (i == paso) dp(26) else dp(10), dp(5)).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
    }

    private fun mostrar(n: Int) {
        paso = n
        dibujarPuntos()
        val vista = when (n) {
            0 -> pasoIntro()
            1 -> pasoNombre()
            2 -> pasoVoz()
            3 -> pasoIA()
            4 -> pasoPermisos()
            5 -> pasoPalabra()
            else -> pasoListo()
        }
        contenedor.removeAllViews()
        vista.alpha = 0f
        vista.translationX = dpf(30f)
        contenedor.addView(vista, FrameLayout.LayoutParams(-1, -1))
        vista.animate().alpha(1f).translationX(0f).setDuration(350).start()
    }

    /** Estructura común de cada paso: contenido desplazable + botones abajo. */
    private fun plantilla(titulo: String, subtitulo: String?, cuerpo: LinearLayout.() -> Unit, principal: String, alPrincipal: () -> Unit, secundario: String? = null, alSecundario: (() -> Unit)? = null): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16)) }
        c.addView(texto(titulo, 26f, Ui.TEXTO, Ui.titulo, 0.04f))
        if (subtitulo != null) c.agregar(texto(subtitulo, 17f, Ui.TEXTO_SUAVE), arriba = dp(8))
        c.espacio(18)
        c.cuerpo()
        scroll.addView(c)
        col.agregar(scroll, alto = 0, peso = 1f)
        val botones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(24), dp(8), dp(24), dp(22)); gravity = Gravity.CENTER_VERTICAL }
        if (secundario != null && alSecundario != null) {
            botones.agregar(boton(secundario, false, alSecundario), ancho = 0, peso = 1f)
            botones.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        }
        botones.agregar(boton(principal, true, alPrincipal), ancho = 0, peso = 1f)
        col.addView(botones)
        return col
    }

    // ------------------------------------------------------------------ 0
    private fun pasoIntro(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), dp(22)) }
        col.agregar(View(this), alto = 0, peso = 1f)
        col.agregar(NucleoView(this), ancho = dp(230), alto = dp(230)).also { (it.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER }
        col.agregar(texto("Hola. Soy YARVIS.", 28f, Ui.TEXTO, Ui.titulo).apply { gravity = Gravity.CENTER }, arriba = dp(24))
        col.agregar(texto("Su asistente personal con inteligencia artificial. Configuremos todo en menos de un minuto.", 18f, Ui.TEXTO_SUAVE).apply { gravity = Gravity.CENTER }, arriba = dp(10))
        col.agregar(View(this), alto = 0, peso = 1f)
        col.agregar(boton("COMENZAR", true) { mostrar(1) })
        col.agregar(texto("Desarrollado por VELKYRA", 12f, Ui.TEXTO_SUAVE).apply { gravity = Gravity.CENTER; alpha = 0.6f }, arriba = dp(14))
        h.postDelayed({ if (paso == 0) Voz.hablar("Hola. Soy YARVIS, su asistente personal. Configuremos todo en menos de un minuto.") }, 600)
        return col
    }

    // ------------------------------------------------------------------ 1
    private fun pasoNombre(): View {
        lateinit var campoNombre: EditText
        lateinit var campoOtro: EditText
        lateinit var ejemplo: TextView
        lateinit var filaChips: LinearLayout
        val opciones = listOf("Señor", "Señora", "Por mi nombre", "Jefe", "Otro")

        fun actualizarEjemplo() {
            ejemplo.text = "Así le hablaré:  «Buenas noches, ${tratoFinal(campoNombre.text.toString())}. Todos los sistemas en línea.»"
        }

        fun dibujarChips() {
            filaChips.removeAllViews()
            for (o in opciones) {
                val activo = when (o) {
                    "Por mi nombre" -> trato == "#nombre"
                    "Otro" -> trato == "#otro"
                    else -> trato.equals(o, true)
                }
                filaChips.addView(chip(o, activo) {
                    trato = when (o) { "Por mi nombre" -> "#nombre"; "Otro" -> "#otro"; else -> o.lowercase() }
                    campoOtro.visibility = if (trato == "#otro") View.VISIBLE else View.GONE
                    dibujarChips(); actualizarEjemplo()
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
        }

        return plantilla("¿Cómo se llama?", "Así podré dirigirme a usted como corresponde.", {
            addView(etiqueta("Su nombre"))
            campoNombre = campo("Escriba su nombre", nombre)
            agregar(campoNombre, arriba = dp(8))
            espacio(22)
            addView(etiqueta("¿Cómo prefiere que le llame?"))
            val sc = HorizontalScrollView(this@BienvenidaActivity).apply { isHorizontalScrollBarEnabled = false }
            filaChips = LinearLayout(this@BienvenidaActivity).apply { orientation = LinearLayout.HORIZONTAL }
            sc.addView(filaChips)
            agregar(sc, arriba = dp(10))
            campoOtro = campo("Ejemplo: comandante, doctor, maestro…", if (trato == "#otro") "" else "")
            campoOtro.visibility = View.GONE
            agregar(campoOtro, arriba = dp(10))
            ejemplo = texto("", 16f, Ui.CIAN_CLARO)
            agregar(ejemplo, arriba = dp(18))
            campoNombre.addTextChangedListener(Cambio { actualizarEjemplo() })
            campoOtro.addTextChangedListener(Cambio { actualizarEjemplo() })
            dibujarChips(); actualizarEjemplo()
        }, "CONTINUAR", {
            nombre = campoNombre.text.toString().trim()
            val otro = campoOtro.text.toString().trim()
            if (trato == "#otro" && otro.isNotBlank()) trato = otro
            if (trato == "#otro") trato = "señor"
            Prefs.nombre = nombre
            Prefs.tratoGuardado = if (trato == "#nombre") nombre.ifBlank { "señor" } else trato
            mostrar(2)
        }, "ATRÁS", { mostrar(0) })
    }

    private fun tratoFinal(n: String) = when (trato) {
        "#nombre" -> n.ifBlank { "Víctor" }.trim()
        "#otro" -> "…"
        else -> trato
    }

    // ------------------------------------------------------------------ 2
    private fun pasoVoz(): View {
        val muestra = { "Buenas noches, ${Prefs.trato}. Todos los sistemas en línea. Estoy a su disposición." }
        return plantilla("Elija mi voz", "Toque una voz para escucharla. Le recomiendo una masculina y grave.", {
            val lista = LinearLayout(this@BienvenidaActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(lista)
            fun llenar() {
                lista.removeAllViews()
                val ops = Voz.opciones()
                if (ops.isEmpty()) {
                    lista.addView(texto("Cargando voces del celular…", 16f, Ui.TEXTO_SUAVE))
                    lista.agregar(boton("INSTALAR VOCES DE GOOGLE", false) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts"))) } catch (_: Exception) {}
                    }, arriba = dp(12))
                    h.postDelayed({ if (paso == 2) llenar() }, 1500)
                    return
                }
                val actual = Prefs.voz.ifBlank { Voz.vozActual() }
                for (o in ops.take(12)) {
                    val sel = o.voz.name == actual
                    val fila = LinearLayout(this@BienvenidaActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = Ui.panel(this@BienvenidaActivity, 14f, if (sel) 0x302DE2FF else Ui.PANEL, if (sel) Ui.CIAN else Ui.BORDE)
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                    }
                    fila.agregar(texto(o.etiqueta, 17f, if (sel) Ui.CIAN_CLARO else Ui.TEXTO, Ui.fuerte), ancho = 0, peso = 1f)
                    fila.addView(texto(if (sel) "✓" else "▶", 18f, Ui.CIAN, Ui.fuerte))
                    fila.setOnClickListener {
                        Prefs.voz = o.voz.name
                        Voz.probarVoz(o.voz, muestra())
                        llenar()
                    }
                    lista.agregar(fila, arriba = dp(8))
                }
            }
            llenar()
            espacio(20)
            addView(etiqueta("Tono (más grave ← → más agudo)"))
            agregar(deslizador(80, ((Prefs.tono - 0.5f) * 100).toInt()) { Prefs.tono = 0.5f + it / 100f; Voz.aplicarVoz() }, arriba = dp(6))
            espacio(12)
            addView(etiqueta("Velocidad"))
            agregar(deslizador(60, ((Prefs.velocidad - 0.7f) * 100).toInt()) { Prefs.velocidad = 0.7f + it / 100f; Voz.aplicarVoz() }, arriba = dp(6))
            agregar(boton("ESCUCHAR", false) { Voz.aplicarVoz(); Voz.hablar(muestra()) }, arriba = dp(16))
        }, "CONTINUAR", { Voz.aplicarVoz(); mostrar(3) }, "ATRÁS", { mostrar(1) })
    }

    // ------------------------------------------------------------------ 3
    private fun pasoIA(): View {
        lateinit var campoClave: EditText
        return plantilla("Inteligencia artificial", null, {
            val estado = tarjeta()
            if (Prefs.claveIncluida) {
                estado.addView(texto("●  ACTIVADA", 16f, Ui.VERDE, Ui.titulo, 0.15f))
                estado.agregar(texto("Ya puedo conversar con usted, responder preguntas, buscar información actual en internet, traducir y mucho más.", 16f, Ui.TEXTO), arriba = dp(8))
            } else {
                estado.addView(texto("●  SIN ACTIVAR", 16f, Ui.AMBAR, Ui.titulo, 0.15f))
                estado.agregar(texto("Sin la IA igual cumplo todas las órdenes del celular, pero no podré conversar ni responder preguntas libres.", 16f, Ui.TEXTO), arriba = dp(8))
            }
            addView(estado)
            espacio(20)
            addView(etiqueta(if (Prefs.claveIncluida) "Usar su propia clave (opcional)" else "Pegue su clave gratuita de Google"))
            campoClave = campo("AIza…", Prefs.claveIAPropia, clave = true)
            agregar(campoClave, arriba = dp(8))
            agregar(texto("Consígala gratis en aistudio.google.com/apikey con su cuenta de Google → «Create API key».", 14f, Ui.TEXTO_SUAVE), arriba = dp(8))
            agregar(boton("OBTENER CLAVE", false) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) } catch (_: Exception) {}
            }, arriba = dp(12))
        }, "CONTINUAR", {
            Prefs.claveIAPropia = campoClave.text.toString()
            mostrar(4)
        }, "ATRÁS", { mostrar(2) })
    }

    // ------------------------------------------------------------------ 4
    private data class Especial(val id: String, val titulo: String, val detalle: String, val ok: () -> Boolean, val abrir: () -> Unit)

    private fun especiales(): List<Especial> = listOf(
        Especial("overlay", "Aparecer sobre otras apps", "Para responderle al decir su palabra mientras usa otra aplicación.",
            { Settings.canDrawOverlays(this) },
            { abrir(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }),
        Especial("bateria", "Funcionar sin restricciones", "Para que el sistema no me apague en segundo plano.",
            { (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) },
            { abrir(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }),
        Especial("notif", "Leer notificaciones", "Para leerle en voz alta los mensajes que le llegan.",
            { ServicioNotificaciones.permisoConcedido(this) },
            {
                val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, ServicioNotificaciones::class.java).flattenToString())
                if (!abrir(i)) abrir(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }),
        Especial("acceso", "Accesibilidad (manos libres)", "Para enviar sus WhatsApp sin tocar la pantalla, bloquear el celular y tomar capturas.",
            { ServicioAccesibilidad.permisoConcedido(this) },
            {
                val ok = Build.VERSION.SDK_INT >= 33 && abrir(
                    Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                        .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(this, ServicioAccesibilidad::class.java).flattenToString())
                )
                if (!ok) abrir(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
    )

    private fun abrir(i: Intent): Boolean = try { startActivity(i); true } catch (_: Exception) { false }

    private fun pasoPermisos(): View {
        return plantilla("Permisos", "Concédalos todos de una vez para que pueda ayudarle en todo. Nada de lo que escucho sale de su celular sin su orden.", {
            val lista = LinearLayout(this@BienvenidaActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(lista)
            fun fila(titulo: String, detalle: String, ok: Boolean, alTocar: (() -> Unit)?) {
                val f = LinearLayout(this@BienvenidaActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Ui.panel(this@BienvenidaActivity, 14f)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                }
                val tx = LinearLayout(this@BienvenidaActivity).apply { orientation = LinearLayout.VERTICAL }
                tx.addView(texto(titulo, 17f, Ui.TEXTO, Ui.fuerte))
                tx.addView(texto(detalle, 14f, Ui.TEXTO_SUAVE))
                f.agregar(tx, ancho = 0, peso = 1f)
                f.addView(texto(if (ok) "✓" else "○", 22f, if (ok) Ui.VERDE else Ui.AMBAR, Ui.fuerte).apply { setPadding(dp(10), 0, 0, 0) })
                if (!ok && alTocar != null) f.setOnClickListener { alTocar() }
                lista.agregar(f, arriba = dp(8))
            }
            refrescarPermisos = {
                lista.removeAllViews()
                val faltanNormales = permisosNormales().filter { !tiene(it) }
                fila("Micrófono, contactos, llamadas, SMS, agenda, ubicación y avisos", "Permisos básicos para todas mis funciones.", faltanNormales.isEmpty()) {
                    requestPermissions(faltanNormales.toTypedArray(), 21)
                }
                for (e in especiales()) fila(e.titulo, e.detalle, e.ok()) { intentados.add(e.id); e.abrir() }
            }
            refrescarPermisos?.invoke()
            agregar(boton("CONCEDER TODO", true) { concederTodo() }, arriba = dp(18))
            val aviso = tarjeta("Importante")
            aviso.addView(texto("Si al activar «Leer notificaciones» o «Accesibilidad» aparece «Configuración restringida»: abra la información de la app → menú ⋮ (arriba a la derecha) → «Permitir configuración restringida», y vuelva a intentarlo.", 15f, Ui.TEXTO))
            aviso.agregar(boton("ABRIR INFORMACIÓN DE LA APP", false) {
                abrir(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, arriba = dp(12))
            agregar(aviso, arriba = dp(18))
        }, if (soloPermisos) "LISTO" else "CONTINUAR", {
            asistente = false
            if (soloPermisos) finish() else mostrar(5)
        }, if (soloPermisos) null else "ATRÁS", if (soloPermisos) null else ({ mostrar(3) }))
    }

    private fun concederTodo() {
        intentados.clear()
        asistente = true
        val faltan = permisosNormales().filter { !tiene(it) }
        if (faltan.isNotEmpty()) requestPermissions(faltan.toTypedArray(), 20) else siguienteEspecial()
    }

    private fun siguienteEspecial() {
        val e = especiales().firstOrNull { !it.ok() && it.id !in intentados }
        if (e == null) {
            asistente = false
            refrescarPermisos?.invoke()
            return
        }
        intentados.add(e.id)
        e.abrir()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refrescarPermisos?.invoke()
        if (requestCode == 20 && asistente) h.postDelayed({ siguienteEspecial() }, 400)
    }

    override fun onResume() {
        super.onResume()
        if (paso == 4) {
            refrescarPermisos?.invoke()
            if (asistente) h.postDelayed({ if (asistente) siguienteEspecial() }, 500)
        }
    }

    // ------------------------------------------------------------------ 5
    private fun pasoPalabra(): View = plantilla("Su palabra de activación", "Enséñeme cómo me llama. Dirá su palabra 5 veces para que aprenda a reconocer SU voz, incluso sin internet.", {
        val t = tarjeta()
        t.addView(texto("Palabra actual: «${Prefs.palabraActivacion}»", 18f, Ui.TEXTO, Ui.fuerte))
        t.agregar(texto(if (Prefs.muestras().isNotEmpty()) "✓ Ya entrenada" else "Toma menos de un minuto. Puede elegir Yarvis, Jarvis, Velkyra o la que prefiera.", 15f, if (Prefs.muestras().isNotEmpty()) Ui.VERDE else Ui.TEXTO_SUAVE), arriba = dp(6))
        addView(t)
        agregar(boton("ENTRENAR AHORA", true) {
            startActivityForResult(Intent(this@BienvenidaActivity, EntrenamientoActivity::class.java).putExtra(EntrenamientoActivity.EXTRA_DESDE_BIENVENIDA, true), 40)
        }, arriba = dp(18))
    }, "CONTINUAR", { mostrar(6) }, "ATRÁS", { mostrar(4) })

    @Deprecated("API de Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 40) mostrar(if (resultCode == RESULT_OK) 6 else 5)
    }

    // ------------------------------------------------------------------ 6
    private fun pasoListo(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), dp(22)) }
        col.agregar(View(this), alto = 0, peso = 1f)
        col.agregar(NucleoView(this).apply { estado = Yarvis.Estado.HABLANDO }, ancho = dp(200), alto = dp(200)).also { (it.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER }
        col.agregar(texto("Todo listo, ${Prefs.trato}.", 26f, Ui.TEXTO, Ui.titulo).apply { gravity = Gravity.CENTER }, arriba = dp(22))
        col.agregar(texto("Diga «${Prefs.palabraActivacion}» en cualquier momento, incluso con el celular bloqueado. También puede tocar el núcleo o escribirme.", 18f, Ui.TEXTO_SUAVE).apply { gravity = Gravity.CENTER }, arriba = dp(10))
        col.agregar(texto("Pruebe: «${Prefs.palabraActivacion}, ¿qué hora es?»  ·  «${Prefs.palabraActivacion}, ¿cómo está el clima?»", 16f, Ui.CIAN_CLARO).apply { gravity = Gravity.CENTER }, arriba = dp(14))
        col.agregar(View(this), alto = 0, peso = 1f)
        col.agregar(boton("INICIAR YARVIS", true) {
            Prefs.configurado = true
            Prefs.escuchaActiva = true
            ServicioEscucha.iniciar(this)
            startActivity(Intent(this, MainActivity::class.java).putExtra("saludar", true))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        })
        return col
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

/** TextWatcher simplificado. */
class Cambio(private val f: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) = f()
}
