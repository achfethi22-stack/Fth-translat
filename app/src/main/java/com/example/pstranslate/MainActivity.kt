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
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.button.MaterialButton
import android.widget.TextView

class MainActivity : ComponentActivity() {

    private val langs = listOf(
        "en-US" to "English (American)", "en" to "English", "fr" to "Fran\u00e7ais", "es" to "Espa\u00f1ol",
        "de" to "Deutsch", "it" to "Italiano", "pt" to "Portugu\u00eas", "tr" to "T\u00fcrk\u00e7e"
    )
    
    private val engines = listOf(
        "Gemini (\u0645\u062c\u0627\u0646\u064a - \u0630\u0643\u0627\u0621 \u0627\u0635\u0637\u0646\u0627\u0639\u064a)",
        "\u0628\u062f\u0648\u0646 \u0625\u0646\u062a\u0631\u0646\u062a (\u0645\u062c\u0627\u0646\u064a - \u0623\u0628\u0633\u0637)"
    )
    
    private val modes = listOf(
        "\u0641\u0648\u0642 \u0627\u0644\u0646\u0635 \u0627\u0644\u0623\u0635\u0644\u064a (\u062b\u0627\u0628\u062a\u0629 \u0628\u062f\u0648\u0646 \u0648\u0645\u064a\u0636)",
        "\u062a\u063a\u0637\u064a\u0629 \u0627\u0644\u0646\u0635 \u0627\u0644\u0623\u0635\u0644\u064a (\u0642\u062f \u062a\u0648\u0645\u0636)"
    )

    private lateinit var engineSpinner: MaterialAutoCompleteTextView
    private lateinit var langSpinner: MaterialAutoCompleteTextView
    private lateinit var geminiBox: TextInputEditText
    private lateinit var geminiInputLayout: TextInputLayout
    private lateinit var tvIpBox: TextInputEditText
    private var openWebAfter = false

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK && r.data != null) {
                val i = Intent(this, TranslateService::class.java)
                    .putExtra("code", r.resultCode)
                    .putExtra("data", r.data)
                startForegroundService(i)
                Toast.makeText(this, "\u062a\u0645 \u0627\u0644\u062a\u0634\u063a\u064a\u0644. \u0627\u0641\u062a\u062d PS Remote Play \u0627\u0644\u0622\u0646", Toast.LENGTH_LONG).show()
                if (openWebAfter) startActivity(Intent(this, WebActivity::class.java)) else moveTaskToBack(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        // \u0631\u0628\u0637 \u0627\u0644\u0639\u0646\u0627\u0635\u0631
        engineSpinner = findViewById(R.id.engineSpinner)
        langSpinner = findViewById(R.id.langSpinner)
        geminiBox = findViewById(R.id.geminiBox)
        geminiInputLayout = findViewById(R.id.geminiInputLayout)
        tvIpBox = findViewById(R.id.tvIpBox)
        val modeSpinner = findViewById<MaterialAutoCompleteTextView>(R.id.modeSpinner)
        val fontLabel = findViewById<TextView>(R.id.fontLabel)
        val fontSlider = findViewById<Slider>(R.id.fontSlider)

        val btnStart = findViewById<MaterialButton>(R.id.btnStart)
        val btnStop = findViewById<MaterialButton>(R.id.btnStop)
        val btnSelectArea = findViewById<MaterialButton>(R.id.btnSelectArea)
        val btnFullScreen = findViewById<MaterialButton>(R.id.btnFullScreen)
        val btnStartWeb = findViewById<MaterialButton>(R.id.btnStartWeb)
        val autoBox = findViewById<CheckBox>(R.id.autoBottomBox)
        val webUrlBox = findViewById<TextInputEditText>(R.id.webUrlBox)

        // \u0625\u0639\u062f\u0627\u062f \u0627\u0644\u0645\u062d\u0631\u0643\u0627\u062a
        engineSpinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, engines))
        val savedEngine = prefs.getInt("engine", 0).coerceIn(0, engines.lastIndex)
        engineSpinner.setText(engines[savedEngine], false)

        // \u0625\u062e\u0641\u0627\u0621/\u0625\u0638\u0647\u0627\u0631 \u062d\u0642\u0644 Gemini \u0628\u0646\u0627\u0621\u064b \u0639\u0644\u0649 \u0627\u0644\u0645\u062d\u0631\u0643
        engineSpinner.setOnItemClickListener { _, _, position, _ ->
            geminiInputLayout.visibility = if (position == 0) View.VISIBLE else View.GONE
        }
        geminiInputLayout.visibility = if (savedEngine == 0) View.VISIBLE else View.GONE
        geminiBox.setText(prefs.getString("gemini_key", ""))

        // \u0625\u0639\u062f\u0627\u062f \u0627\u0644\u0644\u063a\u0627\u062a
        langSpinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, langs.map { it.second }))
        val savedLang = prefs.getString("lang", "en")
        val langIndex = langs.indexOfFirst { it.first == savedLang }.coerceAtLeast(0)
        langSpinner.setText(langs[langIndex].second, false)

        // \u0625\u0639\u062f\u0627\u062f \u0627\u0644\u0623\u0648\u0636\u0627\u0639
        modeSpinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, modes))
        val savedMode = prefs.getInt("mode", 0)
        modeSpinner.setText(modes[savedMode], false)
        modeSpinner.setOnItemClickListener { _, _, position, _ ->
            prefs.edit().putInt("mode", position).apply()
        }

        // \u0625\u0639\u062f\u0627\u062f \u062d\u062c\u0645 \u0627\u0644\u062e\u0637
        val fontNow = prefs.getInt("font", 44)
        fontLabel.text = "\u062d\u062c\u0645 \u0627\u0644\u062e\u0637: $fontNow"
        fontSlider.value = fontNow.toFloat()
        fontSlider.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            fontLabel.text = "\u062d\u062c\u0645 \u0627\u0644\u062e\u0637: $v"
            prefs.edit().putInt("font", v).apply()
        }

        // \u0625\u0639\u062f\u0627\u062f IP TV
        tvIpBox.setText(prefs.getString("tv_ip", ""))
        tvIpBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("tv_ip", (s?.toString() ?: "").trim()).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // \u0627\u0644\u0642\u0633\u0645 \u0627\u0644\u0633\u0641\u0644\u064a \u0627\u0644\u062a\u0644\u0642\u0627\u0626\u064a
        autoBox.isChecked = prefs.getBoolean("auto_bottom", true)
        autoBox.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_bottom", checked).apply()
        }

        // \u0631\u0627\u0628\u0637 \u0627\u0644\u0645\u062a\u0635\u0641\u062d \u0627\u0644\u062f\u0627\u062e\u0644\u064a
        webUrlBox.setText(prefs.getString("web_url", "https://www.xbox.com/play"))
        webUrlBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("web_url", (s?.toString() ?: "").trim()).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // \u0627\u0644\u0623\u0632\u0631\u0627\u0631
        btnStart.setOnClickListener { start(false) }
        btnStartWeb.setOnClickListener { start(true) }
        btnStop.setOnClickListener {
            startService(Intent(this, TranslateService::class.java).setAction(TranslateService.ACTION_STOP))
        }
        btnSelectArea.setOnClickListener {
            if (TranslateService.isRunning) {
                startService(Intent(this, TranslateService::class.java).setAction(TranslateService.ACTION_SELECT))
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "\u0627\u0628\u062f\u0623 \u0627\u0644\u062a\u0631\u062c\u0645\u0629 \u0623\u0648\u0644\u0627\u064b \u062b\u0645 \u0627\u0631\u062c\u0639 \u0648\u0627\u0636\u063a\u0637 \u0647\u0646\u0627", Toast.LENGTH_LONG).show()
            }
        }
        btnFullScreen.setOnClickListener {
            val e = getSharedPreferences("prefs", MODE_PRIVATE).edit()
            e.putFloat("reg_l", 0f)
            e.putFloat("reg_t", 0f)
            e.putFloat("reg_r", 1f)
            e.putFloat("reg_b", 1f)
            e.putBoolean("auto_bottom", false)
            e.apply()
            autoBox.isChecked = false
            Toast.makeText(this, "\u062a\u0645 \u062a\u062d\u062f\u064a\u062f \u0627\u0644\u0634\u0627\u0634\u0629 \u0643\u0627\u0645\u0644\u0629", Toast.LENGTH_SHORT).show()
        }
    }

    private fun start(openWeb: Boolean) {
        openWebAfter = openWeb
        val engine = engines.indexOf(engineSpinner.text.toString()).coerceAtLeast(0)
        val gemini = geminiBox.text.toString().trim()
        
        if (engine == 0 && gemini.isEmpty()) {
            Toast.makeText(this, "\u0623\u062f\u062e\u0644 \u0645\u0641\u062a\u0627\u062d Gemini \u0623\u0648\u0644\u0627\u064b", Toast.LENGTH_LONG).show()
            return
        }
        
        val langIndex = langs.indexOfFirst { it.second == langSpinner.text.toString() }.coerceAtLeast(0)

        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putInt("engine", engine)
            .putString("gemini_key", gemini)
            .putString("lang", langs[langIndex].first)
            .putBoolean("web_only", openWeb)
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
