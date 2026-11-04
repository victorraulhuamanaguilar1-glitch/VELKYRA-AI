package com.velkyra.asistente

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var hud: HudView
    private lateinit var estado: TextView
    private lateinit var registro: TextView
    private lateinit var scroll: ScrollView
    private lateinit var entrada: EditText
    private lateinit var prefs: SharedPreferences
    private lateinit var cerebro: Cerebro

    private var tts: TextToSpeech? = null
    private var ttsListo = false
    private var reconocedor: SpeechRecognizer? = null
    private var escuchando = false
    private val principal = Handler(Looper.getMainLooper())

    private val colorAsistente = Color.rgb(34, 211, 238)
    private val colorUsuario = Color.rgb(168, 85, 247)
    private val textoReposo = "Toca el núcleo para hablar"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("velkyra", MODE_PRIVATE)
        cerebro = Cerebro(this, prefs)
        cerebro.alCerrar = { principal.postDelayed({ finish() }, 3500) }

        hud = findViewById(R.id.hud)
        estado = findViewById(R.id.estado)
        registro = findViewById(R.id.registro)
        scroll = findViewById(R.id.scroll)
        entrada = findViewById(R.id.entrada)

        hud.setOnClickListener { if (escuchando) detenerEscucha() else escuchar() }
        findViewById<ImageButton>(R.id.btnEnviar).setOnClickListener { enviarTexto() }
        findViewById<ImageButton>(R.id.btnAjustes).setOnClickListener { mostrarAjustes() }
        entrada.setOnEditorActionListener { _, accion, _ ->
            if (accion == EditorInfo.IME_ACTION_SEND) {
                enviarTexto()
                true
            } else {
                false
            }
        }

        tts = TextToSpeech(this, this)
        pedirPermisos()
    }

    // ------------------------------------------------------------------
    //  Voz que habla (Texto a voz)
    // ------------------------------------------------------------------

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            estado.text = "No encontré el motor de voz del celular"
            return
        }
        val motor = tts ?: return
        val r = motor.setLanguage(Locale.forLanguageTag("es-PE"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            motor.setLanguage(Locale.forLanguageTag("es"))
        }
        motor.setPitch(0.9f)
        motor.setSpeechRate(1.0f)
        motor.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                principal.post { hud.modo = HudView.Modo.HABLANDO }
            }

            override fun onDone(utteranceId: String?) {
                principal.post {
                    if (!escuchando) {
                        hud.modo = HudView.Modo.REPOSO
                        estado.text = textoReposo
                    }
                }
            }

            @Deprecated("Requerido por versiones antiguas de Android")
            override fun onError(utteranceId: String?) {
                principal.post { hud.modo = HudView.Modo.REPOSO }
            }
        })
        ttsListo = true
        estado.text = textoReposo
        hablar(cerebro.saludoInicial())
    }

    private fun hablar(texto: String) {
        agregar("VELKYRA", texto, colorAsistente)
        if (ttsListo) {
            tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "v" + System.currentTimeMillis())
        } else {
            hud.modo = HudView.Modo.REPOSO
        }
    }

    // ------------------------------------------------------------------
    //  Oído (Reconocimiento de voz)
    // ------------------------------------------------------------------

    private fun escuchar() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pedirPermisos()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            hablar("No encuentro el servicio de reconocimiento de voz. Instala o actualiza la aplicación de Google.")
            return
        }
        tts?.stop()
        val rec = reconocedor ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(oyente)
            reconocedor = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        escuchando = true
        hud.modo = HudView.Modo.ESCUCHANDO
        estado.text = "Escuchando…"
        rec.startListening(intent)
    }

    private fun detenerEscucha() {
        reconocedor?.cancel()
        escuchando = false
        hud.modo = HudView.Modo.REPOSO
        estado.text = textoReposo
    }

    private val oyente = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            hud.nivel = rmsdB
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            estado.text = "Procesando…"
            hud.modo = HudView.Modo.PROCESANDO
        }

        override fun onError(error: Int) {
            escuchando = false
            hud.nivel = 0f
            hud.modo = HudView.Modo.REPOSO
            estado.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No te escuché. Toca el núcleo e inténtalo otra vez"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sin conexión para reconocer la voz"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta el permiso del micrófono"
                else -> textoReposo
            }
        }

        override fun onResults(results: Bundle?) {
            escuchando = false
            hud.nivel = 0f
            val texto = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (texto.isNullOrBlank()) {
                hud.modo = HudView.Modo.REPOSO
                estado.text = textoReposo
            } else {
                procesar(texto)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val parcial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!parcial.isNullOrBlank()) estado.text = parcial
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ------------------------------------------------------------------
    //  Procesar órdenes
    // ------------------------------------------------------------------

    private fun enviarTexto() {
        val texto = entrada.text.toString().trim()
        if (texto.isEmpty()) return
        entrada.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(entrada.windowToken, 0)
        procesar(texto)
    }

    private fun procesar(texto: String) {
        agregar(cerebro.nombre, texto, colorUsuario)
        hud.modo = HudView.Modo.PROCESANDO
        estado.text = "Procesando…"
        cerebro.procesar(texto) { respuesta ->
            principal.post {
                estado.text = textoReposo
                hablar(respuesta)
            }
        }
    }

    private fun agregar(quien: String, texto: String, color: Int) {
        val sb = SpannableStringBuilder()
        if (registro.text.isNotEmpty()) sb.append("\n\n")
        val inicio = sb.length
        sb.append(quien.uppercase(Locale.ROOT))
        sb.setSpan(ForegroundColorSpan(color), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n").append(texto)
        registro.append(sb)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------
    //  Ajustes
    // ------------------------------------------------------------------

    private fun mostrarAjustes() {
        val pad = (18 * resources.displayMetrics.density).toInt()
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        fun campo(etiqueta: String, valor: String, oculto: Boolean = false): EditText {
            val tv = TextView(this)
            tv.text = etiqueta
            tv.setTextColor(Color.rgb(127, 147, 168))
            tv.setPadding(0, pad / 2, 0, 0)
            caja.addView(tv)
            val et = EditText(this)
            et.setText(valor)
            et.setSingleLine()
            et.setTextColor(Color.WHITE)
            if (oculto) et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            caja.addView(et)
            return et
        }

        val cNombre = campo("Tu nombre", cerebro.nombre)
        val cClave = campo("Clave de IA de Google Gemini (opcional)", prefs.getString("clave_ia", "").orEmpty(), oculto = true)
        val cModelo = campo("Modelo de IA", prefs.getString("modelo_ia", Cerebro.MODELO_POR_DEFECTO).orEmpty())

        val ayuda = TextView(this)
        ayuda.text = "La clave es gratis: entra a aistudio.google.com con tu cuenta de Google y toca «Get API key». " +
            "Sin clave, VELKYRA igual cumple todas las órdenes del celular."
        ayuda.setTextColor(Color.rgb(127, 147, 168))
        ayuda.textSize = 12f
        ayuda.setPadding(0, pad / 2, 0, 0)
        caja.addView(ayuda)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Ajustes de VELKYRA")
            .setView(caja)
            .setPositiveButton("Guardar") { _, _ ->
                prefs.edit()
                    .putString("usuario", cNombre.text.toString().trim())
                    .putString("clave_ia", cClave.text.toString().trim())
                    .putString("modelo_ia", cModelo.text.toString().trim().ifEmpty { Cerebro.MODELO_POR_DEFECTO })
                    .apply()
                hablar("Configuración guardada, ${cerebro.nombre}.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------
    //  Permisos y ciclo de vida
    // ------------------------------------------------------------------

    private fun pedirPermisos() {
        val faltan = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (faltan.isNotEmpty()) requestPermissions(faltan.toTypedArray(), 7)
    }

    override fun onPause() {
        super.onPause()
        if (escuchando) detenerEscucha()
    }

    override fun onDestroy() {
        reconocedor?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
