package com.velkyra.yarvis

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Colores, fuentes y piezas visuales del estilo holográfico. */
object Ui {
    const val FONDO = 0xFF02060C.toInt()
    const val PANEL = 0xCC071624.toInt()
    const val PANEL_FUERTE = 0xFF0A1C2C.toInt()
    const val BORDE = 0x552DE2FF
    const val CIAN = 0xFF2DE2FF.toInt()
    const val CIAN_CLARO = 0xFFA8F4FF.toInt()
    const val TEXTO = 0xFFDDF6FF.toInt()
    const val TEXTO_SUAVE = 0xFF6F8FA6.toInt()
    const val AMBAR = 0xFFFFC24A.toInt()
    const val VERDE = 0xFF3DFFB0.toInt()
    const val ROJO = 0xFFFF5C7A.toInt()

    var titulo: Typeface = Typeface.DEFAULT_BOLD
        private set
    var normal: Typeface = Typeface.DEFAULT
        private set
    var fuerte: Typeface = Typeface.DEFAULT_BOLD
        private set

    fun init(ctx: Context) {
        try {
            titulo = Typeface.createFromAsset(ctx.assets, "fuentes/Orbitron.ttf")
            normal = Typeface.createFromAsset(ctx.assets, "fuentes/Rajdhani-Medium.ttf")
            fuerte = Typeface.createFromAsset(ctx.assets, "fuentes/Rajdhani-SemiBold.ttf")
        } catch (_: Exception) {
        }
    }

    fun panel(ctx: Context, radioDp: Float = 16f, relleno: Int = PANEL, borde: Int = BORDE, grosorDp: Float = 1f) =
        GradientDrawable().apply {
            cornerRadius = ctx.dpf(radioDp)
            setColor(relleno)
            setStroke(ctx.dp(grosorDp), borde)
        }

    fun degradadoCian(ctx: Context, radioDp: Float = 28f) = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF22C6F0.toInt(), 0xFF5FF0FF.toInt())
    ).apply { cornerRadius = ctx.dpf(radioDp) }

    fun conAlfa(color: Int, alfa: Int) = Color.argb(alfa, Color.red(color), Color.green(color), Color.blue(color))
}

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun Context.dpf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

fun Context.texto(
    t: CharSequence,
    tam: Float = 16f,
    color: Int = Ui.TEXTO,
    fuente: Typeface = Ui.normal,
    espaciado: Float = 0f
): TextView = TextView(this).apply {
    text = t
    textSize = tam
    setTextColor(color)
    typeface = fuente
    letterSpacing = espaciado
}

fun Context.etiqueta(t: String): TextView =
    texto(t.uppercase(), 11f, Ui.CIAN, Ui.titulo, 0.25f).apply { alpha = 0.85f }

fun Context.boton(t: String, primario: Boolean = true, alTocar: () -> Unit): TextView =
    texto(t, 17f, if (primario) Ui.FONDO else Ui.CIAN, Ui.fuerte, 0.06f).apply {
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(13), dp(22), dp(13))
        background = if (primario) Ui.degradadoCian(context) else Ui.panel(context, 28f, 0x1A2DE2FF, Ui.CIAN)
        isClickable = true
        isFocusable = true
        foreground = context.getDrawable(android.R.drawable.list_selector_background)
        setOnClickListener { alTocar() }
    }

fun Context.chip(t: String, activo: Boolean = false, alTocar: () -> Unit): TextView =
    texto(t, 15f, if (activo) Ui.FONDO else Ui.CIAN_CLARO, Ui.fuerte).apply {
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = if (activo) Ui.degradadoCian(context, 20f) else Ui.panel(context, 20f, 0x14FFFFFF, Ui.BORDE)
        setOnClickListener { alTocar() }
    }

fun Context.campo(pista: String, valor: String = "", clave: Boolean = false): EditText = EditText(this).apply {
    hint = pista
    setText(valor)
    setSingleLine()
    textSize = 17f
    typeface = Ui.normal
    setTextColor(Ui.TEXTO)
    setHintTextColor(Ui.TEXTO_SUAVE)
    background = Ui.panel(context, 14f, Ui.PANEL_FUERTE, Ui.BORDE)
    setPadding(dp(16), dp(12), dp(16), dp(12))
    inputType = if (clave) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
}

fun Context.interruptor(t: String, detalle: String?, valor: Boolean, alCambiar: (Boolean) -> Unit): LinearLayout {
    val fila = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
    }
    val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    textos.addView(texto(t, 17f, Ui.TEXTO, Ui.fuerte))
    if (detalle != null) textos.addView(texto(detalle, 14f, Ui.TEXTO_SUAVE))
    fila.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val sw = Switch(this).apply {
        isChecked = valor
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Ui.CIAN, 0xFF8A9BAA.toInt())
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0x882DE2FF.toInt(), 0x44FFFFFF)
        )
        setOnCheckedChangeListener { _, v -> alCambiar(v) }
    }
    fila.addView(sw)
    fila.setOnClickListener { sw.toggle() }
    return fila
}

fun Context.deslizador(maximo: Int, valor: Int, alCambiar: (Int) -> Unit): SeekBar = SeekBar(this).apply {
    max = maximo
    progress = valor
    progressTintList = ColorStateList.valueOf(Ui.CIAN)
    thumbTintList = ColorStateList.valueOf(Ui.CIAN_CLARO)
    progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, deUsuario: Boolean) {
            if (deUsuario) alCambiar(p)
        }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    })
}

fun Context.tarjeta(titulo: String? = null): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = Ui.panel(context, 18f)
    setPadding(dp(18), dp(16), dp(18), dp(16))
    if (titulo != null) {
        addView(etiqueta(titulo))
        addView(View(context), LinearLayout.LayoutParams(1, dp(8)))
    }
}

fun LinearLayout.agregar(v: View, ancho: Int = ViewGroup.LayoutParams.MATCH_PARENT, alto: Int = ViewGroup.LayoutParams.WRAP_CONTENT, arriba: Int = 0, peso: Float = 0f): View {
    val lp = LinearLayout.LayoutParams(ancho, alto, peso)
    lp.topMargin = arriba
    addView(v, lp)
    return v
}

fun LinearLayout.espacio(altoDp: Int) {
    addView(View(context), LinearLayout.LayoutParams(1, context.dp(altoDp)))
}
