package com.scratchy.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

/** Images attached to notes. Copied into app-private storage so they outlive the source URI. */
object Attachments {
    private const val MAX_SIDE = 1600
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun file(ctx: Context, name: String) = File(File(ctx.filesDir, "attachments").apply { mkdirs() }, name)

    fun delete(ctx: Context, name: String) {
        file(ctx, name).delete()
    }

    /** Copies and downsizes [uri] in the background; calls [done] on the main thread with the stored name, or null. */
    fun import(ctx: Context, uri: Uri, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val name = runCatching { importNow(app, uri) }.getOrNull()
            main.post { done(name) }
        }
    }

    private fun importNow(ctx: Context, uri: Uri): String {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE * 2) sample *= 2
        val raw = cr.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }!!
        val scale = MAX_SIDE.toFloat() / maxOf(raw.width, raw.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(
            raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true
        ) else raw
        val png = bmp.hasAlpha()
        val name = "att_${UUID.randomUUID()}.${if (png) "png" else "jpg"}"
        FileOutputStream(file(ctx, name)).use {
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it)
        }
        return name
    }

    /** Loads a small decoded copy for lists and strips. */
    fun thumb(ctx: Context, name: String, px: Int, onLoaded: (Bitmap?) -> Unit) {
        val key = "$name@$px"
        cache.get(key)?.let { onLoaded(it); return }
        val f = file(ctx, name)
        io.execute {
            val bmp = runCatching {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, b)
                var sample = 1
                while (minOf(b.outWidth, b.outHeight) / (sample * 2) >= px) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
            bmp?.let { cache.put(key, it) }
            main.post { onLoaded(bmp) }
        }
    }
}
