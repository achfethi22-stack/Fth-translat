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
import kotlin.math.max

class OverlayView(context: Context) : View(context) {
    private var items: List<Pair<Rect, String>> = emptyList()
    val drawn = ArrayList<Rect>()
    var fontSize = 44f
    var above = true
    
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
            
            // ✅ جعل المربع يأخذ 90% من عرض الشاشة دائماً (ليتسع للجمل الطويلة)
            val wF = width * 0.90f
            val w = wF.toInt()
            val innerW = w - (pad * 2f).toInt()
            
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, innerW)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
                
            val layoutH = layout.height.toFloat()
            val boxH = layoutH + (pad * 2f)
            
            // ✅ تثبيت المربع في منتصف الشاشة تماماً أفقياً
            val left = (width.toFloat() - wF) / 2f
            
            // تحديد الموقع العمودي (فوق أو تحت النص الأصلي)
            var top = if (above) r.top.toFloat() - boxH - 12f else r.bottom.toFloat() + 12f
            if (above && top < 0f) top = r.bottom.toFloat() + 12f
            top = top.coerceIn(0f, height.toFloat() - boxH)
            
            // رسم الخلفية
            val rectF = RectF(left, top, left + wF, top + boxH)
            canvas.drawRoundRect(rectF, 16f, 16f, bg) 
            
            // رسم النص
            canvas.save()
            canvas.translate(left + pad, top + pad)
            layout.draw(canvas)
            canvas.restore()
            
            drawn.add(Rect(left.toInt(), top.toInt(), (left + wF).toInt(), (top + boxH).toInt()))
        }
    }
}
