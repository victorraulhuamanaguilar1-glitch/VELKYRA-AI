package com.velkyra.yarvis

import android.app.Activity
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Selector de voz compartido (bienvenida y ajustes):
 * voces de Google del celular + voces neuronales propias (sin internet), con tono y velocidad.
 */
fun Activity.selectorVoz(): LinearLayout {
    val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val muestra = { "Buenas noches, ${Prefs.trato}. Todos los sistemas en línea. Había una vez, en un pequeño pueblo de los Andes, un niño que soñaba con tocar las estrellas." }

    fun fila(titulo: String, detalle: String, sel: Boolean, alTocar: () -> Unit): LinearLayout {
        val f = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.panel(this@selectorVoz, 14f, if (sel) 0x302DE2FF else Ui.PANEL, if (sel) Ui.CIAN else Ui.BORDE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tx.addView(texto(titulo, 17f, if (sel) Ui.CIAN_CLARO else Ui.TEXTO, Ui.fuerte))
        tx.addView(texto(detalle, 13f, Ui.TEXTO_SUAVE))
        f.agregar(tx, ancho = 0, peso = 1f)
        f.addView(texto(if (sel) "✓" else "▶", 20f, Ui.CIAN, Ui.fuerte))
        f.setOnClickListener { alTocar() }
        return f
    }

    fun dibujar() {
        caja.removeAllViews()
        caja.addView(texto("Toque una voz para escucharla y elegirla.", 15f, Ui.TEXTO_SUAVE))

        // --- Voces de Google del celular ---
        caja.agregar(etiqueta("Voces del celular (Google)"), arriba = dp(16))
        val google = Voz.opciones()
        val actualGoogle = Prefs.voz.ifBlank { Voz.vozActual() }
        if (google.isEmpty()) {
            caja.agregar(texto("Cargando voces del celular…", 15f, Ui.TEXTO_SUAVE), arriba = dp(6))
            caja.postDelayed({ dibujar() }, 1500)
        }
        for (o in google.take(16)) {
            val sel = !Prefs.usarVozNeural && o.voz.name == actualGoogle
            val detalle = (if (o.voz.isNetworkConnectionRequired) "Necesita internet" else "Funciona sin internet") +
                if (o.masculina) " · recomendada para YARVIS" else ""
            caja.agregar(fila(o.etiqueta.substringBefore(" · en línea"), detalle, sel) {
                Voz.detener()
                Voz.usarVozGoogle(o.voz)
                Voz.hablar(muestra())
                dibujar()
            }, arriba = dp(8))
        }
        caja.agregar(boton("DESCARGAR MÁS VOCES DE GOOGLE", false) {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (_: Exception) {}
            }
        }, arriba = dp(10))
        caja.agregar(texto("En «Motor de Google» → ⚙ → «Instalar datos de voz» → Español, puede descargar más voces (España, Estados Unidos, México…).", 13f, Ui.TEXTO_SUAVE), arriba = dp(6))

        // --- Voces neuronales propias ---
        caja.agregar(etiqueta("Voces neuronales de YARVIS (sin internet)"), arriba = dp(22))
        for (n in VozNeural.NARRADORES) {
            val sel = Prefs.usarVozNeural && Prefs.vozNeural == n.id
            caja.agregar(fila(n.nombre, n.descripcion, sel) {
                Voz.detener()
                Voz.cambiarNarrador(n.id) { ok ->
                    dibujar()
                    if (ok) Voz.hablar(muestra())
                }
                dibujar()
            }, arriba = dp(8))
        }
        if (Prefs.usarVozNeural && !VozNeural.lista) {
            val msg: TextView = texto(
                VozNeural.error?.let { "No pude cargar la voz neuronal ($it). Mientras tanto uso la voz del celular." }
                    ?: "Preparando la voz neuronal… (solo tarda la primera vez)",
                14f, if (VozNeural.error != null) Ui.AMBAR else Ui.TEXTO_SUAVE
            )
            caja.agregar(msg, arriba = dp(8))
            if (VozNeural.error == null) VozNeural.preparar(this) { runOnUiThread { dibujar() } }
        }

        // --- Ajustes finos ---
        caja.agregar(etiqueta("Tono (más grave ← → más agudo)"), arriba = dp(22))
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
