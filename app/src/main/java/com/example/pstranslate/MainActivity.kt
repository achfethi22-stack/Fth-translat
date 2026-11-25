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
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private val langs = listOf(
        "en" to "English", "fr" to "Français", "es" to "Español",
        "de" to "Deutsch", "it" to "Italiano", "pt" to "Português", "tr" to "Türkçe"
    )
    
    // ✅ حذف Claude و Groq من القائمة
    private val engines = listOf(
        "Gemini (مجاني - ذكاء اصطناعي)",
        "بدون إنترنت (مجاني - أبسط)"
    )
    
    private lateinit var engineSpinner: Spinner
    private lateinit var langSpinner: Spinner
    private lateinit var geminiBox: EditText
    
    private val modes = listOf(
        "فوق النص الأصلي (ثابتة بدون وميض)",
        "تغطية النص الأصلي (قد تومض)"
    )

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
            text = "مترجم ألعاب PS5\nيترجم النص الظاهر على الشاشة إلى العربية"
            textSize = 18f
            gravity = Gravity.CENTER
        })

        root.addView(label("\nمحرك الترجمة:"))
        engineSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, engines)
            // ضمان عدم تجاوز الحدود في حال كان المحفوظ سابقاً 2 أو 3
            setSelection(prefs.getInt("engine", 0).coerceIn(0, engines.lastIndex))
        }
        root.addView(engineSpinner)

        root.addView(label("\nمفتاح Gemini (من aistudio.google.com):"))
        geminiBox = keyBox("AIza...", prefs.getString("gemini_key", ""))
        root.addView(geminiBox)

        // ✅ حذف حقول Groq و Claude

        root.addView(label("\nلغة اللعبة (تُستخدم في وضع بدون إنترنت فقط):"))
        langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, langs.map { it.second })
            val saved = prefs.getString("lang", "en")
            setSelection(langs.indexOfFirst { it.first == saved }.coerceAtLeast(0))
        }
        root.addView(langSpinner)

        root.addView(label("\nطريقة عرض الترجمة:"))
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modes)
            setSelection(prefs.getInt("mode", 0))
        }
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putInt("mode", pos).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(modeSpinner)

        val fontNow = prefs.getInt("font", 44)
        val fontLabel = label("\nحجم الخط: " + fontNow)
        root.addView(fontLabel)
        val fontBar = SeekBar(this).apply {
            max = 60
            progress = fontNow - 20
        }
        fontBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + 20
                fontLabel.text = "\nحجم الخط: " + v
                prefs.edit().putInt("font", v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(fontBar)

        root.addView(label("\nعنوان IP للتلفاز (اختياري - لإرسال الترجمة إلى التلفاز):"))
        val tvBox = EditText(this).apply {
            hint = "192.168.1.xx"
            setText(prefs.getString("tv_ip", ""))
        }
        tvBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("tv_ip", (s?.toString() ?: "").trim()).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        root.addView(tvBox)

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
        root.addView(Button(this).apply {
            text = "تحديد منطقة الحوار"
            setOnClickListener {
                if (TranslateService.isRunning) {
                    startService(Intent(this@MainActivity, TranslateService::class.java).setAction(TranslateService.ACTION_SELECT))
                    moveTaskToBack(true)
                } else {
                    Toast.makeText(this@MainActivity, "ابدأ الترجمة أولاً ثم ارجع واضغط هنا", Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "ترجمة الشاشة كلها"
            setOnClickListener {
                val e = getSharedPreferences("prefs", MODE_PRIVATE).edit()
                e.putFloat("reg_l", 0f)
                e.putFloat("reg_t", 0f)
                e.putFloat("reg_r", 1f)
                e.putFloat("reg_b", 1f)
                e.apply()
                Toast.makeText(this@MainActivity, "تم", Toast.LENGTH_SHORT).show()
            }
        })
        setContentView(scroll)
    }

    private fun start() {
        val engine = engineSpinner.selectedItemPosition
        val gemini = geminiBox.text.toString().trim()
        
        // ✅ التحقق فقط من مفتاح Gemini إذا تم اختياره
        if (engine == 0 && gemini.isEmpty()) {
            Toast.makeText(this, "أدخل مفتاح Gemini أولاً", Toast.LENGTH_LONG).show()
            return
        }
        
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putInt("engine", engine)
            .putString("gemini_key", gemini)
            .putString("lang", langs[langSpinner.selectedItemPosition].first)
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
