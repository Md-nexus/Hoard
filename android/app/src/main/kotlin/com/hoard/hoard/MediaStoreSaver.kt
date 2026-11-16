package com.hoard.hoard

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Copies a finished file into the gallery under Movies/Hoard (or Pictures/Hoard). */
object MediaStoreSaver {

    data class Saved(val uri: Uri, val mime: String)

    fun save(ctx: Context, file: File): Saved {
        val mime = mimeFor(file)
        val isImage = mime.startsWith("image/")
        val collection = if (isImage)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val dir = if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Hoard")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return Saved(uri, mime)
    }

    private fun mimeFor(f: File): String = when (f.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "video/mp4"
    }
}
