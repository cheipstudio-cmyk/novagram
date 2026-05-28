package com.secondream.novamessenger.util

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

    /**
     * Copy a local TDLib file into the public Downloads directory under a
     * `Nova/media` or `Nova/file` subfolder via MediaStore.
     * Uses Q+ scoped storage on Android 10+ (the only path that works
     * without WRITE_EXTERNAL_STORAGE), and falls back to direct File
     * copy + a single MediaScanner notify on older releases.
     *
     * Returns true on success. Failure is best-effort silent — the caller
     * shows a toast keyed off the boolean.
     */
    fun saveToDownloads(
        context: android.content.Context,
        sourcePath: String,
        displayName: String,
        mimeType: String,
        category: SaveCategory
    ): Boolean {
        val src = java.io.File(sourcePath)
        if (!src.exists()) return false
        val subdir = when (category) {
            SaveCategory.Media -> "Nova/media"
            SaveCategory.File -> "Nova/file"
        }
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = android.provider.MediaStore.Downloads
                    .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(
                        android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/" + subdir
                    )
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: return@runCatching false
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: return@runCatching false
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                // Legacy path: dump into Downloads/Nova/<sub>/ directly,
                // ping MediaScanner so gallery/file managers pick it up.
                @Suppress("DEPRECATION")
                val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val targetDir = java.io.File(downloads, subdir).apply { mkdirs() }
                val dest = java.io.File(targetDir, displayName)
                src.inputStream().use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(dest.absolutePath), arrayOf(mimeType), null
                )
                true
            }
        }.getOrDefault(false)
    }

    enum class SaveCategory { Media, File }

    /**
     * Downscale + re-encode an image for chat upload. Decodes bounds
     * first to compute an integer sample size (cheap, avoids decoding the
     * full bitmap into memory), loads the downsampled bitmap, scales it
     * so the longest edge is <= [maxEdge], and writes JPEG at [quality]
     * to a new cache file. Returns the compressed file, or null on any
     * failure so the caller can fall back to the original.
     */
    fun compressImageForUpload(
        source: java.io.File,
        maxEdge: Int = 1600,
        quality: Int = 82
    ): java.io.File? = runCatching {
        val path = source.absolutePath
        // Pass 1: bounds only.
        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        val (w, h) = bounds.outWidth to bounds.outHeight
        if (w <= 0 || h <= 0) return@runCatching null
        // Compute power-of-two sample size to get near maxEdge.
        var sample = 1
        val longest = maxOf(w, h)
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
        }
        val decoded = android.graphics.BitmapFactory.decodeFile(path, opts)
            ?: return@runCatching null
        // Fine scale to exactly fit maxEdge if still larger.
        val scale = maxEdge.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { if (it != decoded) decoded.recycle() }
        } else decoded
        val out = java.io.File(
            source.parentFile,
            "cmp_${System.currentTimeMillis()}.jpg"
        )
        java.io.FileOutputStream(out).use { fos ->
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, fos)
        }
        scaled.recycle()
        if (out.exists() && out.length() > 0) out else null
    }.getOrNull()
}
