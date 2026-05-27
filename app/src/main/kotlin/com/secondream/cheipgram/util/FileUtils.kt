package com.secondream.cheipgram.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
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

    /**
     * Open a downloaded TDLib file with the system's default viewer for its
     * MIME type. TDLib stores files under the app's private files-dir
     * (filesDir/tdlib_files/...), so we route the local path through our
     * FileProvider — the authority is `${applicationId}.fileprovider` and
     * file_paths.xml exposes the whole files dir — and grant read access on
     * the resulting content:// Uri.
     *
     * Returns true if a viewer accepted the intent, false otherwise (in
     * which case we surface a toast so the user knows the tap registered
     * but no app could handle the file).
     */
    fun openDocument(
        context: Context,
        path: String,
        mimeType: String?,
        displayName: String? = null
    ): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        val authority = "${context.packageName}.fileprovider"
        val uri = runCatching {
            FileProvider.getUriForFile(context, authority, file)
        }.getOrElse { return false }
        val effectiveMime = mimeType?.takeIf { it.isNotBlank() } ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, effectiveMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!displayName.isNullOrBlank()) {
                putExtra(Intent.EXTRA_TITLE, displayName)
            }
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            // No app installed that handles this MIME type. Surface a brief
            // toast — silent failure on a tap is confusing.
            runCatching {
                Toast.makeText(context, "Nessuna app per aprire questo file", Toast.LENGTH_SHORT).show()
            }
            false
        }
    }
}
