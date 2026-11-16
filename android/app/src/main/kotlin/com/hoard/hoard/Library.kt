package com.hoard.hoard

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import java.io.ByteArrayOutputStream

/** Reads what Hoard saved (Movies/Hoard and Pictures/Hoard) back out of MediaStore. */
object Library {

    fun list(ctx: Context): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        query(
            ctx, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "Movies/Hoard%", true, out
        )
        query(
            ctx, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "Pictures/Hoard%", false, out
        )
        return out.sortedByDescending { it["added"] as Long }
    }

    private fun query(
        ctx: Context, collection: Uri, pathLike: String, video: Boolean,
        out: MutableList<Map<String, Any?>>,
    ) {
        val cols = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        if (video) cols.add(MediaStore.Video.Media.DURATION)
        ctx.contentResolver.query(
            collection, cols.toTypedArray(),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(pathLike),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out.add(
                    mapOf(
                        "uri" to ContentUris.withAppendedId(collection, id).toString(),
                        "name" to c.getString(1),
                        "size" to c.getLong(2),
                        "added" to c.getLong(3),
                        "mime" to c.getString(4),
                        "isVideo" to video,
                        "durationMs" to if (video) c.getLong(5) else 0L,
                    )
                )
            }
        }
    }

    fun thumbnail(ctx: Context, uri: String, size: Int = 360): ByteArray? = try {
        val bmp = ctx.contentResolver.loadThumbnail(Uri.parse(uri), Size(size, size), null)
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, if (size > 512) 90 else 80, it) }
            .toByteArray()
    } catch (e: Exception) {
        null
    }

    /** Returns false if Android refuses (for example a file from a previous install). */
    fun delete(ctx: Context, uri: String): Boolean = try {
        ctx.contentResolver.delete(Uri.parse(uri), null, null) > 0
    } catch (e: SecurityException) {
        false
    }
}
