package com.example.pstranslate

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
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
import android.view.WindowManager
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class TranslateService : Service() {

    companion object { const val ACTION_STOP = "stop" }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var overlay: OverlayView? = null
    private var translator: Translator? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cache = HashMap<String, String>()
    private var busy = false
    private var lastKey = ""
    private var running = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        running = true
        startAsForeground()

        @Suppress("DEPRECATION")
        val data = intent!!.getParcelableExtra<Intent>("data")!!
        val code = intent.getIntExtra("code", 0)
        val lang = intent.getStringExtra("lang") ?: "en"

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlay()
        setupDisplay()

        val src = TranslateLanguage.fromLanguageTag(lang) ?: TranslateLanguage.ENGLISH
        translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.ARABIC).build()
        )
        translator!!.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { handler.post(::tick) }
            .addOnFailureListener { stopSelf() }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ps", "PS Translate", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, TranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "ps")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("الترجمة تعمل")
            .addAction(Notification.Action.Builder(null, "إيقاف", stop).build())
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
            display!!.surface = reader!!.surface
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (running) { overlay?.setItems(emptyList()); lastKey = ""; setupDisplay() }
    }

    private fun tick() {
        if (!running) return
        if (!busy) {
            busy = true
            val ov = overlay
            if (ov != null && ov.hasItems()) {
                ov.alpha = 0f               // نخفي الترجمة لحظة الالتقاط كي لا تُقرأ مرة أخرى
                handler.postDelayed(::grab, 150)
            } else grab()
        }
        handler.postDelayed(::tick, 1500)
    }

    private fun grab() {
        val image = reader?.acquireLatestImage()
        overlay?.alpha = 1f
        if (image == null) { busy = false; return }
        val bmp = toBitmap(image)
        image.close()
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { r ->
                val blocks = r.textBlocks.filter { it.boundingBox != null && it.text.length >= 3 }.take(15)
                translateBlocks(blocks)
            }
            .addOnFailureListener { busy = false }
    }

    private fun translateBlocks(blocks: List<Text.TextBlock>) {
        if (blocks.isEmpty()) { overlay?.setItems(emptyList()); lastKey = ""; busy = false; return }
        val key = blocks.joinToString("|") { it.text }
        if (key == lastKey) { busy = false; return }

        val out = arrayOfNulls<Pair<Rect, String>>(blocks.size)
        var left = blocks.size
        blocks.forEachIndexed { i, b ->
            val src = b.text.replace("\n", " ")
            val done = { t: String ->
                out[i] = Pair(b.boundingBox!!, t)
                if (--left == 0) {
                    overlay?.setItems(out.filterNotNull())
                    lastKey = key
                    busy = false
                }
            }
            val hit = cache[src]
            if (hit != null) done(hit)
            else translator!!.translate(src)
                .addOnSuccessListener { cache[src] = it; done(it) }
                .addOnFailureListener { done(src) }
        }
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
        handler.removeCallbacksAndMessages(null)
        display?.release()
        reader?.close()
        projection?.stop()
        overlay?.let { runCatching { wm.removeView(it) } }
        translator?.close()
        recognizer.close()
        super.onDestroy()
    }
}
