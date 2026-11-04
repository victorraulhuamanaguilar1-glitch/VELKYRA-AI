package com.velkyra.asistente

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Núcleo holográfico animado de VELKYRA.
 * Cambia de color y velocidad según lo que esté haciendo el asistente.
 */
class HudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Modo { REPOSO, ESCUCHANDO, PROCESANDO, HABLANDO }

    var modo: Modo = Modo.REPOSO
        set(valor) {
            field = valor
            invalidate()
        }

    /** Nivel de voz del micrófono (0 a 10) para que el núcleo "respire" mientras escucha. */
    var nivel: Float = 0f
        set(valor) {
            field = valor.coerceIn(0f, 10f)
        }

    private var angulo = 0f
    private var tiempo = 0f
    private var nivelSuave = 0f

    private val pArco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pRelleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val cian = Color.rgb(34, 211, 238)
    private val violeta = Color.rgb(168, 85, 247)
    private val blancoAzul = Color.rgb(224, 250, 255)

    private fun conAlfa(color: Int, alfa: Int): Int =
        Color.argb(alfa, Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.94f
        if (r <= 0f) return

        val velocidad = when (modo) {
            Modo.REPOSO -> 0.5f
            Modo.ESCUCHANDO -> 1.8f
            Modo.PROCESANDO -> 4.5f
            Modo.HABLANDO -> 1.2f
        }
        angulo = (angulo + velocidad) % 360f
        tiempo += 0.05f
        nivelSuave += (nivel - nivelSuave) * 0.25f

        val color = if (modo == Modo.PROCESANDO) violeta else cian

        // Resplandor de fondo
        pRelleno.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(conAlfa(color, 60), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, pRelleno)
        pRelleno.shader = null

        // Anillo exterior: tres arcos que giran
        pArco.color = color
        pArco.alpha = 230
        pArco.strokeWidth = r * 0.022f
        rect.set(cx - r * 0.92f, cy - r * 0.92f, cx + r * 0.92f, cy + r * 0.92f)
        for (i in 0 until 3) {
            canvas.drawArc(rect, angulo + i * 120f, 78f, false, pArco)
        }

        // Anillo de marcas (como un reloj)
        pArco.strokeWidth = r * 0.01f
        pArco.alpha = 130
        val rMarcas = r * 0.8f
        for (i in 0 until 72) {
            val a = Math.toRadians((i * 5.0) - angulo * 0.4)
            val largo = if (i % 6 == 0) r * 0.07f else r * 0.03f
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            canvas.drawLine(
                cx + ca * rMarcas, cy + sa * rMarcas,
                cx + ca * (rMarcas - largo), cy + sa * (rMarcas - largo),
                pArco
            )
        }

        // Anillo medio: segmentos que giran al revés
        pArco.alpha = 190
        pArco.strokeWidth = r * 0.05f
        rect.set(cx - r * 0.62f, cy - r * 0.62f, cx + r * 0.62f, cy + r * 0.62f)
        for (i in 0 until 6) {
            canvas.drawArc(rect, -angulo * 1.4f + i * 60f, 36f, false, pArco)
        }

        // Anillo interno fino (violeta)
        pArco.color = violeta
        pArco.alpha = 160
        pArco.strokeWidth = r * 0.012f
        rect.set(cx - r * 0.5f, cy - r * 0.5f, cx + r * 0.5f, cy + r * 0.5f)
        canvas.drawArc(rect, angulo * 2f, 250f, false, pArco)

        // Núcleo de energía
        val pulso = when (modo) {
            Modo.HABLANDO -> 0.05f * sin(tiempo * 7f) + 0.03f
            Modo.ESCUCHANDO -> nivelSuave / 10f * 0.12f
            Modo.PROCESANDO -> 0.02f * sin(tiempo * 10f)
            Modo.REPOSO -> 0.015f * sin(tiempo * 1.5f)
        }
        val rNucleo = r * (0.34f + pulso)
        pRelleno.shader = RadialGradient(
            cx, cy, rNucleo,
            intArrayOf(blancoAzul, color, conAlfa(color, 30)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, rNucleo, pRelleno)
        pRelleno.shader = null

        postInvalidateOnAnimation()
    }
}
