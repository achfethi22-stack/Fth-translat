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
            // ✅ تحويل كل القيم إلى Float لتفادي أي التباس
            val pad = 24f
            tp.textSize = fontSize
            
            val rWidth = r.width().toFloat()
            val minWF = width * 0.6f
            val maxWF = (width - 32).toFloat()
            
            val wF = max(rWidth + (pad * 2f), minWF).coerceAtMost(maxWF)
            val w = wF.toInt()
            val innerW = w - (pad * 2f).toInt()
            
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, innerW)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
                
            val layoutH = layout.height.toFloat()
            val boxH = layoutH + (pad * 2f)
            
            val left = (width.toFloat() - wF) / 2f
            var top = if (above) r.top.toFloat() - boxH - 12f else r.bottom.toFloat() + 12f
            if (above && top < 0f) top = r.bottom.toFloat() + 12f
            top = top.coerceIn(0f, height.toFloat() - boxH)
            
            val rectF = RectF(left, top, left + wF, top + boxH)
            canvas.drawRoundRect(rectF, 16f, 16f, bg) 
            
            canvas.save()
            canvas.translate(left + pad, top + pad)
            layout.draw(canvas)
            canvas.restore()
            
            drawn.add(Rect(left.toInt(), top.toInt(), (left + wF).toInt(), (top + boxH).toInt()))
        }
    }
}
