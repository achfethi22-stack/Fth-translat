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
    private val bg = Paint().apply { color = 0xFF000000.toInt() }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    fun hasItems() = items.isNotEmpty()

    fun setItems(list: List<Pair<Rect, String>>) {
        items = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for ((r, text) in items) {
            val pad = 12
            val w = maxOf(r.width() + pad * 2, 300).coerceAtMost(width)
            val left = (r.left - pad).coerceAtMost(width - w).coerceAtLeast(0)
            val innerW = w - pad * 2
            var size = 44f
            var layout: StaticLayout
            while (true) {
                tp.textSize = size
                layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, innerW)
                    .setTextDirection(TextDirectionHeuristics.RTL)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
                if (layout.height <= r.height() + pad || size <= 20f) break
                size -= 2f
            }
            val boxH = maxOf(layout.height + pad, r.height() + pad)
            val top = (r.top - pad / 2).coerceAtLeast(0)
            canvas.drawRect(left.toFloat(), top.toFloat(), (left + w).toFloat(), (top + boxH).toFloat(), bg)
            canvas.save()
            canvas.translate((left + pad).toFloat(), (top + pad / 2).toFloat())
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
