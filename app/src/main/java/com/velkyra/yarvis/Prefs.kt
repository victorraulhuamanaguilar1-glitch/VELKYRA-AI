package com.velkyra.yarvis

import android.content.Context
import android.content.SharedPreferences

/** Todas las preferencias de YARVIS en un solo lugar. */
object Prefs {

    const val MODELO_IA = "gemini-flash-latest"
    const val CORREO_SOPORTE = "velkyra3@gmail.com"

    val APPS_MENSAJERIA = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca",
        "com.facebook.mlite", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.mms", "com.instagram.android", "com.microsoft.teams", "com.discord",
        "com.google.android.gm", "jp.naver.line.android", "com.viber.voip", "com.snapchat.android"
    )

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("yarvis", Context.MODE_PRIVATE)
    }

    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun ps(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun pb(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun f(k: String, d: Float) = sp.getFloat(k, d)
    private fun pf(k: String, v: Float) = sp.edit().putFloat(k, v).apply()

    var configurado: Boolean
        get() = b("configurado", false)
        set(v) = pb("configurado", v)

    /** Nombre real del usuario. */
    var nombre: String
        get() = s("nombre", "")
        set(v) = ps("nombre", v.trim())

    /** Cómo quiere que YARVIS se dirija a él: "señor", "jefe", su nombre… */
    var trato: String
        get() = s("trato", "").ifBlank { nombre.ifBlank { "señor" } }
        set(v) = ps("trato", v.trim())

    var tratoGuardado: String
        get() = s("trato", "")
        set(v) = ps("trato", v.trim())

    var voz: String
        get() = s("voz", "")
        set(v) = ps("voz", v)

    var tono: Float
        get() = f("tono", 0.82f)
        set(v) = pf("tono", v)

    var velocidad: Float
        get() = f("velocidad", 0.97f)
        set(v) = pf("velocidad", v)

    var responderAlActivar: Boolean
        get() = b("responder_activar", true)
        set(v) = pb("responder_activar", v)

    var escuchaActiva: Boolean
        get() = b("escucha_activa", true)
        set(v) = pb("escucha_activa", v)

    /** "siempre" (incluso bloqueado) o "pantalla" (solo con la pantalla encendida). */
    var modoEscucha: String
        get() = s("modo_escucha", "siempre")
        set(v) = ps("modo_escucha", v)

    var claveIAPropia: String
        get() = s("clave_ia", "")
        set(v) = ps("clave_ia", v.trim())

    val claveIA: String
        get() = claveIAPropia.ifBlank { BuildConfig.GEMINI_KEY }

    val claveIncluida: Boolean
        get() = BuildConfig.GEMINI_KEY.isNotBlank()

    var modeloIA: String
        get() = s("modelo_ia", MODELO_IA).ifBlank { MODELO_IA }
        set(v) = ps("modelo_ia", v.trim())

    var leerMensajes: Boolean
        get() = b("leer_mensajes", true)
        set(v) = pb("leer_mensajes", v)

    var leerTodasLasApps: Boolean
        get() = b("leer_todas", false)
        set(v) = pb("leer_todas", v)

    var whatsappAutomatico: Boolean
        get() = b("wa_auto", true)
        set(v) = pb("wa_auto", v)

    var ciudad: String
        get() = s("ciudad", "")
        set(v) = ps("ciudad", v.trim())

    var notas: String
        get() = s("notas", "")
        set(v) = ps("notas", v)

    var recordatorios: String
        get() = s("recordatorios", "[]")
        set(v) = ps("recordatorios", v)

    var contadorIds: Int
        get() = sp.getInt("contador_ids", 1000)
        set(v) = sp.edit().putInt("contador_ids", v).apply()
}
