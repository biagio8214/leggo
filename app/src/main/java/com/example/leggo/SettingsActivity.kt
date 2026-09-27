package com.example.leggo

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var spinnerTtsLang: Spinner
    private lateinit var spinnerReaderTheme: Spinner
    private lateinit var sbTextSize: SeekBar
    private lateinit var sbTtsSpeed: SeekBar
    private lateinit var tvTextSizeValue: TextView
    private lateinit var tvTtsSpeedValue: TextView
    private lateinit var btnSystemTtsSettings: Button

    private val speedSteps = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f)
    private var availableVoices: MutableList<Voice?> = mutableListOf()
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        supportActionBar?.title = "Impostazioni"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = getSharedPreferences("LeggoSettings", MODE_PRIVATE)

        // Initialize views manually instead of DataBinding
        spinnerTtsLang = findViewById(R.id.spinnerTtsLang)
        spinnerReaderTheme = findViewById(R.id.spinnerReaderTheme)
        sbTextSize = findViewById(R.id.sbTextSize)
        sbTtsSpeed = findViewById(R.id.sbTtsSpeed)
        tvTextSizeValue = findViewById(R.id.tvTextSizeValue)
        tvTtsSpeedValue = findViewById(R.id.tvTtsSpeedValue)
        btnSystemTtsSettings = findViewById(R.id.btnSystemTtsSettings)

        setupSpinners()
        loadSettings()
        setupListeners()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                populateTtsVoices()
            }
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun populateTtsVoices() {
        try {
            val allVoices = tts?.voices ?: emptySet()
            val allowedLangs = setOf("it", "en", "fr", "de", "es")
            
            val filteredVoices = allVoices.filter { voice ->
                val isSupportedLang = allowedLangs.contains(voice.locale.language)
                val isInstalled = !voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                val isHighQuality = voice.quality >= Voice.QUALITY_HIGH || 
                                    voice.name.contains("network", ignoreCase = true) ||
                                    voice.isNetworkConnectionRequired

                isSupportedLang && isInstalled && isHighQuality
            }.filter { voice ->
                val nameLower = voice.name.lowercase()
                nameLower.contains("female") || nameLower.contains("-f-") || nameLower.contains("fem") ||
                nameLower.contains("male") || nameLower.contains("-m-")
            }.sortedWith(compareBy({ it.locale.language }, { it.name }))

            availableVoices.clear()
            availableVoices.add(null) 
            availableVoices.addAll(filteredVoices)

            val voiceNames = availableVoices.map { voice ->
                if (voice == null) {
                    "Predefinita di sistema"
                } else {
                    val lang = voice.locale.getDisplayLanguage(Locale.ITALIAN).replaceFirstChar { it.uppercase() }
                    val country = voice.locale.country
                    val nameLower = voice.name.lowercase()
                    
                    val gender = when {
                        nameLower.contains("female") || nameLower.contains("-f-") || nameLower.contains("fem") -> "♀ Femminile"
                        else -> "♂ Maschile"
                    }
                    
                    "$lang ($country) - $gender"
                }
            }

            runOnUiThread {
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerTtsLang.adapter = adapter

                val savedVoiceName = prefs.getString("tts_voice_name", "system_default")
                val index = availableVoices.indexOfFirst { 
                    (it == null && savedVoiceName == "system_default") || it?.name == savedVoiceName 
                }
                if (index >= 0) spinnerTtsLang.setSelection(index)
            }
        } catch (e: Exception) {
            Log.e("Settings", "Error voices", e)
        }
    }

    private fun setupListeners() {
        btnSystemTtsSettings.setOnClickListener {
            try {
                val intent = Intent()
                intent.action = "com.android.settings.TTS_SETTINGS"
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
        }

        sbTextSize.apply {
            max = 36
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val textSize = 14 + progress
                    tvTextSizeValue.text = "${textSize}sp"
                    if (fromUser) saveSetting("text_size", textSize)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        sbTtsSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = speedSteps[progress]
                tvTtsSpeedValue.text = "x${speed}"
                if (fromUser) saveSetting("tts_speed", speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        spinnerReaderTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                saveSetting("reader_theme", parent?.getItemAtPosition(position).toString())
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        spinnerTtsLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (availableVoices.isNotEmpty() && position < availableVoices.size) {
                    val voice = availableVoices[position]
                    if (voice == null) {
                        saveSetting("tts_voice_name", "system_default")
                    } else {
                        saveSetting("tts_voice_name", voice.name)
                        saveSetting("tts_lang", mapLocaleToLangName(voice.locale))
                    }
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun mapLocaleToLangName(locale: Locale): String {
        return when (locale.language) {
            "it" -> "Italiano"
            "en" -> "English"
            "fr" -> "Français"
            "de" -> "Deutsch"
            "es" -> "Español"
            else -> "Italiano"
        }
    }

    private fun setupSpinners() {
        val themesAdapter = ArrayAdapter.createFromResource(this, R.array.reader_themes, android.R.layout.simple_spinner_item)
        themesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerReaderTheme.adapter = themesAdapter
    }

    private fun loadSettings() {
        val textSize = prefs.getInt("text_size", 18).coerceIn(14, 50)
        sbTextSize.progress = textSize - 14
        tvTextSizeValue.text = "${textSize}sp"

        val ttsSpeed = prefs.getFloat("tts_speed", 1.0f)
        val speedIndex = speedSteps.indexOfFirst { it == ttsSpeed }.takeIf { it >= 0 } ?: 1
        sbTtsSpeed.progress = speedIndex
        tvTtsSpeedValue.text = "x${speedSteps[speedIndex]}"

        val theme = prefs.getString("reader_theme", "Giorno (Bianco)")
        for (i in 0 until spinnerReaderTheme.adapter.count) {
            if (spinnerReaderTheme.adapter.getItem(i).toString() == theme) {
                spinnerReaderTheme.setSelection(i)
                break
            }
        }
    }

    private fun saveSetting(key: String, value: Any) {
        val editor = prefs.edit()
        when (value) {
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
        }
        editor.apply()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
