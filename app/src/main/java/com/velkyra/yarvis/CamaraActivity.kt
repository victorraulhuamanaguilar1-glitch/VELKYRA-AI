package com.velkyra.yarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream

/**
 * «Odín, ¿qué ves?»: muestra la cámara, toma una foto sola a los 3 segundos (o al tocar la pantalla)
 * y la IA la describe, lee el texto o lo traduce. ODÍN lo dice en voz alta.
 */
class CamaraActivity : Activity() {

    companion object {
        const val EXTRA_INSTRUCCION = "instruccion"
        const val EXTRA_TITULO = "titulo"

        /** Última foto analizada (para preguntas de seguimiento: «¿y de qué color es?»). */
        @Volatile var ultimaFoto: ByteArray? = null
        @Volatile var ultimaFotoHora = 0L

        fun abrir(ctx: Context, instruccion: String, titulo: String) {
            ctx.startActivity(Intent(ctx, CamaraActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_INSTRUCCION, instruccion).putExtra(EXTRA_TITULO, titulo))
        }
    }

    private val principal = Handler(Looper.getMainLooper())
    private lateinit var vista: TextureView
    private lateinit var estado: TextView
    private var hilo: HandlerThread? = null
    private var fondo: Handler? = null
    private var camara: CameraDevice? = null
    private var sesion: CameraCaptureSession? = null
    private var lector: ImageReader? = null
    private var tomada = false
    private var rotacionSensor = 90

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        vista = TextureView(this)
        raiz.addView(vista, FrameLayout.LayoutParams(-1, -1))
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = Ui.panel(this@CamaraActivity, 18f, Ui.conAlfa(Color.BLACK, 190))
        }
        caja.addView(texto(intent.getStringExtra(EXTRA_TITULO) ?: "ODÍN está mirando", 18f, Ui.CIAN, Ui.fuerte))
        estado = texto("Apunte la cámara. Tomo la foto en 3 segundos… (o toque la pantalla)", 15f, Ui.TEXTO)
        caja.agregar(estado, arriba = dp(4))
        raiz.addView(caja, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(28)) })
        raiz.setOnClickListener { capturar() }
        setContentView(raiz)

        if (checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
        } else iniciar()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) iniciar()
        else { Yarvis.anunciar("Necesito permiso para usar la cámara, ${Prefs.trato}."); finish() }
    }

    private fun iniciar() {
        hilo = HandlerThread("odin-camara").also { it.start() }
        fondo = Handler(hilo!!.looper)
        if (vista.isAvailable) abrirCamara() else vista.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = abrirCamara()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        principal.postDelayed({ capturar() }, 3200)
    }

    @SuppressLint("MissingPermission")
    private fun abrirCamara() {
        val cm = getSystemService(CameraManager::class.java)
        val id = try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: cm.cameraIdList.first()
        } catch (_: Exception) { fallar("No encontré la cámara"); return }
        val car = cm.getCameraCharacteristics(id)
        rotacionSensor = car.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val mapa = car.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        // Foto de tamaño moderado (suficiente para leer texto y rápida de enviar)
        val tam = mapa?.getOutputSizes(ImageFormat.JPEG)?.filter { it.width <= 2600 }?.maxByOrNull { it.width * it.height }
            ?: mapa?.getOutputSizes(ImageFormat.JPEG)?.minByOrNull { it.width * it.height }
        val prev = mapa?.getOutputSizes(SurfaceTexture::class.java)?.filter { it.width <= 1920 }?.maxByOrNull { it.width * it.height }
        lector = ImageReader.newInstance(tam?.width ?: 1600, tam?.height ?: 1200, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                img.close()
                analizar(bytes)
            }, fondo)
        }
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    camara = c
                    val st = vista.surfaceTexture ?: return
                    prev?.let { st.setDefaultBufferSize(it.width, it.height) }
                    val sup = Surface(st)
                    @Suppress("DEPRECATION")
                    c.createCaptureSession(listOf(sup, lector!!.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            sesion = s
                            val req = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(sup)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            }
                            try { s.setRepeatingRequest(req.build(), null, fondo) } catch (_: Exception) {}
                            principal.post { ajustarVista(prev?.width ?: 4, prev?.height ?: 3) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { fallar("No pude iniciar la cámara") }
                    }, fondo)
                }
                override fun onDisconnected(c: CameraDevice) { c.close() }
                override fun onError(c: CameraDevice, e: Int) { c.close(); fallar("La cámara está ocupada") }
            }, fondo)
        } catch (e: Exception) { fallar("No pude abrir la cámara") }
    }

    /** Escala la vista previa para que no se vea estirada (la cámara entrega la imagen acostada). */
    private fun ajustarVista(pw: Int, ph: Int) {
        val vw = vista.width.toFloat(); val vh = vista.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val ancho = minOf(pw, ph).toFloat(); val alto = maxOf(pw, ph).toFloat()
        val escala = maxOf(vw / ancho, vh / alto)
        val m = Matrix()
        m.setScale(ancho * escala / vw, alto * escala / vh, vw / 2, vh / 2)
        vista.setTransform(m)
    }

    private fun capturar() {
        if (tomada) return
        val c = camara ?: run { principal.postDelayed({ capturar() }, 500); return }
        val s = sesion ?: run { principal.postDelayed({ capturar() }, 500); return }
        tomada = true
        estado.text = "📸 Foto tomada. Analizando…"
        try {
            val req = c.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(lector!!.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, rotacionSensor)
                set(CaptureRequest.JPEG_QUALITY, 90.toByte())
            }
            s.capture(req.build(), null, fondo)
        } catch (_: Exception) { fallar("No pude tomar la foto") }
    }

    private fun analizar(original: ByteArray) {
        val jpeg = reducir(original)
        ultimaFoto = jpeg
        ultimaFotoHora = System.currentTimeMillis()
        principal.post { cerrarCamara() }
        val instr = intent.getStringExtra(EXTRA_INSTRUCCION) ?: "Describe lo que ves."
        Thread {
            val r = try {
                Yarvis.Respuesta(CerebroIA.verImagen(jpeg, instr), seguir = true)
            } catch (e: Exception) {
                Yarvis.Respuesta(if (!CerebroIA.hayInternet()) "Necesito internet para analizar la foto, ${Prefs.trato}."
                    else "No pude analizar la foto en este momento, ${Prefs.trato}. Inténtelo en un minuto.", cerrar = true, detalle = CerebroIA.detalle(e))
            }
            principal.post {
                estado.text = r.texto
                Yarvis.decirYEscuchar(r, urgente = true)
                principal.postDelayed({ finish() }, 1500)
            }
        }.start()
    }

    /** Achica la foto a 1280 px y aplica la rotación (más rápida de enviar, igual de legible). */
    private fun reducir(b: ByteArray): ByteArray = try {
        val op = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, op)
        var muestra = 1
        while (maxOf(op.outWidth, op.outHeight) / (muestra * 2) >= 1280) muestra *= 2
        var bm = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = muestra })
        val lado = maxOf(bm.width, bm.height)
        if (lado > 1600) {
            val f = 1600f / lado
            bm = Bitmap.createScaledBitmap(bm, (bm.width * f).toInt(), (bm.height * f).toInt(), true)
        }
        val out = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, 82, out)
        out.toByteArray()
    } catch (_: Exception) { b }

    private fun fallar(msg: String) = principal.post {
        Yarvis.anunciar("$msg, ${Prefs.trato}.")
        finish()
    }

    private fun cerrarCamara() {
        try { sesion?.close() } catch (_: Exception) {}
        try { camara?.close() } catch (_: Exception) {}
        sesion = null
        camara = null
    }

    override fun onDestroy() {
        principal.removeCallbacksAndMessages(null)
        cerrarCamara()
        try { lector?.close() } catch (_: Exception) {}
        hilo?.quitSafely()
        super.onDestroy()
    }
}
