package com.velkyra.yarvis

import java.io.File

/** Pasa audio a texto (grabaciones de reuniones, audios de WhatsApp compartidos). */
object Transcriptor {
    fun transcribir(f: File, mime: String): String = CerebroIA.transcribirAudio(f, mime)
}
