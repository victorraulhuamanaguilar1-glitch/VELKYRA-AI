package com.velkyra.yarvis

import android.app.Activity
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.LinearLayout

/**
 * Selector de voz compartido (bienvenida y ajustes):
 * voces de Google del celular (sin límite) + voces premium de Gemini (muy humanas, con internet).
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

        // --- Voces premium (Gemini) ---
        caja.agregar(etiqueta("Voces premium · las más humanas"), arriba = dp(16))
        caja.agregar(texto("Necesitan internet y tienen un cupo gratuito diario. Si se agota, sigo hablando con la voz del celular.", 13f, Ui.TEXTO_SUAVE), arriba = dp(4))
        if (Prefs.claveIA.isBlank()) {
            caja.agregar(texto("Requieren la clave de Google Gemini (Ajustes → Inteligencia artificial).", 14f, Ui.AMBAR), arriba = dp(6))
        }
        val agotada = System.currentTimeMillis() < VozPremium.agotadaHasta
        if (agotada) caja.agregar(texto("○ Cupo de hoy agotado: mientras tanto uso la voz del celular.", 14f, Ui.AMBAR, Ui.fuerte), arriba = dp(6))

        for ((titulo, hombres) in listOf("Hombres" to true, "Mujeres" to false)) {
            caja.agregar(texto(titulo, 14f, Ui.CIAN_CLARO, Ui.fuerte), arriba = dp(10))
            for (v in VozPremium.VOCES.filter { it.hombre == hombres }) {
                val sel = Prefs.vozPremium == v.id
                caja.agregar(fila(v.nombre, v.estilo, sel) {
                    Prefs.vozPremium = v.id
                    Voz.probarPremium(v.id, muestra())
                    dibujar()
                }, arriba = dp(6))
            }
        }
        if (Prefs.vozPremium.isNotBlank()) {
            caja.agregar(texto("Usar la voz premium para:", 14f, Ui.TEXTO_SUAVE), arriba = dp(12))
            val modos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            modos.addView(chip("Todas las respuestas", Prefs.premiumModo == "todo") { Prefs.premiumModo = "todo"; dibujar() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            modos.addView(chip("Solo cuentos y largas", Prefs.premiumModo == "largas") { Prefs.premiumModo = "largas"; dibujar() })
            caja.agregar(modos, arriba = dp(6))
            caja.agregar(boton("APAGAR VOZ PREMIUM", false) { Prefs.vozPremium = ""; Voz.detener(); dibujar() }, arriba = dp(10))
        }

        // --- Voces del celular (Google) ---
        caja.agregar(etiqueta("Voces del celular · sin límite"), arriba = dp(22))
        val google = Voz.opciones()
        val actualGoogle = Prefs.voz.ifBlank { Voz.vozActual() }
        if (google.isEmpty()) {
            caja.agregar(texto("Cargando voces del celular…", 15f, Ui.TEXTO_SUAVE), arriba = dp(6))
            caja.postDelayed({ dibujar() }, 1500)
        }
        for (o in google.take(20)) {
            val sel = Prefs.vozPremium.isBlank() && o.voz.name == actualGoogle
            val detalle = (if (o.voz.isNetworkConnectionRequired) "Necesita internet" else "Funciona sin internet") +
                if (o.masculina) " · masculina" else ""
            caja.agregar(fila(o.etiqueta.substringBefore(" · en línea"), detalle, sel) {
                Voz.detener()
                Prefs.vozPremium = ""
                Voz.usarVozGoogle(o.voz)
                Voz.hablar(muestra())
                dibujar()
            }, arriba = dp(8))
        }
        caja.agregar(boton("DESCARGAR MÁS VOCES DEL CELULAR", false) {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (_: Exception) {}
            }
        }, arriba = dp(10))
        caja.agregar(texto("En «Motor preferido: Google» toque ⚙ → «Instalar datos de voz» → Español, y descargue España, Estados Unidos y México. Así tendrá voces de hombre y de mujer.", 13f, Ui.TEXTO_SUAVE), arriba = dp(6))

        // --- Ajustes finos (voz del celular) ---
        caja.agregar(etiqueta("Tono de la voz del celular (grave ← → agudo)"), arriba = dp(22))
        caja.agregar(deslizador(60, ((Prefs.tono - 0.7f) * 100).toInt()) { Prefs.tono = 0.7f + it / 100f; Voz.aplicarVoz() }, arriba = dp(6))
        caja.agregar(etiqueta("Velocidad de la voz del celular"), arriba = dp(12))
        caja.agregar(deslizador(60, ((Prefs.velocidad - 0.7f) * 100).toInt()) { Prefs.velocidad = 0.7f + it / 100f; Voz.aplicarVoz() }, arriba = dp(6))
        caja.agregar(boton("ESCUCHAR", false) { Voz.hablar(muestra()) }, arriba = dp(14))
    }
    dibujar()
    return caja
}
