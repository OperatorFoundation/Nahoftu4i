package org.nahoft.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

object SaveUtil
{
    const val PNG_MIME_TYPE = "image/png"

    // PNG is lossless, so this quality value is ignored by the encoder, but the
    // Bitmap.compress signature requires it.
    private const val PNG_COMPRESSION_QUALITY = 100

    // Cache subdirectory holding encoded PNGs briefly while the user picks a
    // save destination.
    private const val ENCODED_IMAGE_CACHE_SUBDIR = "encoded_saves"

    // Fallback base name when the cover image has no readable display name.
    private const val DEFAULT_IMAGE_BASE_NAME = "image"

    /**
     * Writes [bitmap] to a temporary PNG in the app cache and returns the file,
     * or null on failure. The caller is responsible for deleting the file once
     * it has been copied to its final destination.
     */
    fun stageEncodedPng(context: Context, bitmap: Bitmap): File?
    {
        return try
        {
            val cacheSubDir = File(context.cacheDir, ENCODED_IMAGE_CACHE_SUBDIR).apply { mkdirs() }
            val tempFile = File.createTempFile("encoded", ".png", cacheSubDir)

            FileOutputStream(tempFile).use { outputStream ->
                val written = bitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    PNG_COMPRESSION_QUALITY,
                    outputStream
                )
                if (!written)
                {
                    tempFile.delete()
                    return null
                }
            }

            tempFile
        }
        catch (e: Exception)
        {
            Timber.e(e, "stageEncodedPng: failed to write temporary PNG")
            null
        }
    }

    /**
     * Copies the already-encoded PNG in [sourceFile] to [destUri] byte-for-byte.
     * No re-encoding occurs, so the steganographic payload is preserved.
     */
    fun writePngToUri(context: Context, sourceFile: File, destUri: Uri): Boolean
    {
        if (!sourceFile.exists()) return false

        return try
        {
            context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
                sourceFile.inputStream().use { input -> input.copyTo(outputStream) }
                true
            } ?: false
        }
        catch (e: Exception)
        {
            Timber.e(e, "writePngToUri: failed to copy encoded PNG to destination")
            false
        }
    }

    /**
     * Builds a save-dialog default filename from the cover image's display name,
     * with a .png extension. Falls back to a generic name when the cover has no
     * readable display name.
     */
    fun suggestedPngName(context: Context, coverUri: Uri): String
    {
        val displayName = queryDisplayName(context, coverUri)
        val base = displayName
            ?.substringBeforeLast('.', displayName)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_IMAGE_BASE_NAME

        return "$base.png"
    }

    private fun queryDisplayName(context: Context, uri: Uri): String?
    {
        return try
        {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst())
                    {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    }
                    else null
                }
        }
        catch (e: Exception)
        {
            Timber.e(e, "queryDisplayName: failed to read cover display name")
            null
        }
    }

    /**
     * Returns the dedicated cache subdirectory that holds encoded PNGs (both the
     * Save As staging files and the share-path temp files from CapturePhotoUtils).
     * Creating it here keeps all encoded cache images in one sweepable location.
     */
    fun encodedCacheDir(context: Context): File =
        File(context.cacheDir, ENCODED_IMAGE_CACHE_SUBDIR).apply { mkdirs() }

    /**
     * Securely deletes every encoded PNG left in the cache, except the file at
     * [exceptPath] when non-null (the Save As temp currently awaiting a destination
     * pick, which must survive the sweep).
     *
     * Secure-overwrite caveat: on the flash storage these files live on, overwriting
     * a file's logical blocks does not guarantee the physical cells are erased
     * (wear-leveling). This raises the bar but is not a true unrecoverability
     * guarantee — the real mitigation is minimizing how long these files exist.
     */
    fun sweepEncodedCache(context: Context, exceptPath: String?)
    {
        val dir = File(context.cacheDir, ENCODED_IMAGE_CACHE_SUBDIR)
        if (!dir.isDirectory) return

        dir.listFiles()?.forEach { file ->
            if (file.absolutePath != exceptPath)
            {
                SecureDelete.secureDelete(file)
            }
        }
    }
}