package com.example.pstranslate

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.*
import java.util.concurrent.Executors
import kotlin.math.max

class TranslateService : Service() {
    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_SELECT = "select"
        @Volatile var isRunning = false

        // ✅ تحديث نماذج Gemini
        val GEMINI_MODELS = listOf("gemini-3.8-flash", "gemini-3.6-flash")
        const val MAX_SIDE = 1280f
        const val MIN_API_GAP_GEMINI = 1200L

        const val SYSTEM = "You translate on-screen video game text into Arabic. The user message is a JSON array of strings. Reply with ONLY a JSON array of the Arabic translations, same length and same order. Keep character names, numbers and button names (X, O, L1, R2...) unchanged. Be short and natural. No explanations, no code fences."
    }

    private val handler = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private val udp = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var overlay: OverlayView? = null
    private var selector: SelectView? = null
    private var translator: Translator? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val cache = object : LinkedHashMap<String, String>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 500
    }

    private var engine = 0
    private var geminiKey = ""
    private var tickMs = 700L
    private var scale = 1f
    private var offX = 0
    private var offY = 0
    private var busy = false
    private var selecting = false
    private var lastKey = ""
    private var lastToast = 0L
    private var running = false
    @Volatile private var frames = 0
    private var emptyCount = 0
    private var pendingKey = ""
    private var pendingCount = 0
    private var lastApi = 0L
    @Volatile private var currentRequestId = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_SELECT) { if (running) showSelector(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        running = true
        isRunning = true
        startAsForeground()

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        engine = prefs.getInt("engine", 0)
        geminiKey = (prefs.getString("gemini_key", "") ?: "").trim()
        val lang = prefs.getString("lang", "en") ?: "en"
        
        if (engine == 0 && geminiKey.isEmpty()) {
            stopSelf(); return START_NOT_STICKY
        }
        tickMs = 700L

        @Suppress("DEPRECATION")
        val data = intent!!.getParcelableExtra<Intent>("data")!!
        val code = intent.getIntExtra("code", 0)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data).also { p ->
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlay()
        setupDisplay()
        warmup()

        // ✅ تعديل رقم المحرك إلى 1
        if (engine == 1) {
            val src = TranslateLanguage.fromLanguageTag(lang) ?: TranslateLanguage.ENGLISH
            translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.ARABIC).build())
            translator!!.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { translator?.translate("hello")?.addOnCompleteListener { handler.post { tick() } } }
                .addOnFailureListener { stopSelf() }
        } else {
            handler.post { tick() }
        }
        return START_NOT_STICKY
    }

    private fun warmup() {
        if (engine == 1) return // ✅ تعديل رقم المحرك إلى 1
        net.execute {
            try { callGemini(listOf("hi")) } catch (_: Exception) {}
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ps", "PS Translate", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, TranslateService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val sel = PendingIntent.getService(this, 1, Intent(this, TranslateService::class.java).setAction(ACTION_SELECT), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "ps")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("الترجمة تعمل")
            .addAction(Notification.Action.Builder(null, "تحديد المنطقة", sel).build())
            .addAction(Notification.Action.Builder(null, "إيقاف", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun addOverlay() {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        overlay = OverlayView(this)
        wm.addView(overlay, lp)
    }

    private fun setupDisplay() {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        reader?.close()
        reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ _: ImageReader -> frames = frames + 1 }, handler)
        if (display == null) {
            display = projection!!.createVirtualDisplay("ps-translate", m.widthPixels, m.heightPixels, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
        } else {
            display!!.resize(m.widthPixels, m.heightPixels, m.densityDpi)
            display!!.setSurface(reader!!.surface)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (running) { overlay?.setItems(emptyList()); lastKey = ""; setupDisplay() }
    }

    private fun tick() {
        if (!running) return
        val cover = getSharedPreferences("prefs", MODE_PRIVATE).getInt("mode", 0) == 1
        if (!busy && !selecting) {
            busy = true
            val ov = overlay
            if (cover && ov != null && ov.hasItems()) {
                ov.alpha = 0f
                waitForFrames(frames, System.currentTimeMillis())
            } else grab()
        }
        handler.postDelayed({ tick() }, if (cover) 1500L else tickMs)
    }

    private fun waitForFrames(start: Int, t0: Long) {
        if (frames - start >= 1 || System.currentTimeMillis() - t0 > 120) grab()
        else handler.postDelayed({ waitForFrames(start, t0) }, 20)
    }

    private fun up(r: Rect): Rect = Rect((r.left / scale).toInt() + offX, (r.top / scale).toInt() + offY, (r.right / scale).toInt() + offX, (r.bottom / scale).toInt() + offY)

    private fun grab() {
        val image = reader?.acquireLatestImage()
        overlay?.alpha = 1f
        if (image == null) { busy = false; return }
        val shot = toBitmap(image)
        image.close()
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val fl = prefs.getFloat("reg_l", 0f); val ft = prefs.getFloat("reg_t", 0f)
        val fr = prefs.getFloat("reg_r", 1f); val fb = prefs.getFloat("reg_b", 1f)
        offX = (fl * shot.width).toInt().coerceIn(0, shot.width - 2)
        offY = (ft * shot.height).toInt().coerceIn(0, shot.height - 2)
        val cw = ((fr * shot.width).toInt() - offX).coerceIn(2, shot.width - offX)
        val ch = ((fb * shot.height).toInt() - offY).coerceIn(2, shot.height - offY)
        val full = if (cw == shot.width && ch == shot.height) shot else Bitmap.createBitmap(shot, offX, offY, cw, ch)
        scale = minOf(1f, MAX_SIDE / maxOf(full.width, full.height).toFloat())
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true) else full
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { r: Text ->
                val cover = prefs.getInt("mode", 0) == 1
                val own = overlay?.drawn ?: ArrayList<Rect>()
                val blocks = r.textBlocks.filter { b ->
                    val bb = b.boundingBox
                    if (bb == null || b.text.length < 3) false
                    else if (cover) true
                    else { val c = up(bb); !own.any { o -> o.contains(c.centerX(), c.centerY()) } }
                }.take(50)
                handleBlocks(blocks)
            }
            .addOnFailureListener { _: Exception -> busy = false }
    }

    private fun handleBlocks(blocks: List<Text.TextBlock>) {
        if (blocks.isEmpty()) {
            emptyCount++
            if (emptyCount >= 4) { overlay?.setItems(emptyList()); sendToTv(emptyList()); lastKey = "" }
            busy = false; return
        }
        emptyCount = 0
        val groups = mergeBlocks(blocks)
        if (groups.isEmpty()) { overlay?.setItems(emptyList()); sendToTv(emptyList()); lastKey = ""; busy = false; return }

        val srcs = groups.map { g -> g.second }
        val key = srcs.joinToString("|") { t -> t.lowercase().filter { c -> c.isLetterOrDigit() } }
        val coverNow = getSharedPreferences("prefs", MODE_PRIVATE).getInt("mode", 0) == 1
        if (similar(key, lastKey)) { pendingKey = ""; pendingCount = 0; busy = false; return }
        if (similar(key, pendingKey)) pendingCount++ else { pendingKey = key; pendingCount = 1 }

        if (!coverNow && pendingCount < 2) { busy = false; return }

        val rects = groups.map { g -> up(g.first) }
        val missing = srcs.filter { s -> !cache.containsKey(s) }.distinct()
        if (missing.isEmpty()) { show(srcs, rects, key); return }

        // ✅ تعديل رقم المحرك إلى 1
        if (engine == 1) { translateOffline(missing, srcs, rects, key); return }

        val now = System.currentTimeMillis()
        if (now - lastApi < MIN_API_GAP_GEMINI) {
            val wait = MIN_API_GAP_GEMINI - (now - lastApi)
            busy = false
            handler.postDelayed({ if (running && !busy) { busy = true; handleBlocks(blocks) } }, wait)
            return
        }
        lastApi = now
        val reqId = ++currentRequestId

        net.execute {
            var result: List<String>? = null
            var error: String? = null
            try {
                result = callGemini(missing)
            } catch (e: Exception) { error = e.message ?: "error" }
            
            handler.post {
                if (reqId != currentRequestId) { busy = false; return@post }
                if (result != null) {
                    for (i in missing.indices) cache.put(missing[i], result[i])
                    show(srcs, rects, key)
                } else {
                    busy = false
                    handleError(error ?: "error")
                }
            }
        }
    }

    private fun mergeBlocks(blocks: List<Text.TextBlock>): List<Pair<Rect, String>> {
        val lines = ArrayList<OcrLine>()
        for (blk in blocks) {
            val bb = blk.boundingBox ?: continue
            val t = blk.text.replace("\n", " ").trim()
            if (t.isEmpty()) continue
            val found = lines.firstOrNull { ln ->
                val a = ln.rect
                val vOver = minOf(a.bottom, bb.bottom) - maxOf(a.top, bb.top)
                val minH = minOf(ln.h, bb.height())
                val hGap = maxOf(a.left, bb.left) - minOf(a.right, bb.right)
                vOver >= minH * 0.5f && hGap <= maxOf(ln.h, bb.height()) * 2f
            }
            if (found == null) {
                val ln = OcrLine(Rect(bb), bb.height())
                ln.pieces.add(Pair(bb.left, t))
                lines.add(ln)
            } else {
                found.rect.union(bb); found.pieces.add(Pair(bb.left, t)); found.h = maxOf(found.h, bb.height())
            }
        }
        lines.sortBy { ln -> ln.rect.top }
        val out = ArrayList<Pair<Rect, String>>()
        var lastH = 0
        for (ln in lines) {
            val b = ln.rect
            val prev = if (out.isEmpty()) null else out[out.size - 1]
            var merged = false
            if (prev != null) {
                val a = prev.first
                val gap = b.top - a.bottom
                val overlapW = minOf(a.right, b.right) - maxOf(a.left, b.left)
                val minW = minOf(a.width(), b.width())
                val sameSize = kotlin.math.abs(a.height() - b.height()) < maxOf(a.height(), b.height()) * 0.4f
                if (gap <= lastH * 0.5f && overlapW > minW * 0.5f && sameSize) {
                    val u = Rect(a); u.union(b); out[out.size - 1] = Pair(u, prev.second + " " + ln.text()); merged = true
                }
            }
            if (!merged) out.add(Pair(Rect(b), ln.text()))
            lastH = ln.h
        }
        return out.take(20)
    }

    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 6 || b.length < 6) return false
        val ga = HashSet<String>(); val gb = HashSet<String>()
        for (i in 0..a.length - 3) ga.add(a.substring(i, i + 3))
        for (i in 0..b.length - 3) gb.add(b.substring(i, i + 3))
        var inter = 0
        for (x in ga) if (gb.contains(x)) inter++
        val union = ga.size + gb.size - inter
        return union > 0 && inter.toFloat() / union.toFloat() >= 0.8f
    }

    private fun sendToTv(texts: List<String>) {
        val ip = (getSharedPreferences("prefs", MODE_PRIVATE).getString("tv_ip", "") ?: "").trim()
        if (ip.isEmpty()) return
        val msg = texts.joinToString("\n").take(500)
        udp.execute {
            try {
                val bytes = msg.toByteArray(Charsets.UTF_8)
                val sock = DatagramSocket()
                sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), 8765)); sock.close()
            } catch (_: Exception) {}
        }
    }

    private fun handleError(err: String) {
        Log.e("PS_TRANSLATE", "API Error: $err")
        val keyError = err.contains("HTTP 401") || err.contains("HTTP 403") || err.contains("API_KEY_INVALID") || err.contains("API key not valid")
        if (keyError) { Toast.makeText(this, "المفتاح غير صحيح", Toast.LENGTH_LONG).show(); stopSelf(); return }
        if (System.currentTimeMillis() - lastToast > 10000) {
            lastToast = System.currentTimeMillis()
            val msg = if (err.contains("HTTP 429")) "تجاوزت الحد المجاني، سأحاول بعد قليل" else "تعذرت الترجمة: " + err.take(60)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun translateOffline(missing: List<String>, srcs: List<String>, rects: List<Rect>, key: String) {
        val remaining = intArrayOf(missing.size)
        val finish = fun() { remaining[0]--; if (remaining[0] == 0) show(srcs, rects, key) }
        for (s in missing) {
            translator!!.translate(s)
                .addOnSuccessListener { res: String -> cache.put(s, res); finish() }
                .addOnFailureListener { _: Exception -> finish() }
        }
    }

    private fun showSelector() {
        if (selector != null) return
        selecting = true
        val v = SelectView(this) { l, t, r, b ->
            val e = getSharedPreferences("prefs", MODE_PRIVATE).edit()
            e.putFloat("reg_l", l); e.putFloat("reg_t", t); e.putFloat("reg_r", r); e.putFloat("reg_b", b); e.apply()
            removeSelector(); overlay?.setItems(emptyList()); lastKey = ""
            Toast.makeText(this, "تم حفظ المنطقة", Toast.LENGTH_SHORT).show()
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        selector = v; wm.addView(v, lp)
    }

    private fun removeSelector() { selector?.let { v -> runCatching { wm.removeView(v) } }; selector = null; selecting = false }

    private fun show(srcs: List<String>, rects: List<Rect>, key: String) {
        val list = ArrayList<Pair<Rect, String>>()
        for (i in srcs.indices) list.add(Pair(rects[i], cache[srcs[i]] ?: srcs[i]))
        val p = getSharedPreferences("prefs", MODE_PRIVATE)
        overlay?.fontSize = p.getInt("font", 44).toFloat()
        overlay?.above = p.getInt("mode", 0) == 0
        overlay?.setItems(list); sendToTv(list.map { p -> p.second })
        lastKey = key; pendingKey = ""; pendingCount = 0; busy = false
    }

    private fun httpPost(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.connectTimeout = 8000; conn.readTimeout = 20000; conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        conn.outputStream.use { os -> os.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = if (stream == null) "" else stream.bufferedReader().use { br -> br.readText() }
        return Pair(code, text)
    }

    private fun parseList(raw: String, texts: List<String>): List<String> {
        var out = raw.trim()
        if (out.startsWith("```")) out = out.substringAfter("\n").substringBeforeLast("```").trim()
        val start = out.indexOf('['); val end = out.lastIndexOf(']')
        if (start >= 0 && end > start) out = out.substring(start, end + 1)
        val res = try { JSONArray(out) } catch (e: Exception) { return texts }
        if (res.length() < texts.size) return texts
        val list = ArrayList<String>()
        for (i in texts.indices) list.add(res.optString(i, texts[i]))
        return list
    }

    private fun callGemini(texts: List<String>): List<String> {
        val arr = JSONArray(); for (t in texts) arr.put(t)
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", arr.toString())))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))
        val headers = mapOf("x-goog-api-key" to geminiKey)
        var lastErr = "HTTP 0"
        for (m in GEMINI_MODELS) {
            val r = httpPost("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent", headers, body.toString())
            if (r.first in 200..299) return parseList(JSONObject(r.second).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text"), texts)
            lastErr = "HTTP " + r.first + " " + r.second.take(200)
            if (r.first != 404) break
        }
        throw Exception(lastErr)
    }

    private fun toBitmap(image: Image): Bitmap {
        val p = image.planes[0]
        val pad = p.rowStride - p.pixelStride * image.width
        val full = Bitmap.createBitmap(image.width + pad / p.pixelStride, image.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        return if (pad == 0) full else Bitmap.createBitmap(full, 0, 0, image.width, image.height)
    }

    override fun onDestroy() {
        running = false; isRunning = false
        handler.removeCallbacksAndMessages(null); net.shutdownNow(); udp.shutdownNow()
        if (::wm.isInitialized) { removeSelector(); overlay?.let { v -> runCatching { wm.removeView(v) } } }
        display?.release(); reader?.close(); projection?.stop(); translator?.close(); recognizer.close()
        super.onDestroy()
    }
}

// ✅ كلاس OverlayView بعد إصلاح الأنواع (Int/Float) والنص في المنتصف
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
            val pad = 24f // Float
            tp.textSize = fontSize
            
            val minW = (width * 0.6f).toInt()
            val w = max(r.width() + (pad * 2).toInt(), minW).coerceAtMost(width - 32)
            val innerW = w - (pad * 2).toInt()
            
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, innerW)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
                
            // ✅ تحويل كافة القيم إلى Float لتفادي الخطأ
            val layoutH = layout.height.toFloat()
            val boxH = layoutH + (pad * 2)
            
            val left = (width - w) / 2f
            var top = if (above) r.top.toFloat() - boxH - 12f else r.bottom.toFloat() + 12f
            if (above && top < 0f) top = r.bottom.toFloat() + 12f
            top = top.coerceIn(0f, height.toFloat() - boxH)
            
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

class SelectView(context: Context, private val onDone: (Float, Float, Float, Float) -> Unit) : View(context) {
    private var sx = 0f; private var sy = 0f; private var ex = 0f; private var ey = 0f; private var dragging = false
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val fill = Paint().apply { color = 0x3300FF00 }
    private val line = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 6f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 46f; textAlign = Paint.Align.CENTER }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        if (dragging) {
            val l = minOf(sx, ex); val t = minOf(sy, ey); val r = maxOf(sx, ex); val b = maxOf(sy, ey)
            canvas.drawRect(l, t, r, b, fill); canvas.drawRect(l, t, r, b, line)
        }
        canvas.drawText("اسحب بإصبعك لتحديد منطقة الحوار", width / 2f, 130f, txt)
        canvas.drawText("المس بدون سحب لترجمة الشاشة كلها", width / 2f, 200f, txt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; ex = e.x; ey = e.y; dragging = true }
            MotionEvent.ACTION_MOVE -> { ex = e.x; ey = e.y }
            MotionEvent.ACTION_UP -> {
                ex = e.x; ey = e.y; dragging = false
                val w = width.toFloat(); val h = height.toFloat()
                val l = minOf(sx, ex); val t = minOf(sy, ey); val r = maxOf(sx, ex); val b = maxOf(sy, ey)
                if (r - l < 60f || b - t < 60f) onDone(0f, 0f, 1f, 1f) else onDone(l / w, t / h, r / w, b / h)
            }
        }
        invalidate()
        return true
    }
}

class OcrLine(var rect: Rect, var h: Int) {
    val pieces = ArrayList<Pair<Int, String>>()
    fun text(): String = pieces.sortedBy { p -> p.first }.joinToString(" ") { p -> p.second }
}
