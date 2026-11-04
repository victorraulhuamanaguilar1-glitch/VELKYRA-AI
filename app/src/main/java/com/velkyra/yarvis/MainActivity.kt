package com.velkyra.yarvis

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pantalla principal: núcleo holográfico, paneles de datos en vivo y conversación. */
class MainActivity : Base(), Yarvis.Observador {

    private val h = Handler(Looper.getMainLooper())
    private val es = Locale.forLanguageTag("es-PE")

    private lateinit var nucleo: NucleoView
    private lateinit var estadoTxt: TextView
    private lateinit var subtitulo: TextView
    private lateinit var conversacion: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var entrada: EditText
    private lateinit var botonAccion: ImageView
    private lateinit var horaTxt: TextView
    private lateinit var fechaTxt: TextView
    private lateinit var bateriaTxt: TextView
    private lateinit var climaTxt: TextView
    private lateinit var climaLugar: TextView
    private var ultimoClima = 0L

    private val tic = object : Runnable {
        override fun run() {
            refrescarDatos()
            h.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.configurado) {
            startActivity(Intent(this, BienvenidaActivity::class.java))
            finish()
            return
        }
        construir()
        for (m in Yarvis.historial) burbuja(m, animar = false)
        if (intent.getBooleanExtra("saludar", false)) h.postDelayed({ Yarvis.anunciar(Yarvis.cerebro.saludoInicial()) }, 900)
        manejar(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        manejar(intent)
    }

    private fun manejar(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("escuchar", false) || i.action == Intent.ACTION_ASSIST || i.action == Intent.ACTION_VOICE_COMMAND) {
            i.removeExtra("escuchar")
            h.postDelayed({ Yarvis.activar(false) }, 400)
        }
        i.getStringExtra("orden")?.let { o ->
            i.removeExtra("orden")
            h.postDelayed({ Yarvis.procesar(o) }, 300)
        }
    }

    private fun construir() {
        val capa = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(12)) }

        // Barra superior
        val barra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val marca = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        marca.addView(texto("YARVIS", 24f, Ui.CIAN, Ui.titulo, 0.35f))
        estadoTxt = texto("", 12f, Ui.VERDE, Ui.titulo, 0.12f)
        marca.addView(estadoTxt)
        barra.agregar(marca, ancho = 0, peso = 1f)
        barra.addView(icono(android.R.drawable.ic_menu_help) { startActivity(Intent(this, ComandosActivity::class.java)) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
        barra.addView(icono(android.R.drawable.ic_menu_preferences) { startActivity(Intent(this, AjustesActivity::class.java)) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        col.addView(barra)

        // Paneles de datos
        val datos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun panel(etq: String): Pair<LinearLayout, TextView> {
            val p = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.panel(this@MainActivity, 14f)
                setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            p.addView(texto(etq, 10f, Ui.TEXTO_SUAVE, Ui.titulo, 0.2f))
            val v = texto("--", 20f, Ui.TEXTO, Ui.titulo).apply { maxLines = 1 }
            p.addView(v)
            return p to v
        }
        val (pHora, vHora) = panel("HORA"); horaTxt = vHora
        fechaTxt = texto("", 12f, Ui.TEXTO_SUAVE, Ui.normal).apply { maxLines = 1 }; pHora.addView(fechaTxt)
        val (pBat, vBat) = panel("ENERGÍA"); bateriaTxt = vBat
        pBat.addView(texto("batería", 12f, Ui.TEXTO_SUAVE))
        val (pClima, vClima) = panel("CLIMA"); climaTxt = vClima
        climaLugar = texto("toque para ver", 12f, Ui.TEXTO_SUAVE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        pClima.addView(climaLugar)
        pClima.setOnClickListener { Yarvis.procesar("¿Cómo está el clima?") }
        pBat.setOnClickListener { Yarvis.procesar("¿Cuánta batería tengo?") }
        pHora.setOnClickListener { Yarvis.procesar("¿Qué tengo hoy en mi agenda?") }
        datos.agregar(pHora, ancho = 0, peso = 1.25f)
        datos.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        datos.agregar(pBat, ancho = 0, peso = 1f)
        datos.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        datos.agregar(pClima, ancho = 0, peso = 1.1f)
        col.agregar(datos, arriba = dp(12))

        // Núcleo
        nucleo = NucleoView(this).apply {
            setOnClickListener { if (Yarvis.estado == Yarvis.Estado.REPOSO) Yarvis.activar(false) else Yarvis.cancelar() }
            setOnLongClickListener { Yarvis.cancelar(); true }
            contentDescription = "Toque para hablar con YARVIS"
        }
        col.agregar(nucleo, alto = dp(230), arriba = dp(6))

        subtitulo = texto("Diga «${Prefs.palabraActivacion}» o toque el núcleo", 18f, Ui.CIAN_CLARO, Ui.fuerte).apply {
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(48)
        }
        col.agregar(subtitulo, arriba = dp(2))

        // Conversación
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        conversacion = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        scroll.addView(conversacion)
        col.agregar(scroll, alto = 0, peso = 1f, arriba = dp(6))

        // Sugerencias
        val sc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (s in listOf("¿Qué hora es?", "¿Cómo está el clima?", "Lee mis mensajes", "Mis recordatorios", "Enciende la linterna", "Cuéntame un chiste", "¿Qué puedes hacer?")) {
            chips.addView(chip(s) { Yarvis.procesar(s) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        sc.addView(chips)
        col.agregar(sc, arriba = dp(6))

        // Barra de entrada
        val barraEntrada = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        entrada = campo("Escriba una orden…").apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = Ui.panel(this@MainActivity, 26f, Ui.PANEL_FUERTE, Ui.BORDE)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { enviar(); true } else false }
        }
        barraEntrada.agregar(entrada, ancho = 0, peso = 1f)
        botonAccion = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Ui.FONDO)
            background = Ui.degradadoCian(this@MainActivity, 26f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { if (entrada.text.isNotBlank()) enviar() else Yarvis.activar(false) }
        }
        barraEntrada.addView(botonAccion, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginStart = dp(10) })
        entrada.addTextChangedListener(Cambio {
            botonAccion.setImageResource(if (entrada.text.isNotBlank()) android.R.drawable.ic_menu_send else android.R.drawable.ic_btn_speak_now)
        })
        col.agregar(barraEntrada, arriba = dp(8))

        capa.addView(col, FrameLayout.LayoutParams(-1, -1))

        // Marca de agua discreta
        val agua = texto("VELKYRA", 11f, Ui.TEXTO, Ui.titulo, 0.6f).apply { alpha = 0.13f }
        capa.addView(agua, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(388) })

        montar(capa)
    }

    private fun enviar() {
        val t = entrada.text.toString().trim()
        if (t.isEmpty()) return
        entrada.setText("")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(entrada.windowToken, 0)
        Yarvis.procesar(t)
    }

    private fun burbuja(m: Yarvis.Mensaje, animar: Boolean = true) {
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(11))
            background = if (m.deUsuario) Ui.panel(this@MainActivity, 16f, 0xFF0D2234.toInt(), 0x00000000, 0f)
            else Ui.panel(this@MainActivity, 16f, 0x1A2DE2FF, Ui.BORDE)
        }
        if (!m.deUsuario) caja.addView(texto("YARVIS", 10f, Ui.CIAN, Ui.titulo, 0.25f))
        caja.addView(texto(m.texto, 16f, if (m.deUsuario) Ui.TEXTO else Ui.CIAN_CLARO).apply { setTextIsSelectable(true) })
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8)
            gravity = if (m.deUsuario) Gravity.END else Gravity.START
            if (m.deUsuario) marginStart = dp(48) else marginEnd = dp(36)
        }
        conversacion.addView(caja, lp)
        while (conversacion.childCount > 60) conversacion.removeViewAt(0)
        if (animar) { caja.alpha = 0f; caja.translationY = dpf(10f); caja.animate().alpha(1f).translationY(0f).setDuration(250).start() }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refrescarDatos() {
        horaTxt.text = SimpleDateFormat("h:mm a", es).format(Date()).replace(" ", "").replace(".", "").uppercase()
        fechaTxt.text = SimpleDateFormat("EEE d MMM", es).format(Date())
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val n = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        bateriaTxt.text = "$n%"
        bateriaTxt.setTextColor(if (n <= 15 && !bm.isCharging) Ui.ROJO else if (bm.isCharging) Ui.VERDE else Ui.TEXTO)
        val escuchando = ServicioEscucha.escuchandoPalabra
        estadoTxt.text = when {
            escuchando -> "●  EN LÍNEA · DIGA «${Prefs.palabraActivacion.uppercase()}»"
            !Prefs.escuchaActiva -> "●  ESCUCHA EN PAUSA"
            Yarvis.estado != Yarvis.Estado.REPOSO -> "●  ATENDIENDO"
            ServicioEscucha.activo -> "●  PREPARANDO ESCUCHA…"
            else -> "●  TOQUE EL NÚCLEO PARA HABLAR"
        }
        estadoTxt.setTextColor(if (escuchando || Yarvis.estado != Yarvis.Estado.REPOSO) Ui.VERDE else Ui.AMBAR)
        if (System.currentTimeMillis() - ultimoClima > 30 * 60_000) {
            ultimoClima = System.currentTimeMillis()
            Thread {
                val c = Clima.actual(this)
                runOnUiThread {
                    if (c != null) { climaTxt.text = "${c.temp}°"; climaLugar.text = c.lugar } else ultimoClima = 0L
                }
            }.start()
        }
    }

    // --- Observador -------------------------------------------------------
    override fun alEstado(estado: Yarvis.Estado) {
        nucleo.estado = estado
        if (estado == Yarvis.Estado.REPOSO) h.postDelayed({ if (Yarvis.estado == Yarvis.Estado.REPOSO) subtitulo.text = "Diga «${Prefs.palabraActivacion}» o toque el núcleo" }, 4000)
        refrescarDatos()
    }

    override fun alMensaje(m: Yarvis.Mensaje) = burbuja(m)
    override fun alParcial(texto: String) { subtitulo.text = texto }
    override fun alNivel(nivel: Float) { nucleo.nivel = nivel }

    override fun onResume() {
        super.onResume()
        Yarvis.pantallaVisible = true
        VentanaFlotante.ocultar()
        Yarvis.observar(this)
        nucleo.estado = Yarvis.estado
        if (Prefs.escuchaActiva && !ServicioEscucha.activo) ServicioEscucha.iniciar(this)
        h.post(tic)
    }

    override fun onPause() {
        Yarvis.pantallaVisible = false
        Yarvis.dejarDeObservar(this)
        h.removeCallbacks(tic)
        super.onPause()
    }
}
