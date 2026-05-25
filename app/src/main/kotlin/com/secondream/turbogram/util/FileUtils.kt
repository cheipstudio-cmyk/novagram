package com.secondream.turbogram.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

object FileUtils {

    fun copyUriToCache(context: Context, uri: Uri, suggestedName: String? = null): File? {
        val name = suggestedName ?: queryFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}_$safeName")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }.onFailure { return null }
        return if (outFile.exists() && outFile.length() > 0) outFile else null
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
    }
}
