package com.velkyra.yarvis

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Panel flotante que aparece sobre cualquier app cuando dice «Yarvis». */
object VentanaFlotante : Yarvis.Observador {

    private var vista: View? = null
    private var nucleo: NucleoView? = null
    private var texto: TextView? = null
    private var wm: WindowManager? = null

    fun mostrar(ctx: Context) {
        if (vista != null || !Settings.canDrawOverlays(ctx)) return
        val c = ctx.applicationContext
        val gestor = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.panel(c, 28f, 0xF0050E18.toInt(), 0x992DE2FF.toInt(), 1.5f)
            setPadding(c.dp(12), c.dp(10), c.dp(18), c.dp(10))
            elevation = c.dpf(12f)
        }
        val n = NucleoView(c).apply { compacto = true; estado = Yarvis.estado }
        panel.addView(n, LinearLayout.LayoutParams(c.dp(64), c.dp(64)))
        val columna = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(12), 0, 0, 0) }
        columna.addView(c.texto("YARVIS", 11f, Ui.CIAN, Ui.titulo, 0.3f))
        val tv = c.texto("Escuchando…", 16f, Ui.TEXTO, Ui.normal).apply {
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        columna.addView(tv)
        panel.addView(columna, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.setOnClickListener {
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        panel.setOnLongClickListener { Yarvis.cancelar(); true }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            x = 0
            y = c.dp(40)
            horizontalMargin = 0.03f
            windowAnimations = android.R.style.Animation_Toast
        }
        try {
            gestor.addView(panel, lp)
            vista = panel
            nucleo = n
            texto = tv
            wm = gestor
            Yarvis.observar(this)
        } catch (_: Exception) {
        }
    }

    fun ocultar() {
        val v = vista ?: return
        Yarvis.dejarDeObservar(this)
        try { wm?.removeView(v) } catch (_: Exception) {}
        vista = null
        nucleo = null
        texto = null
    }

    override fun alEstado(estado: Yarvis.Estado) { nucleo?.estado = estado }
    override fun alParcial(texto: String) { this.texto?.text = texto }
    override fun alNivel(nivel: Float) { nucleo?.nivel = nivel }
}
