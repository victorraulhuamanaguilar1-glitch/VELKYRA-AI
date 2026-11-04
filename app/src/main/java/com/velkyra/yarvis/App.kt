package com.velkyra.yarvis

import android.app.Application

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instancia = this
        Prefs.init(this)
        Ui.init(this)
        Avisos.crearCanales(this)
        Yarvis.init(this)
    }

    companion object {
        lateinit var instancia: App
            private set
    }
}
