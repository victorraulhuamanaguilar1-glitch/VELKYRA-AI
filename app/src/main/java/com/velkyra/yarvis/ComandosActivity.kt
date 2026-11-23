package com.velkyra.yarvis

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout

/** Lista de todo lo que YARVIS puede hacer. Tocar un ejemplo lo ejecuta. */
class ComandosActivity : Base() {

    private val categorias = linkedMapOf(
        "Conversación e IA" to listOf("¿Quién descubrió América?", "Explícame qué es la inteligencia artificial", "Traduce «buenos días» al inglés", "Busca las noticias de hoy", "Cuéntame un chiste", "Llámame jefe"),
        "Llamadas y mensajes" to listOf("Llama a mamá", "Manda un WhatsApp a Juan que llego en diez minutos", "Envía un SMS a Pedro diciendo que ya salí", "Lee mis mensajes", "Llama al 987654321"),
        "Agenda y recordatorios" to listOf("Recuérdame mañana a las 8 llamar a la UGEL", "Recuérdame en 20 minutos sacar el pan", "Agenda reunión el viernes a las 3 de la tarde", "¿Qué tengo hoy en mi agenda?", "Mis recordatorios", "Anota que debo comprar tinta"),
        "Alarmas y tiempo" to listOf("Pon una alarma a las 6 y 30 de la mañana", "Temporizador de 5 minutos", "¿Qué hora es?", "¿Qué día es hoy?", "¿Cuántos días faltan para Navidad?"),
        "Clima y lugares" to listOf("¿Cómo está el clima?", "¿Va a llover mañana?", "Clima en Juliaca", "¿Dónde estoy?", "Llévame a la plaza de armas de Puno", "Farmacias cerca"),
        "Música y videos" to listOf("Reproduce música andina en YouTube", "Pon Daddy Yankee", "Busca recetas de trucha en TikTok", "Pausa la música", "Siguiente canción", "Pon Shakira en Spotify"),
        "Celular" to listOf("Abre WhatsApp", "Enciende la linterna", "Sube el volumen", "Volumen al 50 por ciento", "¿Cuánta batería tengo?", "Modo vibración", "Activa no molestar", "Sube el brillo", "Abre el wifi", "Toma una selfie", "Abre la página velkyra.com"),
        "Manos libres (accesibilidad)" to listOf("Bloquea el celular", "Toma una captura de pantalla", "Ve al inicio", "Atrás", "Apps recientes"),
        "Mi día" to listOf("Buenos días", "¿Qué me escribió Juan?", "Resúmeme los mensajes de hoy", "¿Tengo correos nuevos?", "Lee el correo de Juan", "Manda un correo a Juan diciendo que mañana le envío el informe"),
        "Llamadas que entran" to listOf("(Cuando suene) Contesta", "Contesta en altavoz", "Recházala", "Dile que lo llamo luego"),
        "Ver y leer" to listOf("¿Qué ves?", "¿Qué planta es esta?", "Lee este documento", "Traduce este cartel", "Léeme la pantalla", "Resume esta página", "¿Qué hay en mi pantalla?"),
        "Traductor" to listOf("Modo traductor al inglés", "¿Cómo se dice «dónde está el baño» en inglés?", "Termina la traducción"),
        "Modos" to listOf("Modo manejo", "Modo dormir", "Modo reunión", "Modo normal"),
        "Gastos" to listOf("Gasté 20 soles en pasajes", "Me pagaron 500 soles por el trabajo", "¿Cuánto gasté esta semana?", "¿Cuánto gasté en comida este mes?", "Borra el último gasto"),
        "Encontrar el celular y copias" to listOf("¿Dónde estás?", "Activa los aplausos", "Haz una copia de seguridad"),
        "Control de ODÍN" to listOf("Deja de escuchar", "Activa la escucha", "Cancela", "Adiós")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cuerpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cuerpo.addView(texto("Diga «${Prefs.palabraActivacion}» y luego cualquiera de estas órdenes, o toque una para probarla.", 16f, Ui.TEXTO_SUAVE))
        for ((cat, ejemplos) in categorias) {
            val t = tarjeta(cat)
            for (e in ejemplos) {
                t.addView(texto("›  $e", 17f, Ui.TEXTO).apply {
                    setPadding(0, dp(9), 0, dp(9))
                    setOnClickListener {
                        startActivity(Intent(this@ComandosActivity, MainActivity::class.java).putExtra("orden", e).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    }
                })
            }
            cuerpo.agregar(t, arriba = dp(14))
        }
        montar(pantallaConTitulo("¿Qué puedo hacer?", cuerpo))
    }
}
