package com.newtermux.pi

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Anexos: imagem vai embutida em base64 no prompt (o Pi "enxerga" a imagem);
 * documento vai copiado para o armazenamento compartilhado e o Pi le o
 * arquivo pelo caminho (assim o tool `read` do Pi resolve).
 */
object PiAttach {

    private const val TAG = "NebulaPi"
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    fun mimeOf(context: Context, uri: Uri): String {
        val type = context.contentResolver.getType(uri)
        if (!type.isNullOrEmpty()) return type
        return when (extensionOf(uri)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            else -> "application/octet-stream"
        }
    }

    fun isImage(mime: String): Boolean = mime.startsWith("image/")

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrEmpty()) return name
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "nao consegui ler o nome do arquivo", e)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "arquivo"
    }

    fun readBytes(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "falha ao ler o anexo", e)
            null
        }
    }

    fun toBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /**
     * Copia o arquivo para /storage/emulated/0/Download/nebula-pi-attach/.
     * O Pi roda no ambiente Termux real e le esse caminho.
     */
    fun copyToShared(context: Context, uri: Uri, name: String): String? {
        return try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(downloads, "nebula-pi-attach")
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "nao consegui criar ${dir.absolutePath}")
                return null
            }
            val target = File(dir, sanitize(name))
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            Log.i(TAG, "anexo copiado para ${target.absolutePath}")
            target.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "falha ao copiar o anexo", e)
            null
        }
    }

    private fun sanitize(name: String): String {
        val clean = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (clean.isBlank()) "arquivo" else clean.take(120)
    }

    private fun extensionOf(uri: Uri): String {
        val name = uri.lastPathSegment ?: return ""
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot + 1).lowercase() else ""
    }
}
