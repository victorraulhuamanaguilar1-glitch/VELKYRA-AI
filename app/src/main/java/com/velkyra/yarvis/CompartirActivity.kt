package com.velkyra.yarvis

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * «Compartir → ODÍN»: audios de WhatsApp (los transcribe), fotos (las describe y lee), PDFs y textos (los resume).
 */
class CompartirActivity : Base() {

    private lateinit var estado: TextView
    private lateinit var resultado: TextView
    private var texto = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cuerpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        estado = texto("Recibiendo…", 16f, Ui.CIAN_CLARO, Ui.fuerte)
        cuerpo.addView(estado)
        resultado = texto("", 17f, Ui.TEXTO).apply { setTextIsSelectable(true) }
        val tarjeta = tarjeta("Resultado")
        tarjeta.addView(resultado)
        cuerpo.agregar(tarjeta, arriba = dp(12))
        val botones = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        botones.agregar(boton("LEER EN VOZ ALTA", true) { if (texto.isNotBlank()) Voz.hablar(texto) }, arriba = dp(4))
        botones.agregar(boton("COPIAR", false) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ODÍN", texto))
            android.widget.Toast.makeText(this, "Copiado", android.widget.Toast.LENGTH_SHORT).show()
        }, arriba = dp(8))
        botones.agregar(boton("COMPARTIR", false) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), "Compartir"))
        }, arriba = dp(8))
        botones.agregar(boton("CERRAR", false) { Voz.detener(); finish() }, arriba = dp(8))
        cuerpo.agregar(botones, arriba = dp(14))
        montar(pantallaConTitulo("ODÍN", cuerpo))
        procesar(intent)
    }

    private fun procesar(i: Intent) {
        val tipo = i.type.orEmpty()
        val compartido = i.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val uri: Uri? = i.getParcelableExtra(Intent.EXTRA_STREAM) ?: i.clipData?.getItemAt(0)?.uri
        if (uri == null && compartido.isNullOrBlank()) { estado.text = "No recibí nada para analizar."; return }
        if (!CerebroIA.disponible()) { estado.text = "Necesito la inteligencia artificial activa (Ajustes › IA)."; return }
        Thread {
            val r = try {
                when {
                    uri == null -> {
                        poner("Leyendo el texto…")
                        val t = compartido!!
                        if (t.length < 500) t else "Resumen:\n" + CerebroIA.preguntar(t.take(30000), buscar = false, recordar = false,
                            sistemaFijo = "Resume este texto en español en 4 a 6 oraciones claras. Sin markdown.")
                    }
                    tipo.startsWith("audio") || tipo.contains("ogg") || tipo.contains("opus") -> {
                        poner("🎧 Transcribiendo el audio…")
                        val f = copiar(uri, "audio")
                        val t = Transcriptor.transcribir(f, tipo.ifBlank { "audio/ogg" })
                        f.delete()
                        if (t.length > 700) {
                            val res = try {
                                CerebroIA.preguntar(t, buscar = false, recordar = false, sistemaFijo = "Resume en 2 o 3 oraciones lo que dice este audio. Sin markdown.")
                            } catch (_: Exception) { "" }
                            (if (res.isNotBlank()) "En resumen: $res\n\n" else "") + "Dice:\n$t"
                        } else t.ifBlank { "No se entendió ninguna voz en el audio." }
                    }
                    tipo.startsWith("image") -> {
                        poner("🖼️ Mirando la imagen…")
                        val f = copiar(uri, "imagen")
                        val bytes = f.readBytes(); f.delete()
                        CerebroIA.verImagen(achicar(bytes), "Describe brevemente esta imagen. Si tiene texto (un documento, captura, cartel, mensaje), léelo completo y en orden.")
                    }
                    tipo == "application/pdf" -> {
                        poner("📄 Leyendo el PDF…")
                        val f = copiar(uri, "doc.pdf")
                        val t = CerebroIA.conArchivoGemini(f, "application/pdf", "Resume este documento en español: de qué trata, puntos importantes, fechas, montos y qué hay que hacer. Texto plano, sin markdown, máximo 250 palabras.")
                        f.delete(); t
                    }
                    else -> {
                        poner("Leyendo el archivo…")
                        val f = copiar(uri, "archivo")
                        val t = f.readText().take(30000); f.delete()
                        CerebroIA.preguntar(t, buscar = false, recordar = false, sistemaFijo = "Resume este texto en español en 4 a 6 oraciones claras. Sin markdown.")
                    }
                }
            } catch (e: Exception) {
                if (!CerebroIA.hayInternet()) "Necesito internet para analizarlo." else "No pude analizarlo ahora (${CerebroIA.detalle(e)}). Inténtelo en un minuto."
            }
            runOnUiThread {
                texto = r
                estado.text = "Listo"
                resultado.text = r
            }
        }.start()
    }

    private fun poner(t: String) = runOnUiThread { estado.text = t }

    private fun copiar(uri: Uri, nombre: String): File {
        var ext = ""
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) ext = it.getString(0)?.substringAfterLast('.', "").orEmpty()
            }
        } catch (_: Exception) {}
        val f = File(cacheDir, "compartido_" + System.currentTimeMillis() + if (ext.isNotBlank()) ".$ext" else "_$nombre")
        contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun achicar(b: ByteArray): ByteArray = try {
        val bm = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)
        val f = minOf(1f, 1600f / maxOf(bm.width, bm.height))
        val esc = if (f < 1f) android.graphics.Bitmap.createScaledBitmap(bm, (bm.width * f).toInt(), (bm.height * f).toInt(), true) else bm
        val o = java.io.ByteArrayOutputStream()
        esc.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, o)
        o.toByteArray()
    } catch (_: Exception) { b }
}
