package com.velkyra.yarvis

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Copia de seguridad de ODÍN: su voz registrada, la palabra entrenada, sus chats con estilo, sus apps,
 * notas, gastos, ajustes… todo en un archivo. Se guarda sola cada día en Descargas/ODIN y se puede
 * restaurar al reinstalar o en otro celular.
 */
object Respaldo {

    const val ARCHIVO = "odin-respaldo.json"
    private const val CARPETA = "ODIN"
    // Cosas que no conviene llevar a otro celular
    private val EXCLUIR = setOf("migr_voz_google", "migr_odin")

    /** Todo lo guardado por ODÍN, en JSON. */
    fun exportar(ctx: Context): String {
        val raiz = JSONObject().put("app", "ODIN").put("version", BuildConfig.VERSION_NAME).put("fecha", System.currentTimeMillis())
        val archivos = JSONObject()
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        for (f in dir.listFiles().orEmpty()) {
            if (!f.name.endsWith(".xml")) continue
            val nombre = f.name.removeSuffix(".xml")
            val o = JSONObject()
            for ((k, v) in ctx.getSharedPreferences(nombre, Context.MODE_PRIVATE).all) {
                if (k in EXCLUIR || v == null) continue
                val e = when (v) {
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is Set<*> -> JSONObject().put("t", "s").put("v", JSONArray(v.map { it.toString() }))
                    else -> JSONObject().put("t", "x").put("v", v.toString())
                }
                o.put(k, e)
            }
            archivos.put(nombre, o)
        }
        return raiz.put("datos", archivos).toString()
    }

    /** Restaura una copia. Devuelve cuántos datos restauró, o -1 si el archivo no es válido. */
    fun importar(ctx: Context, json: String): Int {
        val raiz = try { JSONObject(json) } catch (_: Exception) { return -1 }
        if (raiz.optString("app") != "ODIN") return -1
        val datos = raiz.optJSONObject("datos") ?: return -1
        var n = 0
        for (nombre in datos.keys()) {
            val o = datos.getJSONObject(nombre)
            val e = ctx.getSharedPreferences(nombre, Context.MODE_PRIVATE).edit()
            for (k in o.keys()) {
                val x = o.getJSONObject(k)
                when (x.optString("t")) {
                    "b" -> e.putBoolean(k, x.getBoolean("v"))
                    "i" -> e.putInt(k, x.getInt("v"))
                    "l" -> e.putLong(k, x.getLong("v"))
                    "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                    "s" -> { val a = x.getJSONArray("v"); e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                    else -> e.putString(k, x.optString("v"))
                }
                n++
            }
            e.commit()
        }
        return n
    }

    /** Guarda la copia en Descargas/ODIN (reemplaza la anterior). Devuelve true si pudo. */
    fun guardarEnDescargas(ctx: Context): Boolean = try {
        val json = exportar(ctx)
        val cr = ctx.contentResolver
        val coleccion = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // Reemplaza la copia anterior que ODÍN mismo guardó
        cr.query(coleccion, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf(ARCHIVO, "%$CARPETA%"), null)?.use { c ->
            while (c.moveToNext()) try { cr.delete(Uri.withAppendedPath(coleccion, c.getLong(0).toString()), null, null) } catch (_: Exception) {}
        }
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, ARCHIVO)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + CARPETA)
        }
        val uri = cr.insert(coleccion, v) ?: throw IllegalStateException("sin uri")
        cr.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: throw IllegalStateException("sin flujo")
        ctx.getSharedPreferences("yarvis", Context.MODE_PRIVATE).edit().putLong("respaldo_ultimo", System.currentTimeMillis()).apply()
        true
    } catch (_: Exception) { false }

    /** Copia automática, como máximo una vez al día. */
    fun automatico(ctx: Context) {
        val sp = ctx.getSharedPreferences("yarvis", Context.MODE_PRIVATE)
        if (!sp.getBoolean("configurado", false)) return
        if (System.currentTimeMillis() - sp.getLong("respaldo_ultimo", 0L) < 24 * 3_600_000L) return
        Thread { guardarEnDescargas(ctx) }.start()
    }

    fun escribir(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(exportar(ctx).toByteArray()) } != null
    } catch (_: Exception) { false }

    fun leer(ctx: Context, uri: Uri): Int = try {
        val txt = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return -1
        importar(ctx, txt)
    } catch (_: Exception) { -1 }

    /** Reinicia ODÍN para que use los datos restaurados. */
    fun reiniciar(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(i)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Runtime.getRuntime().exit(0) }, 400)
    }
}
