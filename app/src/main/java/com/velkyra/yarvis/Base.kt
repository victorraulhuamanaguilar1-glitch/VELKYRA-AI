package com.velkyra.yarvis

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Base de todas las pantallas: fondo holográfico y utilidades. */
abstract class Base : Activity() {

    protected lateinit var raiz: FrameLayout

    protected fun montar(contenido: View, conFondo: Boolean = true) {
        raiz = FrameLayout(this).apply { fitsSystemWindows = true }
        if (conFondo) raiz.addView(FondoHud(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        raiz.addView(contenido, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(raiz)
    }

    /** Pantalla con barra superior (botón atrás + título) y contenido desplazable. */
    protected fun pantallaConTitulo(titulo: String, cuerpo: LinearLayout): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        val atras = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setColorFilter(Ui.CIAN)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { finish() }
        }
        barra.addView(atras, LinearLayout.LayoutParams(dp(48), dp(48)))
        barra.addView(texto(titulo.uppercase(), 18f, Ui.CIAN, Ui.titulo, 0.2f))
        col.addView(barra)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        cuerpo.setPadding(dp(16), dp(4), dp(16), dp(32))
        scroll.addView(cuerpo)
        col.agregar(scroll, alto = 0, peso = 1f)
        return col
    }

    protected fun icono(res: Int, alTocar: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Ui.CIAN_CLARO)
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = Ui.panel(this@Base, 24f, 0x14FFFFFF, Ui.BORDE)
        setOnClickListener { alTocar() }
    }
}
