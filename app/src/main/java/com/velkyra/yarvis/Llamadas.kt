package com.velkyra.yarvis

import android.Manifest
import android.app.Notification
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager

/**
 * Llamadas entrantes por voz (teléfono, WhatsApp, Messenger, Telegram…):
 * «Le llama Juan. ¿Contesto?» → «contesta», «en altavoz», «recházala», «dile que lo llamo luego».
 */
object Llamadas {

    private val principal = Handler(Looper.getMainLooper())

    private data class Entrante(val clave: String, val quien: String, val app: String, val paquete: String,
                                val contestar: Notification.Action?, val rechazar: Notification.Action?)

    @Volatile private var actual: Entrante? = null
    private val anunciadas = HashMap<String, Long>()
    private var timbreSilenciado = false

    private val rContestar = Regex("(?i)(contestar|responder|aceptar|answer|accept|atender)")
    private val rRechazar = Regex("(?i)(rechazar|colgar|decline|reject|ignorar|finalizar|hang up|cortar)")

    /** ¿Es la notificación de una llamada que está sonando? Si lo es, la anuncia y devuelve true. */
    fun revisar(ctx: Context, sbn: StatusBarNotification, app: String): Boolean {
        val n = sbn.notification
        val acciones = n.actions?.toList().orEmpty()
        val tipo = n.extras.getInt("android.callType", 0) // 1 = entrante (Android 12+)
        val esLlamada = n.category == Notification.CATEGORY_CALL
        if (!esLlamada) return false
        val contestar = acciones.firstOrNull { rContestar.containsMatchIn(it.title ?: "") }
        val rechazar = acciones.firstOrNull { rRechazar.containsMatchIn(it.title ?: "") }
        val entrante = tipo == 1 || (contestar != null && tipo != 2)
        if (!entrante) return true // llamada en curso: no se lee
        val quien = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            .ifBlank { "un número desconocido" }
        val ahora = System.currentTimeMillis()
        synchronized(anunciadas) {
            if ((anunciadas[sbn.key] ?: 0L) > ahora - 60_000) return true
            anunciadas[sbn.key] = ahora
            anunciadas.entries.removeIf { it.value < ahora - 600_000 }
        }
        if (Modos.actual == Modos.DORMIR || Modos.actual == Modos.REUNION) return true
        val e = Entrante(sbn.key, quien, app, sbn.packageName, contestar, rechazar)
        actual = e
        anunciar(ctx, e)
        return true
    }

    /** La notificación de la llamada desapareció: la contestaron, la colgaron o se perdió. */
    fun quitada(clave: String) {
        val e = actual ?: return
        if (e.clave != clave) return
        actual = null
        restaurarTimbre()
    }

    private fun anunciar(ctx: Context, e: Entrante) {
        // Baja el timbre mientras le pregunto, para poder escucharle
        silenciarTimbre(ctx)
        principal.postDelayed({ restaurarTimbre() }, 25_000)
        val tr = Prefs.trato
        val porApp = if (e.paquete.contains("dialer") || e.paquete.contains("telecom") || e.paquete.contains("phone") || e.paquete.contains("incallui")) "" else " por ${e.app}"
        val r = Yarvis.cerebro.preguntarAlUsuario("${tr.replaceFirstChar { it.uppercase() }}, le llama ${e.quien}$porApp. ¿Contesto?") { f -> decidir(ctx, e, f) }
        Yarvis.decirYEscuchar(r, urgente = true)
    }

    private val rSi = Regex("^(si|sip|claro|dale|ok|okay|contesta|contestale|contestala|contestar|responde|atiende|atiendela|pasamelo|pasamela|acepta|aceptala|de una|ya)\\b")
    private val rAltavoz = Regex("(altavoz|alta voz|parlante|manos libres)")
    private val rNo = Regex("^(no|rechaza|rechazala|cuelga|cuelgale|cortala|corta|ignora|ignorala|ahora no|despues|luego|que espere)\\b")
    private val rDile = Regex("(?:dile|decile|escribele|mandale(?: un mensaje)?|avisale)(?: que)?\\s+(.+)$")

    private fun decidir(ctx: Context, e: Entrante, f: com.velkyra.yarvis.Frase): Yarvis.Respuesta {
        val t = f.t
        val tr = Prefs.trato
        if (actual?.clave != e.clave) { restaurarTimbre(); return Yarvis.Respuesta("La llamada ya terminó, $tr.", cerrar = true) }
        val dile = rDile.find(t)
        return when {
            dile != null || (rNo.containsMatchIn(t) && t.contains("mensaje")) -> {
                rechazarAhora(ctx, e)
                val texto = dile?.let { f.originalDeGrupo(it, 1) } ?: "Ahora no puedo contestar, te llamo en un rato."
                Yarvis.cerebro.mensajeRapido(e.quien, texto, e.paquete)
            }
            rSi.containsMatchIn(t) || rAltavoz.containsMatchIn(t) -> {
                val ok = contestarAhora(ctx, e)
                if (ok && rAltavoz.containsMatchIn(t)) principal.postDelayed({ altavoz(ctx) }, 1500)
                if (ok) Yarvis.Respuesta("", cerrar = true) else Yarvis.Respuesta("No pude contestarla yo, $tr. Deslice para contestar.", cerrar = true)
            }
            rNo.containsMatchIn(t) -> {
                rechazarAhora(ctx, e)
                Yarvis.Respuesta("Rechazada, $tr.", cerrar = true)
            }
            else -> {
                restaurarTimbre()
                Yarvis.Respuesta("La dejo sonar, $tr.", cerrar = true)
            }
        }
    }

    private fun contestarAhora(ctx: Context, e: Entrante): Boolean {
        restaurarTimbre()
        actual = null
        try { e.contestar?.actionIntent?.send(); return true } catch (_: Exception) {}
        if (ctx.tiene(Manifest.permission.ANSWER_PHONE_CALLS)) try {
            @Suppress("DEPRECATION")
            ctx.getSystemService(TelecomManager::class.java).acceptRingingCall()
            return true
        } catch (_: Exception) {}
        return false
    }

    private fun rechazarAhora(ctx: Context, e: Entrante): Boolean {
        restaurarTimbre()
        actual = null
        try { e.rechazar?.actionIntent?.send(); return true } catch (_: Exception) {}
        if (ctx.tiene(Manifest.permission.ANSWER_PHONE_CALLS)) try {
            @Suppress("DEPRECATION")
            return ctx.getSystemService(TelecomManager::class.java).endCall()
        } catch (_: Exception) {}
        return false
    }

    private fun altavoz(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
        } catch (_: Exception) {}
    }

    private fun silenciarTimbre(ctx: Context) {
        if (timbreSilenciado) return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            timbreSilenciado = true
        } catch (_: Exception) {}
    }

    private fun restaurarTimbre() {
        if (!timbreSilenciado) return
        timbreSilenciado = false
        try {
            val am = Yarvis.ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) {}
    }
}
