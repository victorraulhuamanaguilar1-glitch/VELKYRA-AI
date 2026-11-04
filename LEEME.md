# VELKYRA AI: asistente virtual para Android

Es un asistente de voz con estilo futurista: le hablas, te responde en voz alta y hace cosas en tu celular.
Requiere Android 8.0 o superior.

## Qué puede hacer

| Dile… | Qué hace |
|---|---|
| "Hola" / "¿Quién eres?" / "¿Cómo estás?" | Conversa contigo |
| "¿Qué hora es?" / "¿Qué día es hoy?" | Hora y fecha |
| "Abre WhatsApp" / "Abre la cámara" / "Abre wifi" | Abre cualquier app instalada o ajuste |
| "Llama a mamá" / "Llama al 987654321" | Llama a un contacto o número |
| "Pon Daddy Yankee en YouTube" | Busca y reproduce en YouTube |
| "Busca recetas de trucha" | Busca en Google |
| "Llévame a la plaza de Azángaro" / "¿Dónde queda Juliaca?" | Abre Google Maps con la ruta |
| "Pon una alarma a las 6 y 30 de la mañana" | Programa una alarma |
| "Temporizador de 10 minutos" | Inicia un temporizador |
| "Enciende la linterna" / "Apaga la linterna" | Controla el flash |
| "Sube el volumen" / "Baja el volumen" / "Volumen al máximo" | Controla el volumen |
| "¿Cuánta batería tengo?" | Nivel de batería |
| "¿Cuánto es 125 por 8?" | Calculadora |
| "Recuérdame que mañana pague la luz" / "Mis notas" / "Borra mis notas" | Notas guardadas |
| "Cuéntame un chiste" | Humor |
| "¿Cómo está el clima?" | Pronóstico |
| "Adiós" | Se despide y se cierra |
| **Cualquier otra pregunta** | La responde la IA (si activas la clave) o la busca en Google |

También puedes **escribir** las órdenes en la caja de abajo.

## Cómo activar el cerebro de IA (gratis, opcional)

1. Entra a **https://aistudio.google.com** con tu cuenta de Google.
2. Toca **Get API key → Create API key** y copia la clave.
3. En la app toca el engranaje ⚙ (arriba a la derecha), pega la clave y guarda.

Con eso VELKYRA responde preguntas libres ("explícame qué es un CETPRO", "dame ideas para un video de terror"…) y recuerda la conversación.
Si algún día Google cambia el modelo, en ajustes puedes escribir otro nombre de modelo (por defecto: `gemini-2.5-flash`).

---

## Cómo generar el APK

### Opción A: con Android Studio (en tu PC)

1. Instala **Android Studio** (gratis): https://developer.android.com/studio
2. Descomprime el ZIP y en Android Studio elige **File → Open** y selecciona la carpeta `VelkyraAI`.
3. Espera a que termine de descargar lo necesario (la primera vez tarda varios minutos; necesita internet).
   Si te ofrece actualizar Gradle o el plugin de Android, puedes aceptar.
4. Menú **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
5. Cuando termine, toca **locate**: el archivo es `app/build/outputs/apk/debug/app-debug.apk`.
6. Pásalo a tu celular e instálalo (acepta "instalar apps de origen desconocido").

### Opción B: sin instalar nada, con GitHub (gratis)

1. Crea una cuenta en https://github.com y un repositorio nuevo (por ejemplo `velkyra-ai`).
2. Sube **todo el contenido** de la carpeta `VelkyraAI` (incluida la carpeta oculta `.github`).
3. Entra a la pestaña **Actions** del repositorio: se ejecuta "Generar APK" automáticamente (unos 5 minutos).
4. Al terminar, abre la ejecución y descarga **VELKYRA-AI-apk** en la sección *Artifacts*. Dentro está el APK.

## Primera vez en el celular

- Acepta los permisos de **micrófono**, **contactos** y **teléfono**.
- Necesitas la app **Google** instalada (es la que reconoce la voz).
- Para que la voz suene en español: *Ajustes → Sistema → Idioma → Salida de texto a voz → Motor de Google*, idioma español.
- Truco: en *Ajustes → Apps → Apps predeterminadas → Asistente digital* puedes elegir VELKYRA AI, y se abrirá manteniendo presionado el botón de inicio.

## Estructura del proyecto

```
app/src/main/java/com/velkyra/asistente/
├── MainActivity.kt   → pantalla, micrófono, voz, ajustes
├── Cerebro.kt        → entiende y ejecuta las órdenes (aquí agregas comandos nuevos)
├── CerebroIA.kt      → conexión con la IA de Google Gemini
└── HudView.kt        → el núcleo holográfico animado
```

Para cambiar el nombre del asistente, edita `app_name` en `res/values/strings.xml` y los textos "VELKYRA" en `Cerebro.kt`, `CerebroIA.kt` y `activity_main.xml`.
