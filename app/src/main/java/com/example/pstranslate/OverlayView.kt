package com.example.pstranslate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private val langs = listOf(
        "en" to "English", "fr" to "Français", "es" to "Español",
        "de" to "Deutsch", "it" to "Italiano", "pt" to "Português", "tr" to "Türkçe"
    )
    private lateinit var spinner: Spinner

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK && r.data != null) {
                val i = Intent(this, TranslateService::class.java)
                    .putExtra("code", r.resultCode)
                    .putExtra("data", r.data)
                    .putExtra("lang", langs[spinner.selectedItemPosition].first)
                startForegroundService(i)
                Toast.makeText(this, "تم التشغيل. افتح PS Remote Play الآن", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 120, 56, 56)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(TextView(this).apply {
            text = "مترجم ألعاب PS5\nيترجم النص الظاهر على الشاشة إلى العربية فوق PS Remote Play"
            textSize = 18f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply { text = "\nلغة اللعبة:" })
        spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, langs.map { it.second })
        }
        root.addView(spinner)
        root.addView(Button(this).apply {
            text = "ابدأ الترجمة"
            setOnClickListener { start() }
        })
        root.addView(Button(this).apply {
            text = "إيقاف"
            setOnClickListener {
                startService(Intent(this@MainActivity, TranslateService::class.java).setAction(TranslateService.ACTION_STOP))
            }
        })
        setContentView(root)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل «العرض فوق التطبيقات» ثم ارجع", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
