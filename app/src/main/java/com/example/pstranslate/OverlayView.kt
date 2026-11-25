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
    
    // ✅ إزالة cornerRadius من Paint
    private val bg = Paint().apply { 
        color = 0xCC000000.toInt()
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
            val pad = 24f
            tp.textSize = fontSize
            
            val minW = (width * 0.6f).toInt()
            val w = max(r.width() + (pad * 2).toInt(), minW).coerceAtMost(width - 32)
            val innerW = w - (pad * 2).toInt()
            
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, innerW)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
                
            val boxH = layout.height.toFloat() + (pad * 2)
            val left = (width - w) / 2f
            var top = if (above) r.top.toFloat() - boxH - 12f else r.bottom.toFloat() + 12f
            if (above && top < 0f) top = r.bottom.toFloat() + 12f
            top = top.coerceIn(0f, height.toFloat() - boxH)
            
            // ✅ رسم مستطيل بحواف دائرية
            val rectF = RectF(left, top, left + w.toFloat(), top + boxH)
            canvas.drawRoundRect(rectF, 16f, 16f, bg) 
            
            canvas.save()
            canvas.translate(left + pad, top + pad)
            layout.draw(canvas)
            canvas.restore()
            
            drawn.add(Rect(left.toInt(), top.toInt(), (left + w).toInt(), (top + boxH).toInt()))
        }
    }
}
