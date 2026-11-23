package com.velkyra.yarvis

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager

/**
 * Modos de ODÍN:
 * - MANEJO: lee en voz alta todo lo que llega y ofrece responder por voz.
 * - DORMIR: silencio total (No molestar con alarmas), no anuncia nada.
 * - REUNION: celular en vibración, no habla ni anuncia nada.
 */
object Modos {
    const val NORMAL = "normal"
    const val MANEJO = "manejo"
    const val DORMIR = "dormir"
    const val REUNION = "reunion"

    private fun sp() = Yarvis.ctx.getSharedPreferences("yarvis", Context.MODE_PRIVATE)

    val actual: String get() = sp().getString("modo", NORMAL) ?: NORMAL

    fun nombre(m: String = actual) = when (m) {
        MANEJO -> "modo manejo"; DORMIR -> "modo dormir"; REUNION -> "modo reunión"; else -> "modo normal"
    }

    /** ¿Puede anunciar mensajes y avisos en voz alta? */
    val puedeAnunciar get() = actual != DORMIR && actual != REUNION

    /** Cambia de modo. Devuelve lo que ODÍN dice. */
    fun activar(ctx: Context, m: String): String {
        val tr = Prefs.trato
        val previo = actual
        if (previo != NORMAL && previo != m) restaurar(ctx)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val e = sp().edit().putString("modo", m)
        var extra = ""
        when (m) {
            DORMIR -> {
                if (nm.isNotificationPolicyAccessGranted) {
                    e.putInt("modo_filtro_antes", nm.currentInterruptionFilter)
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                } else {
                    e.putInt("modo_timbre_antes", am.ringerMode)
                    try { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (_: Exception) {}
                    extra = " Para silenciarlo por completo, deme acceso a «No molestar» en la pantalla que le abrí."
                    try {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {}
                }
            }
            REUNION -> {
                e.putInt("modo_timbre_antes", am.ringerMode)
                try { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (_: Exception) {}
            }
        }
        e.apply()
        ServicioEscucha.actualizar(ctx)
        return when (m) {
            MANEJO -> "Modo manejo activado, $tr. Le leeré en voz alta los mensajes y llamadas que lleguen, y podrá responderlos con la voz. Maneje con cuidado."
            DORMIR -> "Modo dormir activado, $tr. No le interrumpiré con mensajes ni llamadas; las alarmas sí sonarán. Que descanse.$extra"
            REUNION -> "Modo reunión activado, $tr. Puse el celular en vibración y no anunciaré nada hasta que me lo indique."
            else -> "Modo normal, $tr."
        }
    }

    fun desactivar(ctx: Context): String {
        val tr = Prefs.trato
        if (actual == NORMAL) return "Ya estoy en modo normal, $tr."
        val antes = nombre()
        restaurar(ctx)
        sp().edit().putString("modo", NORMAL).apply()
        ServicioEscucha.actualizar(ctx)
        return "Listo, $tr. Desactivé el $antes y todo vuelve a la normalidad."
    }

    private fun restaurar(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val s = sp()
        val filtro = s.getInt("modo_filtro_antes", -1)
        if (filtro > 0 && nm.isNotificationPolicyAccessGranted) try { nm.setInterruptionFilter(filtro) } catch (_: Exception) {}
        val timbre = s.getInt("modo_timbre_antes", -1)
        if (timbre >= 0) try { am.ringerMode = timbre } catch (_: Exception) {}
        s.edit().remove("modo_filtro_antes").remove("modo_timbre_antes").apply()
    }
}
