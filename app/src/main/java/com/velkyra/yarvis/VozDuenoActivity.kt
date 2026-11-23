package com.velkyra.yarvis

import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Registro de voz: el dueño (o un invitado autorizado) lee 3 frases y dice la palabra de activación 3 veces.
 * Con eso YARVIS guarda su "huella de voz" y luego solo obedece a las voces registradas.
 */
class VozDuenoActivity : Base() {

    companion object {
        const val EXTRA_NOMBRE = "nombre"
    }

    private data class Paso(val texto: String, val ms: Int, val esPalabra: Boolean)

    private val h = Handler(Looper.getMainLooper())
    private lateinit var nucleo: NucleoView
    private lateinit var titulo: TextView
    private lateinit var frase: TextView
    private lateinit var estado: TextView
    private lateinit var lista: LinearLayout
    private lateinit var boton: TextView
    private lateinit var botonSec: TextView

    private var nombreInvitado: String? = null
    private val huellasFrases = mutableListOf<FloatArray>()
    private val huellasPalabra = mutableListOf<FloatArray>()
    private var indice = 0
    private var grabando = false
    private var terminado = false

    private val pasos by lazy {
        val p = Prefs.palabraActivacion
        listOf(
            Paso("Hoy es un buen día para aprender algo nuevo.", 4200, false),
            Paso("El cielo de los Andes brilla con miles de estrellas.", 4200, false),
            Paso("Me gusta escuchar música mientras trabajo en casa.", 4200, false),
            Paso(p, 2200, true),
            Paso(p, 2200, true),
            Paso("Oye $p", 2400, true)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nombreInvitado = intent.getStringExtra(EXTRA_NOMBRE)?.trim()?.ifBlank { null }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val quien = nombreInvitado
        c.addView(texto(
            if (quien == null) "Aprenderé a reconocer su voz para obedecerle solo a usted. Lea en voz alta cada frase cuando el núcleo se ilumine, con su tono normal."
            else "$quien: lea en voz alta cada frase cuando el núcleo se ilumine, con su tono normal. Así le reconoceré y podrá darme órdenes.",
            16f, Ui.TEXTO_SUAVE
        ))
        nucleo = NucleoView(this)
        c.agregar(nucleo, alto = dp(170), arriba = dp(14))
        titulo = texto("", 14f, Ui.CIAN, Ui.titulo, 0.15f).apply { gravity = Gravity.CENTER }
        c.agregar(titulo, arriba = dp(4))
        frase = texto("Toque «COMENZAR» cuando esté listo.", 22f, Ui.TEXTO, Ui.fuerte).apply { gravity = Gravity.CENTER }
        c.agregar(frase, arriba = dp(8))
        estado = texto("", 15f, Ui.CIAN_CLARO).apply { gravity = Gravity.CENTER }
        c.agregar(estado, arriba = dp(6))
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.agregar(lista, arriba = dp(10))
        boton = boton("COMENZAR", true) { principal() }
        c.agregar(boton, arriba = dp(18))
        botonSec = boton("CANCELAR", false) { finish() }
        c.agregar(botonSec, arriba = dp(10))
        montar(pantallaConTitulo(if (quien == null) "Reconocer mi voz" else "Voz de $quien", c))
        ServicioEscucha.pausar()
    }

    private fun principal() {
        if (terminado) { finish(); return }
        if (grabando) return
        if (!tiene(Manifest.permission.RECORD_AUDIO)) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41); return }
        if (!VozDueno.disponible(this)) {
            estado.text = "El reconocedor de voces no está incluido en esta versión de la app."
            estado.setTextColor(Ui.ROJO)
            return
        }
        huellasFrases.clear(); huellasPalabra.clear(); lista.removeAllViews()
        indice = 0
        boton.visibility = View.GONE
        siguiente()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (tiene(Manifest.permission.RECORD_AUDIO)) principal()
    }

    private fun siguiente() {
        if (indice >= pasos.size) { terminar(); return }
        val p = pasos[indice]
        titulo.text = "PASO ${indice + 1} DE ${pasos.size}"
        frase.text = if (p.esPalabra) "Diga: «${p.texto}»" else "«${p.texto}»"
        estado.text = "Prepárese…"
        nucleo.estado = Yarvis.Estado.PENSANDO
        h.postDelayed({ grabar(p) }, 900)
    }

    private fun grabar(p: Paso) {
        grabando = true
        estado.text = "Hable ahora…"
        nucleo.estado = Yarvis.Estado.ESCUCHANDO
        Thread {
            val pcm = try { VozDueno.grabar(p.ms) { n -> runOnUiThread { nucleo.nivel = n } } } catch (_: Exception) { ShortArray(0) }
            val huella = VozDueno.huella(this, pcm)
            runOnUiThread {
                grabando = false
                nucleo.nivel = 0f
                if (huella == null) {
                    estado.text = if (pcm.size < 8000) "No le escuché bien. Repitamos esta frase." else "No pude analizar la voz (${VozDueno.error ?: "error"}). Repitamos."
                    h.postDelayed({ siguiente() }, 1200)
                    return@runOnUiThread
                }
                if (p.esPalabra) huellasPalabra.add(huella) else huellasFrases.add(huella)
                lista.agregar(texto("✓  Paso ${indice + 1} grabado", 15f, Ui.VERDE, Ui.fuerte), arriba = dp(2))
                indice++
                h.postDelayed({ siguiente() }, 500)
            }
        }.start()
    }

    private fun terminar() {
        val todas = huellasFrases + huellasPalabra
        val perfil = VozDueno.promedio(todas)
        // Qué tan parecida es su voz consigo misma al decir la palabra: con eso se ajusta la exigencia
        val propias = huellasPalabra.map { VozDueno.similitud(it, perfil) }
        val minimo = propias.minOrNull() ?: 0.5f
        val quien = nombreInvitado
        VozDueno.guardar(quien ?: "dueño", quien == null, perfil)
        if (quien == null) {
            Prefs.umbralVoz = (minimo * 0.75f).coerceIn(0.22f, 0.42f)
            Prefs.verificarVoz = true
        }
        // Comprobar que no se confunde con otras voces registradas
        val otros = VozDueno.perfiles().filter { it.dueno != (quien == null) || (it.nombre != (quien ?: "dueño")) }
        val parecido = otros.maxOfOrNull { VozDueno.similitud(it.huella, perfil) } ?: 0f

        terminado = true
        nucleo.estado = Yarvis.Estado.HABLANDO
        titulo.text = "LISTO"
        frase.text = if (quien == null) "Voz aprendida. Desde ahora solo le obedeceré a usted." else "Voz de $quien aprendida."
        estado.text = "Coincidencia consigo mismo: ${(minimo * 100).toInt()}%" +
            (if (otros.isNotEmpty()) " · parecido con otras voces: ${(parecido * 100).toInt()}%" else "") +
            if (otros.isNotEmpty() && parecido > Prefs.umbralVoz) "\nOjo: se parece a otra voz registrada; podría confundirlas." else ""
        boton.text = "TERMINAR"
        boton.visibility = View.VISIBLE
        botonSec.text = "REPETIR"
        botonSec.setOnClickListener { terminado = false; principal() }
        Voz.hablar(if (quien == null) "Listo, ${Prefs.trato}. Ya reconozco su voz." else "Encantado de conocerle, $quien. Ya reconozco su voz.")
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        ServicioEscucha.reanudar()
        super.onDestroy()
    }
}
