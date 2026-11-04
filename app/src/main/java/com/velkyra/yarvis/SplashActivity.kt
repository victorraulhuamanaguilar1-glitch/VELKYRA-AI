package com.velkyra.yarvis

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Pantalla de arranque: «VELKYRA presenta · YARVIS». */
class SplashActivity : Base() {

    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val capa = FrameLayout(this)

        val marca = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val velkyra = texto("VELKYRA", 30f, Ui.TEXTO, Ui.titulo, 0.55f).apply { alpha = 0f; gravity = Gravity.CENTER }
        val presenta = texto("PRESENTA", 12f, Ui.TEXTO_SUAVE, Ui.titulo, 0.6f).apply { alpha = 0f; gravity = Gravity.CENTER }
        marca.addView(velkyra)
        marca.addView(presenta)
        capa.addView(marca, FrameLayout.LayoutParams(-1, -1))

        val principal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = 0f
        }
        val nucleo = NucleoView(this)
        principal.addView(nucleo, LinearLayout.LayoutParams(dp(220), dp(220)))
        principal.addView(texto("YARVIS", 40f, Ui.CIAN, Ui.titulo, 0.45f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        principal.addView(texto("ASISTENTE VIRTUAL INTELIGENTE", 12f, Ui.TEXTO_SUAVE, Ui.titulo, 0.3f).apply { gravity = Gravity.CENTER })
        capa.addView(principal, FrameLayout.LayoutParams(-1, -1))

        val pie = texto("Desarrollado por VELKYRA", 12f, Ui.TEXTO_SUAVE, Ui.normal, 0.15f).apply { gravity = Gravity.CENTER; alpha = 0.6f }
        capa.addView(pie, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(28) })

        montar(capa)

        val suave = DecelerateInterpolator()
        velkyra.scaleX = 1.25f; velkyra.scaleY = 1.25f
        velkyra.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(suave).start()
        presenta.animate().alpha(1f).setStartDelay(500).setDuration(600).start()
        h.postDelayed({
            marca.animate().alpha(0f).setDuration(400).start()
            principal.scaleX = 0.85f; principal.scaleY = 0.85f
            principal.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(250).setDuration(700).setInterpolator(suave).start()
        }, 1500)
        h.postDelayed({ continuar() }, 3400)
        capa.setOnClickListener { continuar() }
    }

    private var listo = false

    private fun continuar() {
        if (listo) return
        listo = true
        h.removeCallbacksAndMessages(null)
        val destino = if (Prefs.configurado) MainActivity::class.java else BienvenidaActivity::class.java
        startActivity(Intent(this, destino))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Suppress("unused")
    private fun ocultar(v: View) { v.visibility = View.GONE }
}
