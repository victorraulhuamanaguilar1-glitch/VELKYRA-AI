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
        Ui.init(this)
        Avisos.crearCanales(this)
        Yarvis.init(this)
    }

    companion object {
        lateinit var instancia: App
            private set
    }
}
