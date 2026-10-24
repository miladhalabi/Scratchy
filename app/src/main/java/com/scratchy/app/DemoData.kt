package com.scratchy.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.io.FileOutputStream

/**
 * Made-up notes for screenshots. Only runs in the separate demo build (package ends in ".demo"),
 * so the real app and its notes are never touched.
 */
object DemoData {
    private const val ON = "- [x]"
    private const val OFF = "- [ ]"

    fun seedIfDemo(ctx: Context) {
        if (!ctx.packageName.endsWith(".demo") || NoteStore.all(ctx).isNotEmpty()) return
        val h = 3_600_000L
        val now = System.currentTimeMillis()
        fun add(i: Int, title: String, body: String, cat: String, ago: Long, pinned: Boolean = false, img: String? = null) {
            val at = now - ago * h
            NoteStore.save(ctx, Note("demo-$i", title, body, at, at, pinned, listOfNotNull(img), false, 1, cat))
        }
        picture(ctx, "demo1.png", 0xFFFF9A62.toInt(), 0xFF6A3FA0.toInt())
        picture(ctx, "demo2.png", 0xFF48C6B3.toInt(), 0xFF1B4F72.toInt())
        add(1, "Weekend in Lisbon", "$ON Reserve the hostel\n$ON Train tickets\n$OFF Pastéis de Belém before 10\n$OFF Tram 28 early, it gets packed",
            "Travel", 2, pinned = true, img = "demo1.png")
        add(2, "Gift ideas for Sam", "$OFF Vinyl: Kind of Blue\n$ON Candle\n$OFF Hiking socks\n$OFF Book: The Overstory", "Shopping", 5)
        add(3, "Launch checklist", "$ON Final copy review\n$ON Screenshots\n$OFF Press kit\n$OFF Schedule the post for Tuesday 9:00", "Work", 20)
        add(4, "App idea: pocket garden", "A plant care app that nags you gently. Water streaks, a tiny forest that grows, and a widget with the one plant that needs you today.",
            "Ideas", 30, img = "demo2.png")
        add(5, "Carbonara", "Guanciale, 4 yolks, pecorino, lots of pepper.\nPasta water is the sauce. Off the heat before the eggs go in.", "Recipes", 50)
        add(6, "Reading list", "The Overstory\nBraiding Sweetgrass\nA Psalm for the Wild-Built\nPicnic at Hanging Rock", "Books", 70)
        add(7, "Morning routine", "$ON Water\n$ON Stretch 10 min\n$OFF No phone until 8:30\n$OFF Plan the day in 3 lines", "Health", 90)
        add(8, "Weekly review", "Wins: shipped the beta, finally booked the dentist.\nNext week: onboarding flow, taxes, call Mum.", "Work", 120)
    }

    private fun picture(ctx: Context, name: String, a: Int, b: Int) {
        val bmp = Bitmap.createBitmap(900, 560, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 900f, 560f, a, b, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 900f, 560f, p)
        p.shader = null; p.color = 0x33FFFFFF
        c.drawCircle(660f, 200f, 120f, p)
        p.color = 0x22000000
        c.drawCircle(180f, 520f, 260f, p)
        FileOutputStream(Attachments.file(ctx, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }
}
