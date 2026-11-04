package com.velkyra.yarvis

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Lee en voz alta los mensajes que llegan (WhatsApp, Telegram, SMS…). */
class ServicioNotificaciones : NotificationListenerService() {

    data class Notif(
        val clave: String, val app: String, val titulo: String, val texto: String, val hora: Long,
        val paquete: String = "", val respuesta: Notification.Action? = null
    )

    companion object {
        @Volatile
        private var conectado: ServicioNotificaciones? = null
        private val lista = ArrayDeque<Notif>()
        private val ultimas = HashMap<String, Long>()

        val activo get() = conectado != null

        fun recientes(): List<Notif> = synchronized(lista) { lista.toList() }

        /** Mensajes que se pueden responder directamente (sin abrir la app). */
        private val respondibles = ArrayDeque<Notif>()

        fun ultimoRemitente(): String? = synchronized(respondibles) { respondibles.lastOrNull()?.titulo }

        /**
         * Responde el último mensaje de [quien] (o el último que llegó, si es null) usando la acción
         * «Responder» de la notificación. Devuelve el nombre del contacto, o null si no encontró ninguno.
         */
        fun responder(ctx: Context, quien: String?, texto: String): String? {
            val n = synchronized(respondibles) {
                if (quien.isNullOrBlank()) respondibles.lastOrNull()
                else {
                    val q = Frase.normalizar(quien)
                    respondibles.lastOrNull { Frase.normalizar(it.titulo).let { t -> t.contains(q) || q.contains(t) } }
                }
            } ?: return null
            val accion = n.respuesta ?: return null
            val entradas = accion.remoteInputs ?: return null
            val datos = android.os.Bundle()
            for (ri in entradas) datos.putCharSequence(ri.resultKey, texto)
            val intent = android.content.Intent()
            android.app.RemoteInput.addResultsToIntent(entradas, intent, datos)
            return try {
                accion.actionIntent.send(ctx, 0, intent)
                n.titulo
            } catch (_: Exception) {
                null
            }
        }
        fun marcarLeidas() = synchronized(lista) { lista.clear() }

        fun permisoConcedido(ctx: Context): Boolean {
            val activos = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            return activos.contains(ComponentName(ctx, ServicioNotificaciones::class.java).flattenToString())
        }
    }

    override fun onListenerConnected() { conectado = this }
    override fun onListenerDisconnected() { if (conectado === this) conectado = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            procesar(sbn)
        } catch (_: Exception) {
        }
    }

    private fun procesar(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val titulo = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val texto = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (titulo.isBlank() || texto.isBlank()) return

        val app = nombreApp(sbn.packageName)
        if (titulo.equals(app, true) && Regex("(mensajes? nuevos?|new messages?|buscando|checking)", RegexOption.IGNORE_CASE).containsMatchIn(texto)) return

        // Evitar repetir la misma notificación (WhatsApp la actualiza varias veces)
        val firma = "${sbn.packageName}|$titulo|$texto"
        val ahora = System.currentTimeMillis()
        synchronized(ultimas) {
            val previa = ultimas[firma]
            if (previa != null && ahora - previa < 120_000) return
            ultimas[firma] = ahora
            if (ultimas.size > 200) ultimas.entries.removeIf { ahora - it.value > 600_000 }
        }

        val esMensaje = sbn.packageName in Prefs.APPS_MENSAJERIA || n.category == Notification.CATEGORY_MESSAGE
        if (!esMensaje && !Prefs.leerTodasLasApps) return

        val tituloLimpio = titulo.replace(Regex("\\s*\\(\\d+ mensajes?\\)"), "")
        // Acción «Responder» (la que permite contestar desde la notificación)
        val responder = (n.actions?.toList().orEmpty() + Notification.WearableExtender(n).actions)
            .firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val item = Notif(sbn.key, app, tituloLimpio, texto, ahora, sbn.packageName, responder)
        synchronized(lista) {
            lista.addLast(item)
            while (lista.size > 30) lista.removeFirst()
        }
        if (responder != null) synchronized(respondibles) {
            respondibles.removeAll { it.titulo == tituloLimpio && it.paquete == sbn.packageName }
            respondibles.addLast(item)
            while (respondibles.size > 30) respondibles.removeFirst()
        }

        if (!Prefs.leerMensajes || !puedeHablar()) return
        val frase = if (esMensaje) "${Prefs.trato.replaceFirstChar { it.uppercase() }}, mensaje de $tituloLimpio por $app: ${texto.take(220)}"
        else "Notificación de $app: $tituloLimpio. ${texto.take(160)}"
        Yarvis.anunciar(frase)
    }

    private fun puedeHablar(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return false
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_RINGTONE) return false
        if (am.ringerMode == AudioManager.RINGER_MODE_SILENT) return false
        return true
    }

    private fun nombreApp(pkg: String): String = when (pkg) {
        "com.whatsapp" -> "WhatsApp"
        "com.whatsapp.w4b" -> "WhatsApp Business"
        "com.facebook.orca", "com.facebook.mlite" -> "Messenger"
        "org.telegram.messenger" -> "Telegram"
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms" -> "mensaje de texto"
        else -> try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }
    }
}

/** Accesibilidad: envía mensajes de WhatsApp solo, bloquea la pantalla, capturas, inicio, atrás. */
class ServicioAccesibilidad : AccessibilityService() {

    companion object {
        @Volatile
        private var instancia: ServicioAccesibilidad? = null
        @Volatile
        private var enviarHasta = 0L
        @Volatile
        private var llamarHasta = 0L
        @Volatile
        private var reproducirHasta = 0L

        val activo get() = instancia != null

        fun prepararEnvio() { enviarHasta = System.currentTimeMillis() + 20_000 }
        fun prepararLlamadaWhatsapp() { llamarHasta = System.currentTimeMillis() + 20_000 }
        fun prepararReproduccion() { reproducirHasta = System.currentTimeMillis() + 12_000 }

        fun accion(a: Int): Boolean = instancia?.performGlobalAction(a) ?: false

        fun permisoConcedido(ctx: Context): Boolean {
            val activos = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return activos.contains(ComponentName(ctx, ServicioAccesibilidad::class.java).flattenToString()) ||
                activos.contains(ctx.packageName + "/.ServicioAccesibilidad")
        }
    }

    override fun onServiceConnected() { instancia = this }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val ahora = System.currentTimeMillis()
        if (ahora > enviarHasta && ahora > llamarHasta && ahora > reproducirHasta) return
        val pkg = e?.packageName?.toString() ?: return
        val raiz = rootInActiveWindow ?: return
        if (pkg.startsWith("com.whatsapp")) {
            if (ahora < enviarHasta) {
                val boton = raiz.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull { it.isEnabled }
                    ?: buscarPorDescripcion(raiz, listOf("enviar", "send"))
                if (boton != null && boton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) enviarHasta = 0
            }
            if (ahora < llamarHasta) {
                val boton = buscarPorDescripcion(raiz, listOf("llamada", "llamar", "llamada de voz", "voice call", "call"), parcial = true)
                if (boton != null && boton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) llamarHasta = 0
            }
        }
        if (ahora < reproducirHasta && (pkg.contains("youtube") || pkg.contains("facebook"))) {
            val boton = buscarPorDescripcion(raiz, listOf("reproducir", "play video", "play"), parcial = false)
            if (boton != null && boton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) reproducirHasta = 0
        }
    }

    private fun buscarPorDescripcion(n: AccessibilityNodeInfo, textos: List<String>, profundidad: Int = 0, parcial: Boolean = false): AccessibilityNodeInfo? {
        if (profundidad > 30) return null
        val d = n.contentDescription?.toString()?.lowercase().orEmpty()
        if (n.isClickable && n.isEnabled && textos.any { if (parcial) d.startsWith(it) else d == it }) return n
        for (i in 0 until n.childCount) {
            val h = n.getChild(i) ?: continue
            buscarPorDescripcion(h, textos, profundidad + 1, parcial)?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instancia === this) instancia = null
        super.onDestroy()
    }
}
