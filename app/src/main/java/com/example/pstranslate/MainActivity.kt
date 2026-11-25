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
        "en" to "English", "fr" to "Français", "es" to "Español",
        "de" to "Deutsch", "it" to "Italiano", "pt" to "Português", "tr" to "Türkçe"
    )
    
    private val engines = listOf(
        "Gemini (مجاني - ذكاء اصطناعي)",
        "بدون إنترنت (مجاني - أبسط)"
    )
    
    private val modes = listOf(
        "فوق النص الأصلي (ثابتة بدون وميض)",
        "تغطية النص الأصلي (قد تومض)"
    )

    private lateinit var engineSpinner: MaterialAutoCompleteTextView
    private lateinit var langSpinner: MaterialAutoCompleteTextView
    private lateinit var geminiBox: TextInputEditText
    private lateinit var geminiInputLayout: TextInputLayout
    private lateinit var tvIpBox: TextInputEditText

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK && r.data != null) {
                val i = Intent(this, TranslateService::class.java)
                    .putExtra("code", r.resultCode)
                    .putExtra("data", r.data)
                startForegroundService(i)
                Toast.makeText(this, "تم التشغيل. افتح PS Remote Play الآن", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        // ربط العناصر
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

        // إعداد المحركات
        engineSpinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, engines))
        val savedEngine = prefs.getInt("engine", 0).coerceIn(0, engines.lastIndex)
        engineSpinner.setText(engines[savedEngine], false)

        // إخفاء/إظهار حقل Gemini بناءً على المحرك
        engineSpinner.setOnItemClickListener { _, _, position, _ ->
            geminiInputLayout.visibility = if (position == 0) View.VISIBLE else View.GONE
        }
        geminiInputLayout.visibility = if (savedEngine == 0) View.VISIBLE else View.GONE
        geminiBox.setText(prefs.getString("gemini_key", ""))

        // إعداد اللغات
        langSpinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, langs.map { it.second }))
        val savedLang = prefs.getString("lang", "en")
        val langIndex = langs.indexOfFirst { it.first == savedLang }.coerceAtLeast(0)
        langSpinner.setText(langs[langIndex].second, false)

        // إعداد الأوضاع
        modeSpinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, modes))
        val savedMode = prefs.getInt("mode", 0)
        modeSpinner.setText(modes[savedMode], false)
        modeSpinner.setOnItemClickListener { _, _, position, _ ->
            prefs.edit().putInt("mode", position).apply()
        }

        // إعداد حجم الخط
        val fontNow = prefs.getInt("font", 44)
        fontLabel.text = "حجم الخط: $fontNow"
        fontSlider.value = fontNow.toFloat()
        fontSlider.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            fontLabel.text = "حجم الخط: $v"
            prefs.edit().putInt("font", v).apply()
        }

        // إعداد IP TV
        tvIpBox.setText(prefs.getString("tv_ip", ""))
        tvIpBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("tv_ip", (s?.toString() ?: "").trim()).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // الأزرار
        btnStart.setOnClickListener { start() }
        btnStop.setOnClickListener {
            startService(Intent(this, TranslateService::class.java).setAction(TranslateService.ACTION_STOP))
        }
        btnSelectArea.setOnClickListener {
            if (TranslateService.isRunning) {
                startService(Intent(this, TranslateService::class.java).setAction(TranslateService.ACTION_SELECT))
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "ابدأ الترجمة أولاً ثم ارجع واضغط هنا", Toast.LENGTH_LONG).show()
            }
        }
        btnFullScreen.setOnClickListener {
            val e = getSharedPreferences("prefs", MODE_PRIVATE).edit()
            e.putFloat("reg_l", 0f)
            e.putFloat("reg_t", 0f)
            e.putFloat("reg_r", 1f)
            e.putFloat("reg_b", 1f)
            e.apply()
            Toast.makeText(this, "تم تحديد الشاشة كاملة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun start() {
        val engine = engines.indexOf(engineSpinner.text.toString()).coerceAtLeast(0)
        val gemini = geminiBox.text.toString().trim()
        
        if (engine == 0 && gemini.isEmpty()) {
            Toast.makeText(this, "أدخل مفتاح Gemini أولاً", Toast.LENGTH_LONG).show()
            return
        }
        
        val langIndex = langs.indexOfFirst { it.second == langSpinner.text.toString() }.coerceAtLeast(0)

        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putInt("engine", engine)
            .putString("gemini_key", gemini)
            .putString("lang", langs[langIndex].first)
            .apply()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل خيار العرض فوق التطبيقات ثم ارجع", Toast.LENGTH_LONG).show()
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
