package com.example.pstranslate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.View

class OverlayView(context: Context) : View(context) {
    private var items: List<Pair<Rect, String>> = emptyList()
    val drawn = ArrayList<Rect>()
    var fontSize = 44f
    var above = true

    private val bg = Paint().apply { color = 0xFF000000.toInt() }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    fun hasItems() = items.isNotEmpty()

    fun setItems(list: List<Pair<Rect, String>>) {
        items = list.sortedBy { p -> p.first.top }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawn.clear()
        val gap = 10f
        var lastBottom = -1000f
        for ((r, text) in items) {
            val pad = 24f
            tp.textSize = fontSize

            val wF = width * 0.90f
            val innerW = (wF - pad * 2f).toInt()

            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, innerW)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()

            val layoutH = layout.height.toFloat()
            val boxH = if (above) layoutH + pad * 2f else maxOf(layoutH + pad * 2f, r.height() + pad * 2f)
            val left = (width.toFloat() - wF) / 2f

            var top = if (above) r.top.toFloat() - boxH - 12f else r.centerY() - boxH / 2f
            if (above && top < 0f) top = r.bottom.toFloat() + 12f
            if (top < lastBottom + gap) top = lastBottom + gap
            top = top.coerceIn(0f, maxOf(0f, height.toFloat() - boxH))
            lastBottom = top + boxH

            canvas.drawRoundRect(RectF(left, top, left + wF, top + boxH), 16f, 16f, bg)

            canvas.save()
            canvas.translate(left + pad, top + pad)
            layout.draw(canvas)
            canvas.restore()

            drawn.add(Rect(left.toInt(), top.toInt(), (left + wF).toInt(), (top + boxH).toInt()))
        }
    }
}
