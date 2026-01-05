package com.example.leggo

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

class SettingsActivity : BaseActivity() {

    private lateinit var sbTextSize: SeekBar
    private lateinit var sbTtsSpeed: SeekBar
    private lateinit var spinnerAppLang: Spinner
    private lateinit var spinnerTtsLang: Spinner
    private lateinit var spinnerReaderTheme: Spinner
    private lateinit var tvTextSizeValue: TextView
    private lateinit var tvTtsSpeedValue: TextView
    private lateinit var btnSystemTtsSettings: Button
    private lateinit var prefs: SharedPreferences

    private val speedSteps = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f)

    private var availableVoices: List<Voice> = emptyList()
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("LeggoSettings", MODE_PRIVATE)

        spinnerAppLang = findViewById(R.id.spinnerAppLang)
        sbTextSize = findViewById(R.id.sbTextSize)
        sbTtsSpeed = findViewById(R.id.sbTtsSpeed)
        spinnerTtsLang = findViewById(R.id.spinnerTtsLang)
        spinnerReaderTheme = findViewById(R.id.spinnerReaderTheme)
        tvTextSizeValue = findViewById(R.id.tvTextSizeValue)
        tvTtsSpeedValue = findViewById(R.id.tvTtsSpeedValue)
        btnSystemTtsSettings = findViewById(R.id.btnSystemTtsSettings)

        setupSpinners()
        loadSettings()
        setupListeners()

        initTtsForVoices()
    }

    private fun initTtsForVoices() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                populateTtsVoices()
            }
        }
    }

    private fun populateTtsVoices() {
        try {
            val allVoices = tts?.voices ?: emptySet()
            if (allVoices.isEmpty()) return

            val allowedLangs = setOf("it", "en", "fr", "de", "es")

            availableVoices = allVoices.filter { voice ->
                val lang = voice.locale.language
                allowedLangs.contains(lang) && !voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }.sortedWith(compareByDescending<Voice> {
                it.isNetworkConnectionRequired
            }.thenBy {
                it.locale.displayLanguage
            })

            val voiceNames = availableVoices.map { voice ->
                val langDisplay = voice.locale.getDisplayLanguage(Locale.getDefault())
                val country = voice.locale.country
                val quality = if (voice.isNetworkConnectionRequired) " (High Quality)" else ""
                val variant = if (voice.name.contains("female", true)) "F" else if (voice.name.contains("male", true)) "M" else ""
                "$langDisplay ($country) $variant$quality"
            }

            runOnUiThread {
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerTtsLang.adapter = adapter

                val savedVoiceName = prefs.getString("tts_voice_name", "")
                if (!savedVoiceName.isNullOrEmpty()) {
                    val index = availableVoices.indexOfFirst { it.name == savedVoiceName }
                    if (index >= 0) {
                        spinnerTtsLang.setSelection(index)
                    }
                } else {
                    val savedLang = prefs.getString("tts_lang", "Italiano")
                    val langCode = when (savedLang) {
                        "English" -> "en"
                        "Français" -> "fr"
                        "Deutsch" -> "de"
                        "Español" -> "es"
                        else -> "it"
                    }
                    val index = availableVoices.indexOfFirst { it.locale.language == langCode }
                    if (index >= 0) spinnerTtsLang.setSelection(index)
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Error loading voices", e)
        }
    }

    private fun setupListeners() {
        btnSystemTtsSettings.setOnClickListener {
            try {
                val intent = Intent("com.android.settings.TTS_SETTINGS")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
        }

        spinnerAppLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedLangCode = if (position == 1) "en" else "it"
                val currentLang = prefs.getString("app_lang", "it")

                if (currentLang != selectedLangCode) {
                    saveSetting("app_lang", selectedLangCode)
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    finish()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // SeekBar dimensione testo: da 14sp a 50sp
        sbTextSize.apply {
            max = 36  // 50 - 14 = 36 step

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val textSize = 14 + progress
                    tvTextSizeValue.text = getString(R.string.text_size_value, textSize)
                    if (fromUser) {
                        saveSetting("text_size", textSize)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        sbTtsSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = speedSteps[progress]
                tvTtsSpeedValue.text = getString(R.string.tts_speed_value, speed.toString())
                if (fromUser) saveSetting("tts_speed", speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        spinnerReaderTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedTheme = parent?.getItemAtPosition(position).toString()
                saveSetting("reader_theme", selectedTheme)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerTtsLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (availableVoices.isNotEmpty() && position < availableVoices.size) {
                    val selectedVoice = availableVoices[position]
                    saveSetting("tts_voice_name", selectedVoice.name)
                    saveSetting("tts_lang_code", selectedVoice.locale.language)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSpinners() {
        ArrayAdapter.createFromResource(this, R.array.app_languages, android.R.layout.simple_spinner_item).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerAppLang.adapter = this
        }

        ArrayAdapter.createFromResource(this, R.array.tts_languages, android.R.layout.simple_spinner_item).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerTtsLang.adapter = this
        }

        ArrayAdapter.createFromResource(this, R.array.reader_themes, android.R.layout.simple_spinner_item).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerReaderTheme.adapter = this
        }
    }

    private fun loadSettings() {
        // Lingua app
        val appLang = prefs.getString("app_lang", "it")
        spinnerAppLang.setSelection(if (appLang == "en") 1 else 0, false)

        // Dimensione testo - CORREZIONE SICURA
        var textSize = prefs.getInt("text_size", 18)  // default ragionevole
        textSize = textSize.coerceIn(14, 50)  // Forza tra 14 e 50

        // Aggiorna anche le preferenze con il valore corretto (pulisce valori vecchi corrotti)
        if (textSize != prefs.getInt("text_size", 18)) {
            saveSetting("text_size", textSize)
        }

        sbTextSize.progress = (textSize - 14).coerceIn(0, 36)
        tvTextSizeValue.text = getString(R.string.text_size_value, textSize)

        // Velocità TTS
        val ttsSpeed = prefs.getFloat("tts_speed", 1.0f)
        val speedIndex = speedSteps.indexOfFirst { it == ttsSpeed }.takeIf { it >= 0 } ?: 1
        sbTtsSpeed.progress = speedIndex
        tvTtsSpeedValue.text = getString(R.string.tts_speed_value, speedSteps[speedIndex].toString())

        // Tema lettore
        val readerTheme = prefs.getString("reader_theme", "Giorno (Bianco)")
        @Suppress("UNCHECKED_CAST")
        (spinnerReaderTheme.adapter as? ArrayAdapter<String>)?.getPosition(readerTheme)?.let {
            spinnerReaderTheme.setSelection(it)
        }
    }

    private fun saveSetting(key: String, value: Any) {
        prefs.edit().apply {
            when (value) {
                is Int -> putInt(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
            }
            apply()
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
