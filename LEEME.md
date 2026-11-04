# YARVIS · Asistente virtual para Android

Desarrollado por **VELKYRA** · Soporte: velkyra3@gmail.com

Asistente por voz con estilo holográfico. Se activa diciendo **«Yarvis»** (sin internet, incluso con el celular bloqueado), responde con voz y controla el celular: llamadas, WhatsApp manos libres, SMS, lectura de mensajes, recordatorios, agenda, alarmas, clima real, música, videos, cámara, linterna, volumen, brillo y más. Con inteligencia artificial (Google Gemini) para conversar y buscar información actual.

- Requiere Android 12 o superior.
- Descarga: pestaña **Releases** → `YARVIS.apk`.
- La clave de IA se guarda como secreto `GEMINI_API_KEY` en *Settings → Secrets and variables → Actions*.

## Estructura
| Archivo | Qué hace |
|---|---|
| `Yarvis.kt` | Centro de mando (activar, escuchar, procesar, responder) |
| `Cerebro.kt` | Todas las órdenes del celular |
| `CerebroIA.kt` | Conversación con Gemini + búsqueda en Google |
| `ServicioEscucha.kt` | Palabra «Yarvis» en segundo plano (Vosk, sin internet) |
| `ServiciosSistema.kt` | Lectura de notificaciones y accesibilidad (WhatsApp automático) |
| `Frase.kt` | Entiende fechas y horas en español |
| `MainActivity.kt`, `BienvenidaActivity.kt`, `AjustesActivity.kt` | Pantallas |
