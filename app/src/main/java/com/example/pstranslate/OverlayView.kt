package com.example.pstranslate

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.View
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context) : View(context) {
    private var items: List<Pair<Rect, String>> = emptyList()
    val drawn = ArrayList<Rect>()
    var fontSize = 44f
    var above = true
    
    // خلفية النص (سوداء شفافة قليلاً لتسهيل القراءة)
    private val bg = Paint().apply { 
        color = 0xCC000000.toInt() // 0xCC تعني شفافية بنسبة 80%
        cornerRadius = 16f // حواف دائرية للمربع
    }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { 
        color = Color.WHITE 
        textAlign = Paint.Align.CENTER // محاذاة النص للمركز
    }

    fun hasItems() = items.isNotEmpty()

    fun setItems(list: List<Pair<Rect, String>>) {
        items = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawn.clear()
        for ((r, text) in items) {
            val pad = 24 // زيادة الحواشي لتبدو أفضل
            tp.textSize = fontSize
            
            // تحديد عرض المربع: نأخذ أكبر نص أو 60% من عرض الشاشة كحد أدنى
            val minW = (width * 0.6f).toInt()
            val w = max(r.width() + pad * 2, minW).coerceAtMost(width - 32) // ترك هامش 16 بكسل من كل جانب
            val innerW = w - pad * 2
            
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, innerW)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setAlignment(Layout.Alignment.ALIGN_CENTER) // ✅ محاذاة النص في المنتصف
                .build()
                
            val boxH = layout.height + pad * 2
            
            // حساب موقع المربع في منتصف الشاشة أفقياً
            val left = (width - w) / 2f
            
            // حساب موقع المربع عمودياً (فوق أو تحت النص الأصلي)
            var top = if (above) r.top - boxH - 12 else r.bottom + 12
            if (above && top < 0) top = r.bottom + 12
            top = top.coerceIn(0f, height - boxH.toFloat())
            
            // رسم الخلفية
            canvas.drawRect(left, top, left + w, top + boxH, bg)
            
            // رسم النص
            canvas.save()
            canvas.translate(left + pad, top + pad)
            layout.draw(canvas)
            canvas.restore()
            
            drawn.add(Rect(left.toInt(), top.toInt(), (left + w).toInt(), (top + boxH).toInt()))
        }
    }
}
