package com.velkyra.yarvis

import android.app.Application

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instancia = this
        Prefs.init(this)
        // Una sola vez: volver a la voz de Google del celular como principal
        val sp = getSharedPreferences("yarvis", MODE_PRIVATE)
        if (!sp.getBoolean("migr_voz_google", false)) {
            sp.edit().putBoolean("usar_voz_neural", false).putBoolean("migr_voz_google", true).apply()
        }
        // Una sola vez: el asistente pasa a llamarse ODÍN (la palabra «Yarvis» entrenada se reemplaza)
        if (!sp.getBoolean("migr_odin", false)) {
            val pal = Frase.normalizar(sp.getString("palabra_activacion", "") ?: "")
            val e = sp.edit().putBoolean("migr_odin", true)
            if (pal.isBlank() || pal.contains("yarvis") || pal.contains("jarvis")) e.putString("palabra_activacion", "Odín").putString("muestras_palabra", "[]")
            e.apply()
        }
        Ui.init(this)
        Avisos.crearCanales(this)
        Yarvis.init(this)
        // Si el sistema no conectó el lector de notificaciones (pasa tras actualizar en Xiaomi), lo reconecta
        android.os.Handler(mainLooper).postDelayed({ ServicioNotificaciones.reconectar(this) }, 3000)
        // Copia de seguridad automática (una vez al día, en Descargas/ODIN)
        android.os.Handler(mainLooper).postDelayed({ Respaldo.automatico(this) }, 15000)
    }

    companion object {
        lateinit var instancia: App
            private set
    }
}
