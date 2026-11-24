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
        "en" to "English", "fr" to "Fran\xe7ais", "es" to "Espa\xf1ol",
        "de" to "Deutsch", "it" to "Italiano", "pt" to "Portugu\xeas", "tr" to "T\xfcrk\xe7e"
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
                Toast.makeText(this, "\u062a\u0645 \u0627\u0644\u062a\u0634\u063a\u064a\u0644. \u0627\u0641\u062a\u062d PS Remote Play \u0627\u0644\u0622\u0646", Toast.LENGTH_LONG).show()
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
            text = "\u0645\u062a\u0631\u062c\u0645 \u0623\u0644\u0639\u0627\u0628 PS5\n\u064a\u062a\u0631\u062c\u0645 \u0627\u0644\u0646\u0635 \u0627\u0644\u0638\u0627\u0647\u0631 \u0639\u0644\u0649 \u0627\u0644\u0634\u0627\u0634\u0629 \u0625\u0644\u0649 \u0627\u0644\u0639\u0631\u0628\u064a\u0629 \u0641\u0648\u0642 PS Remote Play"
            textSize = 18f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply { text = "\n\u0644\u063a\u0629 \u0627\u0644\u0644\u0639\u0628\u0629:" })
        spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, langs.map { it.second })
        }
        root.addView(spinner)
        root.addView(Button(this).apply {
            text = "\u0627\u0628\u062f\u0623 \u0627\u0644\u062a\u0631\u062c\u0645\u0629"
            setOnClickListener { start() }
        })
        root.addView(Button(this).apply {
            text = "\u0625\u064a\u0642\u0627\u0641"
            setOnClickListener {
                startService(Intent(this@MainActivity, TranslateService::class.java).setAction(TranslateService.ACTION_STOP))
            }
        })
        setContentView(root)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "\u0641\u0639\u0651\u0644 \xab\u0627\u0644\u0639\u0631\u0636 \u0641\u0648\u0642 \u0627\u0644\u062a\u0637\u0628\u064a\u0642\u0627\u062a\xbb \u062b\u0645 \u0627\u0631\u062c\u0639", Toast.LENGTH_LONG).show()
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
