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
    private val bg = Paint().apply { color = 0xE6000000.toInt() }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    fun hasItems() = items.isNotEmpty()

    fun setItems(list: List<Pair<Rect, String>>) {
        items = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for ((r, text) in items) {
            val w = maxOf(r.width(), 260).coerceAtMost(width)
            val left = r.left.coerceAtMost(width - w).coerceAtLeast(0)
            var size = 44f
            var layout: StaticLayout
            while (true) {
                tp.textSize = size
                layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, w)
                    .setTextDirection(TextDirectionHeuristics.RTL)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
                if (layout.height <= r.height() + 8 || size <= 20f) break
                size -= 2f
            }
            val h = maxOf(layout.height, r.height())
            canvas.drawRect(left.toFloat(), r.top.toFloat(), (left + w).toFloat(), (r.top + h).toFloat(), bg)
            canvas.save()
            canvas.translate(left.toFloat(), r.top.toFloat())
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
