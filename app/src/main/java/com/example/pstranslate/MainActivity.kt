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
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private val langs = listOf(
        "en" to "English", "fr" to "Fran\u00e7ais", "es" to "Espa\u00f1ol",
        "de" to "Deutsch", "it" to "Italiano", "pt" to "Portugu\u00eas", "tr" to "T\u00fcrk\u00e7e"
    )
    private val engines = listOf(
        "Gemini (\u0645\u062c\u0627\u0646\u064a - \u0630\u0643\u0627\u0621 \u0627\u0635\u0637\u0646\u0627\u0639\u064a)",
        "Claude (\u0645\u062f\u0641\u0648\u0639 - \u0627\u0644\u0623\u062f\u0642)",
        "\u0628\u062f\u0648\u0646 \u0625\u0646\u062a\u0631\u0646\u062a (\u0645\u062c\u0627\u0646\u064a - \u0623\u0628\u0633\u0637)"
    )
    private lateinit var engineSpinner: Spinner
    private lateinit var langSpinner: Spinner
    private lateinit var claudeBox: EditText
    private lateinit var geminiBox: EditText

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK && r.data != null) {
                val i = Intent(this, TranslateService::class.java)
                    .putExtra("code", r.resultCode)
                    .putExtra("data", r.data)
                startForegroundService(i)
                Toast.makeText(this, "\u062a\u0645 \u0627\u0644\u062a\u0634\u063a\u064a\u0644. \u0627\u0641\u062a\u062d PS Remote Play \u0627\u0644\u0622\u0646", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            }
        }

    private fun label(t: String) = TextView(this).apply { text = t }

    private fun keyBox(hintText: String, saved: String?) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setText(saved ?: "")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 100, 56, 56)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "\u0645\u062a\u0631\u062c\u0645 \u0623\u0644\u0639\u0627\u0628 PS5\n\u064a\u062a\u0631\u062c\u0645 \u0627\u0644\u0646\u0635 \u0627\u0644\u0638\u0627\u0647\u0631 \u0639\u0644\u0649 \u0627\u0644\u0634\u0627\u0634\u0629 \u0625\u0644\u0649 \u0627\u0644\u0639\u0631\u0628\u064a\u0629"
            textSize = 18f
            gravity = Gravity.CENTER
        })

        root.addView(label("\n\u0645\u062d\u0631\u0643 \u0627\u0644\u062a\u0631\u062c\u0645\u0629:"))
        engineSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, engines)
            setSelection(prefs.getInt("engine", 0))
        }
        root.addView(engineSpinner)

        root.addView(label("\n\u0645\u0641\u062a\u0627\u062d Gemini (\u0645\u0646 aistudio.google.com):"))
        geminiBox = keyBox("AIza...", prefs.getString("gemini_key", ""))
        root.addView(geminiBox)

        root.addView(label("\n\u0645\u0641\u062a\u0627\u062d Claude (\u0645\u0646 console.anthropic.com):"))
        claudeBox = keyBox("sk-ant-...", prefs.getString("api_key", ""))
        root.addView(claudeBox)

        root.addView(label("\n\u0644\u063a\u0629 \u0627\u0644\u0644\u0639\u0628\u0629 (\u062a\u064f\u0633\u062a\u062e\u062f\u0645 \u0641\u064a \u0648\u0636\u0639 \u0628\u062f\u0648\u0646 \u0625\u0646\u062a\u0631\u0646\u062a \u0641\u0642\u0637):"))
        langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, langs.map { it.second })
            val saved = prefs.getString("lang", "en")
            setSelection(langs.indexOfFirst { it.first == saved }.coerceAtLeast(0))
        }
        root.addView(langSpinner)

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
        root.addView(Button(this).apply {
            text = "\u062a\u062d\u062f\u064a\u062f \u0645\u0646\u0637\u0642\u0629 \u0627\u0644\u062d\u0648\u0627\u0631"
            setOnClickListener {
                if (TranslateService.isRunning) {
                    startService(Intent(this@MainActivity, TranslateService::class.java).setAction(TranslateService.ACTION_SELECT))
                    moveTaskToBack(true)
                } else {
                    Toast.makeText(this@MainActivity, "\u0627\u0628\u062f\u0623 \u0627\u0644\u062a\u0631\u062c\u0645\u0629 \u0623\u0648\u0644\u0627\u064b \u062b\u0645 \u0627\u0631\u062c\u0639 \u0648\u0627\u0636\u063a\u0637 \u0647\u0646\u0627", Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "\u062a\u0631\u062c\u0645\u0629 \u0627\u0644\u0634\u0627\u0634\u0629 \u0643\u0644\u0647\u0627"
            setOnClickListener {
                val e = getSharedPreferences("prefs", MODE_PRIVATE).edit()
                e.putFloat("reg_l", 0f)
                e.putFloat("reg_t", 0f)
                e.putFloat("reg_r", 1f)
                e.putFloat("reg_b", 1f)
                e.apply()
                Toast.makeText(this@MainActivity, "\u062a\u0645", Toast.LENGTH_SHORT).show()
            }
        })
        setContentView(scroll)
    }

    private fun start() {
        val engine = engineSpinner.selectedItemPosition
        val gemini = geminiBox.text.toString().trim()
        val claude = claudeBox.text.toString().trim()
        if (engine == 0 && gemini.isEmpty()) {
            Toast.makeText(this, "\u0623\u062f\u062e\u0644 \u0645\u0641\u062a\u0627\u062d Gemini \u0623\u0648\u0644\u0627\u064b", Toast.LENGTH_LONG).show()
            return
        }
        if (engine == 1 && claude.isEmpty()) {
            Toast.makeText(this, "\u0623\u062f\u062e\u0644 \u0645\u0641\u062a\u0627\u062d Claude \u0623\u0648\u0644\u0627\u064b", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putInt("engine", engine)
            .putString("gemini_key", gemini)
            .putString("api_key", claude)
            .putString("lang", langs[langSpinner.selectedItemPosition].first)
            .apply()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "\u0641\u0639\u0651\u0644 \u062e\u064a\u0627\u0631 \u0627\u0644\u0639\u0631\u0636 \u0641\u0648\u0642 \u0627\u0644\u062a\u0637\u0628\u064a\u0642\u0627\u062a \u062b\u0645 \u0627\u0631\u062c\u0639", Toast.LENGTH_LONG).show()
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
