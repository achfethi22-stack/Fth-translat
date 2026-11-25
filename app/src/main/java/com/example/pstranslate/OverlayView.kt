package com.example.pstranslate

import android.content.Context
import android.graphics.*
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
        items = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawn.clear()
        for ((r, text) in items) {
            val pad = 14
            tp.textSize = fontSize
            val minW = (width * 0.4f).toInt()
            val w = maxOf(r.width() + pad * 2, minW).coerceAtMost(width)
            val innerW = w - pad * 2
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, innerW)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            val boxH = if (above) layout.height + pad else maxOf(layout.height + pad, r.height() + pad)
            val left = (r.centerX() - w / 2).coerceIn(0, maxOf(0, width - w))
            var top = if (above) r.top - boxH - 8 else r.top - pad / 2
            if (above && top < 0) top = r.bottom + 8
            top = top.coerceIn(0, maxOf(0, height - boxH))
            canvas.drawRect(left.toFloat(), top.toFloat(), (left + w).toFloat(), (top + boxH).toFloat(), bg)
            canvas.save()
            canvas.translate((left + pad).toFloat(), (top + pad / 2).toFloat())
            layout.draw(canvas)
            canvas.restore()
            drawn.add(Rect(left, top, left + w, top + boxH))
        }
    }
}
