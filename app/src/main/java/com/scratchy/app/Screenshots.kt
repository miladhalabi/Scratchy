package com.scratchy.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast

/** Finds the newest screenshot in the gallery. Needs photo access (READ_MEDIA_IMAGES). */
object Screenshots {
    class Shot(val uri: Uri, val id: Long, val addedSec: Long)

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasAccess(ctx: Context) =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun latest(ctx: Context): Shot? {
        if (!hasAccess(ctx)) return null
        val img = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val sel: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            sel = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("%screenshot%", "%Screenshots%")
        } else {
            sel = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            args = arrayOf("%screenshot%")
        }
        val q = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        return runCatching {
            ctx.contentResolver.query(
                img, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED), q, null
            )?.use {
                if (it.moveToFirst()) Shot(ContentUris.withAppendedId(img, it.getLong(0)), it.getLong(0), it.getLong(1))
                else null
            }
        }.getOrNull()
    }

    /** Attaches the newest screenshot to [editor]. Returns false when photo access is missing. */
    fun attachLatest(ctx: Context, editor: NoteEditor): Boolean {
        if (!hasAccess(ctx)) return false
        val shot = latest(ctx)
        if (shot == null) Toast.makeText(ctx, "No screenshot found", Toast.LENGTH_SHORT).show()
        else editor.addImage(shot.uri)
        return true
    }
}
