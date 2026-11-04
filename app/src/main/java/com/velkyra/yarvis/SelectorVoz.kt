package com.velkyra.yarvis

import android.app.Activity
import android.view.Gravity
import android.widget.LinearLayout

/** Selector de voz compartido (bienvenida y ajustes): narradores neuronales + tono y velocidad. */
fun Activity.selectorVoz(): LinearLayout {
    val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val muestra = { "Buenas noches, ${Prefs.trato}. Todos los sistemas en línea. Había una vez, en un pequeño pueblo de los Andes, un niño que soñaba con tocar las estrellas." }

    fun dibujar() {
        caja.removeAllViews()
        for (n in VozNeural.NARRADORES) {
            val sel = Prefs.usarVozNeural && Prefs.vozNeural == n.id
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.panel(this@selectorVoz, 14f, if (sel) 0x302DE2FF else Ui.PANEL, if (sel) Ui.CIAN else Ui.BORDE)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            tx.addView(texto(n.nombre, 18f, if (sel) Ui.CIAN_CLARO else Ui.TEXTO, Ui.fuerte))
            tx.addView(texto(n.descripcion + " · sin internet", 13f, Ui.TEXTO_SUAVE))
            fila.agregar(tx, ancho = 0, peso = 1f)
            fila.addView(texto(if (sel) "✓" else "▶", 20f, Ui.CIAN, Ui.fuerte))
            fila.setOnClickListener {
                Voz.detener()
                Voz.cambiarNarrador(n.id) { ok ->
                    dibujar()
                    if (ok) Voz.hablar(muestra())
                }
                dibujar()
            }
            caja.agregar(fila, arriba = dp(8))
        }
        if (Prefs.usarVozNeural && !VozNeural.lista) {
            val msg = VozNeural.error?.let { "No pude cargar la voz neuronal ($it). Uso la voz del celular mientras tanto." }
                ?: "Preparando la voz neuronal… (solo tarda la primera vez)"
            caja.agregar(texto(msg, 14f, if (VozNeural.error != null) Ui.AMBAR else Ui.TEXTO_SUAVE), arriba = dp(8))
            if (VozNeural.error == null) VozNeural.preparar(this) { runOnUiThread { dibujar() } }
        }

        caja.agregar(etiqueta("Tono (más grave ← → más agudo)"), arriba = dp(18))
        val neural = Prefs.usarVozNeural
        caja.agregar(deslizador(60, (((if (neural) Prefs.tonoNeural else Prefs.tono) - 0.7f) * 100).toInt()) {
            if (neural) Prefs.tonoNeural = 0.7f + it / 100f else { Prefs.tono = 0.7f + it / 100f; Voz.aplicarVoz() }
        }, arriba = dp(6))
        caja.agregar(etiqueta("Velocidad"), arriba = dp(12))
        caja.agregar(deslizador(60, ((Prefs.velocidad - 0.7f) * 100).toInt()) { Prefs.velocidad = 0.7f + it / 100f; Voz.aplicarVoz() }, arriba = dp(6))
        caja.agregar(boton("ESCUCHAR", false) { Voz.hablar(muestra()) }, arriba = dp(14))
    }
    dibujar()
    return caja
}
