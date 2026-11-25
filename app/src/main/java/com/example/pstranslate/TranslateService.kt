package com.example.pstranslate

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class TranslateService : Service() {

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_SELECT = "select"
        @Volatile var isRunning = false

        const val CLAUDE_MODEL = "claude-haiku-5-5"
        val GEMINI_MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
        const val MAX_SIDE = 1280f

        const val SYSTEM = "You translate on-screen video game text into Arabic. " +
            "The user message is a JSON array of strings. Reply with ONLY a JSON array of the Arabic " +
            "translations, same length and same order. Keep character names, numbers and button names " +
            "(X, O, L1, R2...) unchanged. Be short and natural. No explanations, no code fences."
    }

    private val handler = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var overlay: OverlayView? = null
    private var selector: SelectView? = null
    private var translator: Translator? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cache = HashMap<String, String>()

    private var engine = 0
    private var claudeKey = ""
    private var geminiKey = ""
    private var tickMs = 1000L
    private var scale = 1f
    private var offX = 0
    private var offY = 0
    private var busy = false
    private var selecting = false
    private var lastKey = ""
    private var lastToast = 0L
    private var running = false

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
        claudeKey = prefs.getString("api_key", "") ?: ""
        geminiKey = prefs.getString("gemini_key", "") ?: ""
        val lang = prefs.getString("lang", "en") ?: "en"
        if ((engine == 0 && geminiKey.isEmpty()) || (engine == 1 && claudeKey.isEmpty())) {
            stopSelf()
            return START_NOT_STICKY
        }
        tickMs = when (engine) {
            0 -> 4000L
            1 -> 800L
            else -> 1000L
        }

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

        if (engine == 2) {
            val src = TranslateLanguage.fromLanguageTag(lang) ?: TranslateLanguage.ENGLISH
            translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.ARABIC).build()
            )
            translator!!.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { handler.post { tick() } }
                .addOnFailureListener { stopSelf() }
        } else {
            handler.post { tick() }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ps", "PS Translate", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, TranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val sel = PendingIntent.getService(
            this, 1, Intent(this, TranslateService::class.java).setAction(ACTION_SELECT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "ps")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("\u0627\u0644\u062a\u0631\u062c\u0645\u0629 \u062a\u0639\u0645\u0644")
            .addAction(Notification.Action.Builder(null, "\u062a\u062d\u062f\u064a\u062f \u0627\u0644\u0645\u0646\u0637\u0642\u0629", sel).build())
            .addAction(Notification.Action.Builder(null, "\u0625\u064a\u0642\u0627\u0641", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun addOverlay() {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        overlay = OverlayView(this)
        wm.addView(overlay, lp)
    }

    private fun setupDisplay() {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        reader?.close()
        reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)
        if (display == null) {
            display = projection!!.createVirtualDisplay(
                "ps-translate", m.widthPixels, m.heightPixels, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
            )
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
        if (!busy && !selecting) {
            busy = true
            val ov = overlay
            if (ov != null && ov.hasItems()) {
                ov.alpha = 0f
                handler.postDelayed({ grab() }, 120)
            } else grab()
        }
        handler.postDelayed({ tick() }, tickMs)
    }

    private fun up(r: Rect): Rect =
        Rect((r.left / scale).toInt() + offX, (r.top / scale).toInt() + offY,
            (r.right / scale).toInt() + offX, (r.bottom / scale).toInt() + offY)

    private fun grab() {
        val image = reader?.acquireLatestImage()
        overlay?.alpha = 1f
        if (image == null) { busy = false; return }
        val shot = toBitmap(image)
        image.close()
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val fl = prefs.getFloat("reg_l", 0f)
        val ft = prefs.getFloat("reg_t", 0f)
        val fr = prefs.getFloat("reg_r", 1f)
        val fb = prefs.getFloat("reg_b", 1f)
        offX = (fl * shot.width).toInt().coerceIn(0, shot.width - 2)
        offY = (ft * shot.height).toInt().coerceIn(0, shot.height - 2)
        val cw = ((fr * shot.width).toInt() - offX).coerceIn(2, shot.width - offX)
        val ch = ((fb * shot.height).toInt() - offY).coerceIn(2, shot.height - offY)
        val full = if (cw == shot.width && ch == shot.height) shot else Bitmap.createBitmap(shot, offX, offY, cw, ch)
        scale = minOf(1f, MAX_SIDE / maxOf(full.width, full.height).toFloat())
        val bmp = if (scale < 1f)
            Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
        else full
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { r: Text ->
                val blocks = r.textBlocks.filter { b -> b.boundingBox != null && b.text.length >= 3 }.take(30)
                handleBlocks(blocks)
            }
            .addOnFailureListener { _: Exception -> busy = false }
    }

    private fun handleBlocks(blocks: List<Text.TextBlock>) {
        if (blocks.isEmpty()) {
            overlay?.setItems(emptyList())
            lastKey = ""
            busy = false
            return
        }
        val groups = mergeBlocks(blocks)
        if (groups.isEmpty()) {
            overlay?.setItems(emptyList())
            lastKey = ""
            busy = false
            return
        }
        val srcs = groups.map { g -> g.second }
        val key = srcs.joinToString("|")
        if (key == lastKey) { busy = false; return }

        val rects = groups.map { g -> up(g.first) }
        val missing = srcs.filter { s -> !cache.containsKey(s) }.distinct()
        if (missing.isEmpty()) { show(srcs, rects, key); return }

        if (engine == 2) {
            translateOffline(missing, srcs, rects, key)
            return
        }

        net.execute {
            var result: List<String>? = null
            var error: String? = null
            try {
                result = if (engine == 1) callClaude(missing) else callGemini(missing)
            } catch (e: Exception) {
                error = e.message ?: "error"
            }
            val res = result
            val err = error
            handler.post {
                if (res != null) {
                    for (i in missing.indices) cache.put(missing[i], res[i])
                    show(srcs, rects, key)
                } else {
                    busy = false
                    handleError(err ?: "error")
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
                found.rect.union(bb)
                found.pieces.add(Pair(bb.left, t))
                found.h = maxOf(found.h, bb.height())
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
                if (gap <= lastH * 0.8f && overlapW > minW * 0.3f) {
                    val u = Rect(a)
                    u.union(b)
                    out[out.size - 1] = Pair(u, prev.second + " " + ln.text())
                    merged = true
                }
            }
            if (!merged) out.add(Pair(Rect(b), ln.text()))
            lastH = ln.h
        }
        return out.take(10)
    }

    private fun handleError(err: String) {
        val keyError = err.contains("HTTP 401") || err.contains("HTTP 403") ||
            err.contains("API_KEY_INVALID") || err.contains("API key not valid")
        if (keyError) {
            Toast.makeText(this, "\u0627\u0644\u0645\u0641\u062a\u0627\u062d \u063a\u064a\u0631 \u0635\u062d\u064a\u062d", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        if (System.currentTimeMillis() - lastToast > 10000) {
            lastToast = System.currentTimeMillis()
            val msg = if (err.contains("HTTP 429")) "\u062a\u062c\u0627\u0648\u0632\u062a \u0627\u0644\u062d\u062f \u0627\u0644\u0645\u062c\u0627\u0646\u064a\u060c \u0633\u0623\u062d\u0627\u0648\u0644 \u0628\u0639\u062f \u0642\u0644\u064a\u0644" else "\u062a\u0639\u0630\u0631\u062a \u0627\u0644\u062a\u0631\u062c\u0645\u0629: " + err.take(60)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun translateOffline(missing: List<String>, srcs: List<String>, rects: List<Rect>, key: String) {
        val remaining = intArrayOf(missing.size)
        val finish = fun() {
            remaining[0] = remaining[0] - 1
            if (remaining[0] == 0) show(srcs, rects, key)
        }
        for (s in missing) {
            translator!!.translate(s)
                .addOnSuccessListener { res: String ->
                    cache.put(s, res)
                    finish()
                }
                .addOnFailureListener { _: Exception ->
                    cache.put(s, s)
                    finish()
                }
        }
    }

    private fun showSelector() {
        if (selector != null) return
        selecting = true
        val v = SelectView(this) { l: Float, t: Float, r: Float, b: Float ->
            val e = getSharedPreferences("prefs", MODE_PRIVATE).edit()
            e.putFloat("reg_l", l)
            e.putFloat("reg_t", t)
            e.putFloat("reg_r", r)
            e.putFloat("reg_b", b)
            e.apply()
            removeSelector()
            overlay?.setItems(emptyList())
            lastKey = ""
            Toast.makeText(this, "\u062a\u0645 \u062d\u0641\u0638 \u0627\u0644\u0645\u0646\u0637\u0642\u0629", Toast.LENGTH_SHORT).show()
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        selector = v
        wm.addView(v, lp)
    }

    private fun removeSelector() {
        selector?.let { v -> runCatching { wm.removeView(v) } }
        selector = null
        selecting = false
    }

    private fun show(srcs: List<String>, rects: List<Rect>, key: String) {
        val list = ArrayList<Pair<Rect, String>>()
        for (i in srcs.indices) list.add(Pair(rects[i], cache[srcs[i]] ?: srcs[i]))
        overlay?.setItems(list)
        lastKey = key
        busy = false
    }

    private fun httpPost(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        conn.doOutput = true
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
        val res = JSONArray(out)
        val list = ArrayList<String>()
        for (i in texts.indices) list.add(if (i < res.length()) res.optString(i, texts[i]) else texts[i])
        return list
    }

    private fun callClaude(texts: List<String>): List<String> {
        val arr = JSONArray()
        for (t in texts) arr.put(t)
        val body = JSONObject()
            .put("model", CLAUDE_MODEL)
            .put("max_tokens", 1500)
            .put("system", SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", arr.toString())))
        val headers = mapOf("x-api-key" to claudeKey, "anthropic-version" to "2023-06-01")
        val r = httpPost("https://api.anthropic.com/v1/messages", headers, body.toString())
        if (r.first !in 200..299) throw Exception("HTTP " + r.first + " " + r.second.take(200))
        val raw = JSONObject(r.second).getJSONArray("content").getJSONObject(0).getString("text")
        return parseList(raw, texts)
    }

    private fun callGemini(texts: List<String>): List<String> {
        val arr = JSONArray()
        for (t in texts) arr.put(t)
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", arr.toString())))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))
        val headers = mapOf("x-goog-api-key" to geminiKey)
        var lastErr = "HTTP 0"
        for (m in GEMINI_MODELS) {
            val r = httpPost("https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent", headers, body.toString())
            if (r.first in 200..299) {
                val raw = JSONObject(r.second).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                return parseList(raw, texts)
            }
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
        running = false
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        net.shutdownNow()
        if (::wm.isInitialized) {
            removeSelector()
            overlay?.let { v -> runCatching { wm.removeView(v) } }
        }
        display?.release()
        reader?.close()
        projection?.stop()
        translator?.close()
        recognizer.close()
        super.onDestroy()
    }
}

class SelectView(context: Context, private val onDone: (Float, Float, Float, Float) -> Unit) : View(context) {
    private var sx = 0f
    private var sy = 0f
    private var ex = 0f
    private var ey = 0f
    private var dragging = false
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val fill = Paint().apply { color = 0x3300FF00 }
    private val line = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 46f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        if (dragging) {
            val l = minOf(sx, ex)
            val t = minOf(sy, ey)
            val r = maxOf(sx, ex)
            val b = maxOf(sy, ey)
            canvas.drawRect(l, t, r, b, fill)
            canvas.drawRect(l, t, r, b, line)
        }
        canvas.drawText("\u0627\u0633\u062d\u0628 \u0628\u0625\u0635\u0628\u0639\u0643 \u0644\u062a\u062d\u062f\u064a\u062f \u0645\u0646\u0637\u0642\u0629 \u0627\u0644\u062d\u0648\u0627\u0631", width / 2f, 130f, txt)
        canvas.drawText("\u0627\u0644\u0645\u0633 \u0628\u062f\u0648\u0646 \u0633\u062d\u0628 \u0644\u062a\u0631\u062c\u0645\u0629 \u0627\u0644\u0634\u0627\u0634\u0629 \u0643\u0644\u0647\u0627", width / 2f, 200f, txt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x
                sy = e.y
                ex = e.x
                ey = e.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                ex = e.x
                ey = e.y
            }
            MotionEvent.ACTION_UP -> {
                ex = e.x
                ey = e.y
                dragging = false
                val w = width.toFloat()
                val h = height.toFloat()
                val l = minOf(sx, ex)
                val t = minOf(sy, ey)
                val r = maxOf(sx, ex)
                val b = maxOf(sy, ey)
                if (r - l < 60f || b - t < 60f) onDone(0f, 0f, 1f, 1f)
                else onDone(l / w, t / h, r / w, b / h)
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
