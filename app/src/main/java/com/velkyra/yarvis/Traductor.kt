package com.velkyra.yarvis

import android.net.Uri
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Traductor: traducciones sueltas y «modo traductor» para conversar con alguien que habla otro idioma.
 * En el modo traductor los turnos se alternan: usted habla en español → ODÍN lo dice en el otro idioma;
 * la otra persona responde → ODÍN se lo dice en español.
 */
object Traductor {

    data class Idioma(val nombre: String, val codigo: String, val voz: String)

    val IDIOMAS = listOf(
        Idioma("inglés", "en", "en-US"), Idioma("portugués", "pt", "pt-BR"), Idioma("francés", "fr", "fr-FR"),
        Idioma("italiano", "it", "it-IT"), Idioma("alemán", "de", "de-DE"), Idioma("chino", "zh-CN", "zh-CN"),
        Idioma("japonés", "ja", "ja-JP"), Idioma("coreano", "ko", "ko-KR"), Idioma("ruso", "ru", "ru-RU"),
        Idioma("árabe", "ar", "ar-SA"), Idioma("hindi", "hi", "hi-IN"), Idioma("turco", "tr", "tr-TR"),
        Idioma("neerlandés", "nl", "nl-NL"), Idioma("polaco", "pl", "pl-PL")
    )

    fun idioma(dicho: String): Idioma? {
        val d = Frase.normalizar(dicho)
        return IDIOMAS.firstOrNull { d.contains(Frase.normalizar(it.nombre)) }
            ?: when {
                d.contains("brasil") -> IDIOMAS[1]
                d.contains("mandarin") -> IDIOMAS[5]
                d.contains("holand") -> IDIOMAS[12]
                d.contains("ingles") || d.contains("english") -> IDIOMAS[0]
                else -> null
            }
    }

    // --- Modo traductor -----------------------------------------------------------------

    @Volatile var activo: Idioma? = null
        private set
    /** true = ahora habla la otra persona (en su idioma). */
    @Volatile var turnoOtro = false
        private set

    fun iniciar(i: Idioma) { activo = i; turnoOtro = false }
    fun terminar() { activo = null; turnoOtro = false }

    /** Idioma en que hay que escuchar ahora (null = español). */
    fun idiomaEscucha(): String? = activo?.takeIf { turnoOtro }?.voz

    /** Traduce un turno de la conversación y prepara el siguiente. Se llama desde un hilo secundario. */
    fun turno(texto: String): Yarvis.Respuesta {
        val i = activo ?: return Yarvis.Respuesta("", cerrar = true)
        return if (!turnoOtro) {
            val tr = traducir(texto, "es", i.codigo) ?: return Yarvis.Respuesta("No pude traducir eso. Repítalo, por favor.", seguir = true)
            turnoOtro = true
            Yarvis.Respuesta(tr, seguir = true, idiomaVoz = i.voz)
        } else {
            val tr = traducir(texto, i.codigo, "es") ?: return Yarvis.Respuesta("I couldn't translate that. Please repeat.", seguir = true, idiomaVoz = i.voz)
            turnoOtro = false
            Yarvis.Respuesta(tr, seguir = true)
        }
    }

    // --- Traducción ------------------------------------------------------------------------

    /** Traduce con el traductor de Google (sin clave) y, si falla, con la IA. */
    fun traducir(texto: String, de: String, a: String): String? {
        try {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl=$de&tl=$a&q=" + Uri.encode(texto)
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            val j = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val partes = JSONArray(j).getJSONArray(0)
            val sb = StringBuilder()
            for (k in 0 until partes.length()) sb.append(partes.getJSONArray(k).optString(0))
            val r = sb.toString().trim()
            if (r.isNotBlank()) return r
        } catch (_: Exception) {}
        if (!CerebroIA.disponible()) return null
        val nombreA = if (a == "es") "español" else IDIOMAS.firstOrNull { it.codigo == a }?.nombre ?: a
        return try {
            CerebroIA.preguntar(texto, buscar = false, recordar = false,
                sistemaFijo = "Eres un traductor profesional. Traduce al $nombreA el texto del usuario, de forma natural. Responde SOLO con la traducción, sin comillas ni notas.")
                .trim().trim('"', '«', '»').ifBlank { null }
        } catch (_: Exception) { null }
    }
}
