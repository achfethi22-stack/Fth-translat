package com.example.pstranslate

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.View
import kotlin.math.max

class OverlayView(context: Context) : View(context) {
    private var items: List<Pair<Rect, String>> = emptyList()
    val drawn = ArrayList<Rect>()
    var fontSize = 44f
    var above = true
    
    private val bg = Paint().apply { 
        color = 0xCC000000.toInt()
        cornerRadius = 16f
    }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { 
        color = Color.WHITE 
        textAlign = Paint.Align.CENTER
    }

    fun hasItems() = items.isNotEmpty()

    fun setItems(list: List<Pair<Rect, String>>) {
        items = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawn.clear()
        for ((r, text) in items) {
            val pad = 24f // ✅ تحويل إلى Float
            tp.textSize = fontSize
            
            val minW = (width * 0.6f).toInt()
            val w = max(r.width() + (pad * 2).toInt(), minW).coerceAtMost(width - 32)
            val innerW = w - (pad * 2).toInt()
            
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, innerW)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
                
            val boxH = layout.height + (pad * 2).toInt()
            
            // ✅ ضمان أن كل القيم من نوع Float
            val left = (width - w) / 2f
            var top = if (above) r.top - boxH - 12f else r.bottom + 12f
            if (above && top < 0f) top = r.bottom + 12f
            top = top.coerceIn(0f, height - boxH.toFloat())
            
            canvas.drawRect(left, top, left + w, top + boxH, bg)
            
            canvas.save()
            canvas.translate(left + pad, top + pad)
            layout.draw(canvas)
            canvas.restore()
            
            // ✅ تحويل القيم إلى Int هنا فقط عند الحفظ في المصفوفة
            drawn.add(Rect(left.toInt(), top.toInt(), (left + w).toInt(), (top + boxH).toInt()))
        }
    }
}
