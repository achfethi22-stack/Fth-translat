package com.example.pstranslate

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.content.Context
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
        const val MODEL = "claude-haiku-5-5"
        const val MAX_SIDE = 1280f
        const val TICK_MS = 800L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var overlay: OverlayView? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cache = HashMap<String, String>()
    private var apiKey = ""
    private var scale = 1f
    private var busy = false
    private var lastKey = ""
    private var lastToast = 0L
    private var running = false
    private var selector: SelectView? = null
    private var selecting = false
    private var offX = 0
    private var offY = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_SELECT) { if (running) showSelector(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        running = true
        isRunning = true
        startAsForeground()

        apiKey = getSharedPreferences("prefs", MODE_PRIVATE).getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) { stopSelf(); return START_NOT_STICKY }

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
        handler.post { tick() }
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
        handler.postDelayed({ tick() }, TICK_MS)
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
                val blocks = r.textBlocks.filter { b -> b.boundingBox != null && b.text.length >= 3 }.take(15)
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
        val key = blocks.joinToString("|") { b -> b.text }
        if (key == lastKey) { busy = false; return }

        val srcs = blocks.map { b -> b.text.replace("\n", " ") }
        val rects = blocks.map { b -> up(b.boundingBox ?: Rect()) }
        val missing = srcs.filter { s -> !cache.containsKey(s) }.distinct()
        if (missing.isEmpty()) { show(srcs, rects, key); return }

        net.execute {
            var result: List<String>? = null
            var error: String? = null
            try {
                result = translateBatch(missing)
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
                    if (err != null && err.contains("401")) {
                        Toast.makeText(this, "\u0627\u0644\u0645\u0641\u062a\u0627\u062d \u063a\u064a\u0631 \u0635\u062d\u064a\u062d", Toast.LENGTH_LONG).show()
                        stopSelf()
                    } else if (System.currentTimeMillis() - lastToast > 10000) {
                        lastToast = System.currentTimeMillis()
                        Toast.makeText(this, "\u062a\u0639\u0630\u0631\u062a \u0627\u0644\u062a\u0631\u062c\u0645\u0629: " + err, Toast.LENGTH_SHORT).show()
                    }
                }
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

    private fun translateBatch(texts: List<String>): List<String> {
        val arr = JSONArray()
        for (t in texts) arr.put(t)
        val system = "You translate on-screen video game text into Arabic. " +
            "The user message is a JSON array of strings. Reply with ONLY a JSON array of the Arabic " +
            "translations, same length and same order. Keep character names, numbers and button names " +
            "(X, O, L1, R2...) unchanged. Be short and natural. No explanations, no code fences."
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 1500)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", arr.toString())))

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.outputStream.use { os -> os.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { br -> br.readText() }
        if (code !in 200..299) throw Exception("HTTP " + code)

        var out = JSONObject(text).getJSONArray("content").getJSONObject(0).getString("text").trim()
        if (out.startsWith("```")) out = out.substringAfter("\n").substringBeforeLast("```").trim()
        val res = JSONArray(out)
        val list = ArrayList<String>()
        for (i in texts.indices) list.add(if (i < res.length()) res.optString(i, texts[i]) else texts[i])
        return list
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
        if (::wm.isInitialized) removeSelector()
        net.shutdownNow()
        display?.release()
        reader?.close()
        projection?.stop()
        overlay?.let { v -> runCatching { wm.removeView(v) } }
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
