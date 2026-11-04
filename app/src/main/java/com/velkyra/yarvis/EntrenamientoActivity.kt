package com.velkyra.yarvis

import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Entrenamiento de la palabra de activación: el usuario la dice 5 veces y YARVIS aprende
 * cómo la escucha el reconocedor sin internet con SU voz. Luego se verifica.
 */
class EntrenamientoActivity : Base(), RecognitionListener {

    companion object {
        const val EXTRA_DESDE_BIENVENIDA = "desde_bienvenida"
        private const val MUESTRAS = 5
    }

    private enum class Fase { INICIO, PRUEBA, GRABANDO, VERIFICAR, NEGATIVA, LISTO }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var campoPalabra: EditText
    private lateinit var nucleo: NucleoView
    private lateinit var instruccion: TextView
    private lateinit var oido: TextView
    private lateinit var lista: LinearLayout
    private lateinit var botonPrincipal: TextView
    private lateinit var botonSecundario: TextView

    private var fase = Fase.INICIO
    private var modelo: Model? = null
    private var reconocedor: Recognizer? = null
    private var servicio: SpeechService? = null
    private val muestras = mutableListOf<String>()
    private var aceptando = false
    private var intentosPrueba = 0
    private var aciertosPrueba = 0
    private var intentosVacios = 0
    private var desdeBienvenida = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        desdeBienvenida = intent.getBooleanExtra(EXTRA_DESDE_BIENVENIDA, false)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        c.addView(texto("Enséñeme a reconocer su voz. Elija la palabra con la que me llamará y dígala $MUESTRAS veces, como lo haría normalmente.", 16f, Ui.TEXTO_SUAVE))
        c.agregar(etiqueta("Su palabra de activación"), arriba = dp(18))
        campoPalabra = campo("Yarvis", Prefs.palabraActivacion)
        c.agregar(campoPalabra, arriba = dp(8))
        val sc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (s in listOf("Yarvis", "Jarvis", "Oye Yarvis", "Velkyra", "Asistente", "Computadora")) {
            chips.addView(chip(s) { campoPalabra.setText(s) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        sc.addView(chips)
        c.agregar(sc, arriba = dp(10))
        c.agregar(texto("Consejo: las palabras de dos o tres sílabas, poco comunes, funcionan mejor y se activan menos por error.", 13f, Ui.TEXTO_SUAVE), arriba = dp(8))

        nucleo = NucleoView(this)
        c.agregar(nucleo, alto = dp(170), arriba = dp(14))
        instruccion = texto("Toque «COMENZAR» cuando esté listo.", 20f, Ui.TEXTO, Ui.fuerte).apply { gravity = Gravity.CENTER }
        c.agregar(instruccion, arriba = dp(6))
        oido = texto("", 17f, Ui.CIAN_CLARO).apply { gravity = Gravity.CENTER }
        c.agregar(oido, arriba = dp(6))
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.agregar(lista, arriba = dp(12))

        botonPrincipal = boton("COMENZAR", true) { principal() }
        c.agregar(botonPrincipal, arriba = dp(18))
        botonSecundario = boton(if (desdeBienvenida) "OMITIR POR AHORA" else "CANCELAR", false) { terminar(false) }
        c.agregar(botonSecundario, arriba = dp(10))

        montar(pantallaConTitulo("Entrenar mi palabra", c))
        ServicioEscucha.pausar()
    }

    private fun principal() {
        when (fase) {
            Fase.INICIO -> comenzar()
            Fase.LISTO -> terminar(true)
            else -> {}
        }
    }

    private fun comenzar() {
        if (!tiene(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 31)
            return
        }
        if (campoPalabra.text.isBlank()) campoPalabra.setText("Yarvis")
        muestras.clear()
        lista.removeAllViews()
        botonPrincipal.visibility = View.GONE
        instruccion.text = "Preparando el reconocedor de voz…"
        nucleo.estado = Yarvis.Estado.PENSANDO
        MotorVoz.obtener(this) { m ->
            if (m == null) {
                instruccion.text = MotorVoz.error ?: "No se pudo cargar el reconocedor de voz."
                instruccion.setTextColor(Ui.ROJO)
                nucleo.estado = Yarvis.Estado.REPOSO
                return@obtener
            }
            modelo = m
            val p = Frase.normalizar(palabra())
            if (p.contains("yarvis") || p.contains("jarvis")) {
                // Primero se prueba el método que casi no tiene falsas activaciones
                fase = Fase.PRUEBA
                intentosPrueba = 0
                aciertosPrueba = 0
                siguientePrueba()
            } else {
                fase = Fase.GRABANDO
                siguienteMuestra()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (tiene(Manifest.permission.RECORD_AUDIO)) comenzar()
        else instruccion.text = "Necesito permiso para usar el micrófono."
    }

    private fun palabra() = campoPalabra.text.toString().trim().ifBlank { "Yarvis" }

    private fun siguientePrueba() {
        intentosPrueba++
        instruccion.text = "Diga «${palabra()}»   ($intentosPrueba de $MUESTRAS)"
        oido.text = "Le escucho…"
        escuchar(ServicioEscucha.gramaticaPorDefecto())
    }

    private fun resultadoPrueba(acierto: Boolean, texto: String) {
        h.removeCallbacksAndMessages(null)
        if (acierto) aciertosPrueba++
        agregarFila(if (acierto) "Le reconocí ✓" else "No le reconocí" + if (texto.isNotBlank()) " (entendí «$texto»)" else "", acierto)
        when {
            aciertosPrueba >= 3 -> {
                // ¡Funciona con su voz! Se usa el método por defecto, sin muestras
                Prefs.palabraActivacion = palabra()
                Prefs.muestrasPalabra = "[]"
                fase = Fase.NEGATIVA
                instruccion.text = "¡Le reconozco muy bien! Ahora diga una frase normal, SIN la palabra.\nPor ejemplo: «hoy hace buen día»."
                oido.text = "Le escucho…"
                h.postDelayed({ escuchar(ServicioEscucha.gramatica()) }, 500)
            }
            intentosPrueba - aciertosPrueba > MUESTRAS - 3 -> {
                // No alcanza: se aprende cómo le escucha a usted
                lista.agregar(texto("Voy a aprender cómo pronuncia su palabra.", 16f, Ui.CIAN_CLARO, Ui.fuerte), arriba = dp(8))
                fase = Fase.GRABANDO
                h.postDelayed({ siguienteMuestra() }, 800)
            }
            else -> h.postDelayed({ siguientePrueba() }, 600)
        }
    }

    private fun siguienteMuestra() {
        val n = muestras.size + 1
        instruccion.text = "Diga «${palabra()}»   ($n de $MUESTRAS)"
        oido.text = "Le escucho…"
        escuchar(null)
    }

    /** Inicia el reconocedor. [gramatica] null = modo libre (para aprender). */
    private fun escuchar(gramatica: String?) {
        detener()
        val m = modelo ?: return
        try {
            val rec = if (gramatica == null) Recognizer(m, 16000f) else Recognizer(m, 16000f, gramatica)
            val s = SpeechService(rec, 16000f)
            s.startListening(this)
            reconocedor = rec
            servicio = s
            aceptando = true
            nucleo.estado = Yarvis.Estado.ESCUCHANDO
            nucleo.nivel = 6f
            h.removeCallbacksAndMessages(null)
            h.postDelayed({ sinRespuesta() }, 7000)
        } catch (e: Exception) {
            instruccion.text = "No pude usar el micrófono. ¿Otra app lo está usando?"
            instruccion.setTextColor(Ui.ROJO)
        }
    }

    private fun detener() {
        aceptando = false
        servicio?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.shutdown() } catch (_: Exception) {}
        }
        servicio = null
        reconocedor?.let { try { it.close() } catch (_: Exception) {} }
        reconocedor = null
        nucleo.nivel = 0f
    }

    private fun sinRespuesta() {
        when (fase) {
            Fase.PRUEBA -> resultadoPrueba(false, "")
            Fase.GRABANDO -> {
                intentosVacios++
                oido.text = if (intentosVacios >= 3) "No le escucho. Revise que el micrófono no esté tapado y hable un poco más fuerte." else "No le escuché. Inténtelo de nuevo."
                siguienteMuestra()
            }
            Fase.VERIFICAR -> {
                oido.text = "No le reconocí. Diga «${palabra()}» de nuevo, con claridad."
                escuchar(ServicioEscucha.gramatica())
            }
            Fase.NEGATIVA -> {
                // Si no se activó con la frase normal, ¡perfecto!
                finalizarPrueba(true)
            }
            else -> {}
        }
    }

    private fun textoDe(json: String?): String = try {
        val o = JSONObject(json ?: "")
        o.optString("text").ifBlank { o.optString("partial") }
    } catch (_: Exception) { "" }

    private fun resultado(json: String?) {
        if (!aceptando) return
        val t = textoDe(json).replace("[unk]", "").trim()
        when (fase) {
            Fase.PRUEBA -> {
                if (t.isBlank()) return
                detener()
                resultadoPrueba(ServicioEscucha.esJarvis(t), t)
            }
            Fase.GRABANDO -> {
                if (t.isBlank()) return
                h.removeCallbacksAndMessages(null)
                detener()
                intentosVacios = 0
                muestras.add(t)
                agregarFila("Entendí: «$t»", true)
                if (muestras.size >= MUESTRAS) iniciarVerificacion() else h.postDelayed({ siguienteMuestra() }, 600)
            }
            Fase.VERIFICAR -> {
                if (t.isBlank()) return
                val ok = ServicioEscucha.contienePalabra(t)
                h.removeCallbacksAndMessages(null)
                if (ok) {
                    agregarFila("Prueba: le reconocí perfectamente ✓", true)
                    fase = Fase.NEGATIVA
                    instruccion.text = "Ahora diga una frase normal, SIN la palabra.\nPor ejemplo: «hoy hace buen día»."
                    oido.text = "Le escucho…"
                    escuchar(ServicioEscucha.gramatica())
                } else {
                    oido.text = "Entendí «$t». Intente de nuevo."
                    h.postDelayed({ escuchar(ServicioEscucha.gramatica()) }, 500)
                }
            }
            Fase.NEGATIVA -> {
                if (t.isBlank()) return
                if (ServicioEscucha.contienePalabra(t)) {
                    h.removeCallbacksAndMessages(null)
                    finalizarPrueba(false)
                }
            }
            else -> {}
        }
    }

    private fun iniciarVerificacion() {
        // Guarda lo aprendido para que la verificación use exactamente lo mismo que el servicio
        val distintas = muestras.map { it.lowercase() }.distinct()
        Prefs.palabraActivacion = palabra()
        Prefs.muestrasPalabra = JSONArray(distintas).toString()
        fase = Fase.VERIFICAR
        instruccion.text = "¡Aprendido! Ahora la prueba final: diga «${palabra()}» una vez más."
        oido.text = "Le escucho…"
        escuchar(ServicioEscucha.gramatica())
    }

    private fun finalizarPrueba(sinFalsos: Boolean) {
        detener()
        fase = Fase.LISTO
        nucleo.estado = Yarvis.Estado.HABLANDO
        if (sinFalsos) {
            agregarFila("No me activé con una frase normal ✓", true)
            instruccion.text = "Todo listo. Diga «${palabra()}» cuando me necesite."
        } else {
            agregarFila("Me activé con una frase normal ✗", false)
            instruccion.text = "Funciona, pero podría activarse por error. Le sugiero una palabra más larga o «Oye ${palabra()}»."
        }
        oido.text = estadoServicio()
        botonPrincipal.text = "GUARDAR Y TERMINAR"
        botonPrincipal.visibility = View.VISIBLE
        botonSecundario.text = "REPETIR ENTRENAMIENTO"
        botonSecundario.setOnClickListener { fase = Fase.INICIO; comenzar() }
        Voz.hablar("Entrenamiento completado, ${Prefs.trato}. Ya reconozco su voz.")
    }

    private fun estadoServicio(): String = when {
        !Prefs.escuchaActiva -> "Ojo: la escucha en segundo plano está apagada. Actívela en Ajustes."
        !desdeBienvenida && !ServicioEscucha.activo -> "Ojo: la escucha en segundo plano no está funcionando. Revise los permisos y la batería en Ajustes."
        else -> ""
    }

    private fun agregarFila(t: String, ok: Boolean) {
        lista.agregar(texto(t, 16f, if (ok) Ui.VERDE else Ui.AMBAR, Ui.fuerte), arriba = dp(4))
    }

    private fun terminar(guardar: Boolean) {
        h.removeCallbacksAndMessages(null)
        detener()
        if (guardar) Prefs.palabraActivacion = palabra()
        ServicioEscucha.reanudar()
        ServicioEscucha.reiniciar()
        setResult(if (guardar) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    // --- Vosk ---------------------------------------------------------------
    override fun onPartialResult(hypothesis: String?) {
        if (!aceptando) return
        val t = textoDe(hypothesis).replace("[unk]", "…")
        if (t.isNotBlank() && fase != Fase.LISTO) oido.text = "«$t»"
        if (fase == Fase.PRUEBA && ServicioEscucha.esJarvis(t)) {
            detener()
            resultadoPrueba(true, t)
            return
        }
        if (fase == Fase.NEGATIVA && ServicioEscucha.contienePalabra(t)) {
            h.removeCallbacksAndMessages(null)
            finalizarPrueba(false)
        }
    }

    override fun onResult(hypothesis: String?) = resultado(hypothesis)
    override fun onFinalResult(hypothesis: String?) = resultado(hypothesis)
    override fun onError(exception: Exception?) {
        instruccion.text = "Error del micrófono. Cierre otras apps que lo usen e inténtelo de nuevo."
    }
    override fun onTimeout() {}

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        detener()
        if (fase != Fase.LISTO) { ServicioEscucha.reanudar(); ServicioEscucha.reiniciar() }
        super.onDestroy()
    }
}
