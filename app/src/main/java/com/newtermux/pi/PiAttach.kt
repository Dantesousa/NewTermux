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
 * Attachments: the image is embedded as base64 in the prompt (Pi "sees" the image);
 * the document is copied to shared storage and Pi reads the
 * file by path (so Pi's `read` tool can resolve it).
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
            Log.w(TAG, "could not read the file name", e)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun readBytes(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "failed to read the attachment", e)
            null
        }
    }

    fun toBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /**
     * Copies the file to /storage/emulated/0/Download/nebula-pi-attach/.
     * Pi runs in the real Termux environment and reads that path.
     */
    fun copyToShared(context: Context, uri: Uri, name: String): String? {
        return try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(downloads, "nebula-pi-attach")
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "could not create ${dir.absolutePath}")
                return null
            }
            val target = File(dir, sanitize(name))
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            Log.i(TAG, "attachment copied to ${target.absolutePath}")
            target.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "failed to copy the attachment", e)
            null
        }
    }

    /**
     * Files for the @ mention in the input: looks in Download and in the
     * attachments folder, filters by name. No recursion (fast enough for the UI thread).
     */
    fun listMentionFiles(query: String, limit: Int = 8): List<File> {
        return try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dirs = listOf(File(downloads, "nebula-pi-attach"), downloads)
            val q = query.trim().lowercase()
            val out = mutableListOf<File>()
            for (dir in dirs) {
                val files = dir.listFiles() ?: continue
                for (f in files) {
                    if (!f.isFile || !f.canRead()) continue
                    if (q.isEmpty() || f.name.lowercase().contains(q)) out.add(f)
                    if (out.size >= limit * 2) break
                }
                if (out.size >= limit * 2) break
            }
            out.sortedBy { it.name.lowercase() }.take(limit)
        } catch (e: Exception) {
            Log.w(TAG, "could not list files for the @ mention", e)
            emptyList()
        }
    }

    private fun sanitize(name: String): String {
        val clean = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (clean.isBlank()) "file" else clean.take(120)
    }

    /**
     * Preview of a document: first lines, to show in the attachment chip.
     * Only tries text files (binaries become null).
     */
    fun previewOf(path: String?, maxChars: Int = 220): String? {
        if (path.isNullOrBlank()) return null
        return try {
            val file = File(path)
            if (!file.exists() || file.length() > 4L * 1024 * 1024) return null
            val text = file.readText(Charsets.UTF_8)
            // Heuristic: too many control bytes means it is not text.
            val control = text.count { it.code < 9 || (it.code in 11..12) || (it.code in 14..31) }
            if (control > text.length / 20) return null
            val clean = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(6)
                .joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (clean.isEmpty()) null else clean.take(maxChars)
        } catch (e: Exception) {
            Log.w(TAG, "could not generate the document preview", e)
            null
        }
    }

    private fun extensionOf(uri: Uri): String {
        val name = uri.lastPathSegment ?: return ""
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot + 1).lowercase() else ""
    }
}
