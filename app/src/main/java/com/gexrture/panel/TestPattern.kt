package com.gexrture.panel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * 16:9 test card for M2: numbered grid (clipping), circle (aspect distortion), crosshair
 * (centre), labelled corners. Dark grey fill because black is see-through on the glasses.
 */
object TestPattern {
    const val WIDTH = 1600
    const val HEIGHT = 900

    fun create(): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(28, 30, 36))

        val grid = Paint().apply { color = Color.rgb(90, 96, 110); strokeWidth = 2f }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 176, 190); textSize = 26f }
        val cell = 100
        for (x in 0..WIDTH step cell) c.drawLine(x.toFloat(), 0f, x.toFloat(), HEIGHT.toFloat(), grid)
        for (y in 0..HEIGHT step cell) c.drawLine(0f, y.toFloat(), WIDTH.toFloat(), y.toFloat(), grid)
        for (row in 0 until HEIGHT / cell) for (col in 0 until WIDTH / cell) {
            c.drawText("${'A' + row}${col + 1}", col * cell + 8f, row * cell + 32f, text)
        }

        val cx = WIDTH / 2f
        val cy = HEIGHT / 2f
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.rgb(80, 200, 255)
        }
        c.drawCircle(cx, cy, 300f, circle)

        val cross = Paint().apply { color = Color.rgb(255, 80, 80); strokeWidth = 6f }
        c.drawLine(cx - 60, cy, cx + 60, cy, cross)
        c.drawLine(cx, cy - 60, cx, cy + 60, cross)

        val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 12f; color = Color.rgb(255, 200, 60) }
        c.drawRect(6f, 6f, WIDTH - 6f, HEIGHT - 6f, border)

        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 56f; isFakeBoldText = true }
        c.drawText("TL", 30f, 90f, big)
        c.drawText("TR", WIDTH - 110f, 90f, big)
        c.drawText("BL", 30f, HEIGHT - 40f, big)
        c.drawText("BR", WIDTH - 110f, HEIGHT - 40f, big)

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 44f; textAlign = Paint.Align.CENTER
        }
        c.drawText("GeXRture M2 test panel · 16:9 · circle must look round", cx, cy + 380f, title)
        return bmp
    }
}
