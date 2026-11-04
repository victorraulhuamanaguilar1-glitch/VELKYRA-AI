package com.velkyra.yarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Núcleo holográfico animado: reacciona a la voz y cambia según el estado de YARVIS. */
class NucleoView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var estado = Yarvis.Estado.REPOSO
        set(v) { field = v; invalidate() }

    /** Nivel del micrófono (−2 a 10). */
    var nivel = 0f
        set(v) { field = v.coerceIn(0f, 10f) }

    /** Versión compacta (ventana flotante): menos detalles. */
    var compacto = false

    private var ang = 0f
    private var t = 0f
    private var nivelSuave = 0f
    private val barras = FloatArray(48)

    private val pLinea = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pRelleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    private fun color(): Int = when (estado) {
        Yarvis.Estado.PENSANDO -> Ui.AMBAR
        else -> Ui.CIAN
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.95f
        if (r <= 0) return
        val col = color()

        val vel = when (estado) {
            Yarvis.Estado.REPOSO -> 0.35f
            Yarvis.Estado.ESCUCHANDO -> 1.3f
            Yarvis.Estado.PENSANDO -> 3.6f
            Yarvis.Estado.HABLANDO -> 0.9f
        }
        ang = (ang + vel) % 360f
        t += 0.045f
        nivelSuave += (nivel - nivelSuave) * 0.3f

        // Resplandor
        pRelleno.shader = RadialGradient(cx, cy, r, intArrayOf(Ui.conAlfa(col, 70), Ui.conAlfa(col, 18), Color.TRANSPARENT), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, pRelleno)
        pRelleno.shader = null

        // Anillo exterior fino + arcos
        pLinea.color = col
        pLinea.alpha = 90
        pLinea.strokeWidth = r * 0.006f
        c.drawCircle(cx, cy, r * 0.95f, pLinea)
        pLinea.alpha = 230
        pLinea.strokeWidth = r * 0.02f
        rect.set(cx - r * 0.95f, cy - r * 0.95f, cx + r * 0.95f, cy + r * 0.95f)
        for (i in 0 until 4) c.drawArc(rect, ang + i * 90f, 42f, false, pLinea)

        if (!compacto) {
            // Marcas de reloj
            pLinea.strokeWidth = r * 0.008f
            val rm = r * 0.86f
            for (i in 0 until 90) {
                val a = Math.toRadians(i * 4.0 - ang * 0.3)
                val largo = if (i % 15 == 0) r * 0.07f else if (i % 5 == 0) r * 0.04f else r * 0.02f
                pLinea.alpha = if (i % 5 == 0) 170 else 80
                val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                c.drawLine(cx + ca * rm, cy + sa * rm, cx + ca * (rm - largo), cy + sa * (rm - largo), pLinea)
            }
        }

        // Barras de audio alrededor del núcleo
        val activo = estado == Yarvis.Estado.ESCUCHANDO || estado == Yarvis.Estado.HABLANDO
        val rb = r * 0.6f
        pLinea.strokeWidth = r * (if (compacto) 0.035f else 0.022f)
        for (i in barras.indices) {
            val objetivo = when (estado) {
                Yarvis.Estado.ESCUCHANDO -> (nivelSuave / 10f) * (0.4f + 0.6f * ((sin(t * 9 + i * 1.7f) + 1) / 2f))
                Yarvis.Estado.HABLANDO -> 0.25f + 0.35f * ((sin(t * 11 + i * 0.9f) * cos(t * 5 + i * 0.4f) + 1) / 2f)
                else -> 0.04f
            }
            barras[i] += (objetivo - barras[i]) * 0.25f
            val a = Math.toRadians(i * (360.0 / barras.size) + ang * 0.6)
            val l = r * 0.03f + r * 0.2f * barras[i]
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            pLinea.alpha = if (activo) 220 else 90
            c.drawLine(cx + ca * rb, cy + sa * rb, cx + ca * (rb + l), cy + sa * (rb + l), pLinea)
        }

        // Anillo medio segmentado (gira al revés)
        pLinea.alpha = 200
        pLinea.strokeWidth = r * 0.035f
        rect.set(cx - r * 0.5f, cy - r * 0.5f, cx + r * 0.5f, cy + r * 0.5f)
        for (i in 0 until 8) c.drawArc(rect, -ang * 1.5f + i * 45f, 26f, false, pLinea)

        // Arco de "carga" mientras piensa
        if (estado == Yarvis.Estado.PENSANDO) {
            pLinea.strokeWidth = r * 0.015f
            pLinea.alpha = 255
            rect.set(cx - r * 0.42f, cy - r * 0.42f, cx + r * 0.42f, cy + r * 0.42f)
            c.drawArc(rect, ang * 3f, 90f + 80f * sin(t * 4), false, pLinea)
        }

        // Núcleo de energía
        val pulso = when (estado) {
            Yarvis.Estado.HABLANDO -> 0.035f * sin(t * 8) + 0.03f
            Yarvis.Estado.ESCUCHANDO -> nivelSuave / 10f * 0.08f
            Yarvis.Estado.PENSANDO -> 0.015f * sin(t * 12)
            Yarvis.Estado.REPOSO -> 0.012f * sin(t * 1.6f)
        }
        val rn = r * (0.3f + pulso)
        pRelleno.shader = RadialGradient(cx, cy, rn, intArrayOf(0xFFF2FDFF.toInt(), Ui.conAlfa(col, 255), Ui.conAlfa(col, 40)), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rn, pRelleno)
        pRelleno.shader = null

        // Anillo interno blanco
        pLinea.color = 0xFFE8FBFF.toInt()
        pLinea.alpha = 160
        pLinea.strokeWidth = r * 0.008f
        c.drawCircle(cx, cy, rn * 0.72f, pLinea)

        postInvalidateOnAnimation()
    }
}

/** Fondo de cuadrícula holográfica con línea de escaneo y esquinas de interfaz. */
class FondoHud @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val pRejilla = Paint().apply { color = 0x0F2DE2FF; strokeWidth = 1f }
    private val pEsquina = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x882DE2FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val pScan = Paint()
    private val pVineta = Paint()
    private var y = 0f
    private val path = Path()

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(Ui.FONDO)
        val paso = context.dpf(28f)
        var x = 0f
        while (x < w) { c.drawLine(x, 0f, x, h, pRejilla); x += paso }
        var yy = 0f
        while (yy < h) { c.drawLine(0f, yy, w, yy, pRejilla); yy += paso }

        // Línea de escaneo
        y = (y + context.dpf(1.2f)) % (h + 200f)
        pScan.shader = LinearGradient(0f, y - 120f, 0f, y, Color.TRANSPARENT, 0x182DE2FF, Shader.TileMode.CLAMP)
        c.drawRect(0f, y - 120f, w, y, pScan)

        // Viñeta
        pVineta.shader = RadialGradient(w / 2, h * 0.38f, maxOf(w, h) * 0.75f, intArrayOf(0x00000000, 0xCC000000.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, pVineta)

        // Esquinas
        val m = context.dpf(10f)
        val l = context.dpf(22f)
        path.reset()
        path.moveTo(m, m + l); path.lineTo(m, m); path.lineTo(m + l, m)
        path.moveTo(w - m - l, m); path.lineTo(w - m, m); path.lineTo(w - m, m + l)
        path.moveTo(m, h - m - l); path.lineTo(m, h - m); path.lineTo(m + l, h - m)
        path.moveTo(w - m - l, h - m); path.lineTo(w - m, h - m); path.lineTo(w - m, h - m - l)
        c.drawPath(path, pEsquina)

        postInvalidateDelayed(32)
    }
}
