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

    data class Notif(val clave: String, val app: String, val titulo: String, val texto: String, val hora: Long)

    companion object {
        @Volatile
        private var conectado: ServicioNotificaciones? = null
        private val lista = ArrayDeque<Notif>()
        private val ultimas = HashMap<String, Long>()

        val activo get() = conectado != null

        fun recientes(): List<Notif> = synchronized(lista) { lista.toList() }
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
        synchronized(lista) {
            lista.addLast(Notif(sbn.key, app, tituloLimpio, texto, ahora))
            while (lista.size > 30) lista.removeFirst()
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

        val activo get() = instancia != null

        fun prepararEnvio() { enviarHasta = System.currentTimeMillis() + 20_000 }

        fun accion(a: Int): Boolean = instancia?.performGlobalAction(a) ?: false

        fun permisoConcedido(ctx: Context): Boolean {
            val activos = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return activos.contains(ComponentName(ctx, ServicioAccesibilidad::class.java).flattenToString()) ||
                activos.contains(ctx.packageName + "/.ServicioAccesibilidad")
        }
    }

    override fun onServiceConnected() { instancia = this }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (System.currentTimeMillis() > enviarHasta) return
        val pkg = e?.packageName?.toString() ?: return
        if (!pkg.startsWith("com.whatsapp")) return
        val raiz = rootInActiveWindow ?: return
        val boton = raiz.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull { it.isEnabled }
            ?: buscarPorDescripcion(raiz, listOf("enviar", "send"))
        if (boton != null && boton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            enviarHasta = 0
        }
    }

    private fun buscarPorDescripcion(n: AccessibilityNodeInfo, textos: List<String>, profundidad: Int = 0): AccessibilityNodeInfo? {
        if (profundidad > 25) return null
        val d = n.contentDescription?.toString()?.lowercase().orEmpty()
        if (n.isClickable && textos.any { d == it }) return n
        for (i in 0 until n.childCount) {
            val h = n.getChild(i) ?: continue
            buscarPorDescripcion(h, textos, profundidad + 1)?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instancia === this) instancia = null
        super.onDestroy()
    }
}
