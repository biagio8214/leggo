package com.example.leggo

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    
    private lateinit var spinnerAppLang: Spinner
    private lateinit var spinnerTtsLang: Spinner
    private lateinit var spinnerTheme: Spinner
    private lateinit var seekSpeed: SeekBar
    private lateinit var textSpeedValue: TextView
    private lateinit var seekTextSize: SeekBar
    private lateinit var textTextSizeValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        prefs = getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)
        
        spinnerAppLang = findViewById(R.id.spinnerAppLanguage)
        spinnerTtsLang = findViewById(R.id.spinnerTtsLanguage)
        spinnerTheme = findViewById(R.id.spinnerTheme)
        seekSpeed = findViewById(R.id.seekSpeed)
        textSpeedValue = findViewById(R.id.textSpeedValue)
        seekTextSize = findViewById(R.id.seekTextSize)
        textTextSizeValue = findViewById(R.id.textTextSizeValue)
        
        setupSpinners()
        setupSeekBars()
        
        loadCurrentSettings()
        
        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Impostazioni salvate. Riavvia per applicare lingua app.", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    private fun setupSpinners() {
        // Lingue App (Semplificato)
        val appLangs = arrayOf("Italiano", "English")
        spinnerAppLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, appLangs)
        
        // Lingue TTS
        val ttsLangs = arrayOf("Italiano", "English", "Français", "Deutsch", "Español")
        spinnerTtsLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ttsLangs)
        
        // Temi
        val themes = arrayOf("Giorno (Bianco)", "Pergamena", "Notte (Nero)")
        spinnerTheme.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, themes)
    }
    
    private fun setupSeekBars() {
        // Speed: 0..5 -> 0.5, 1.0, 1.5, 2.0, 2.5, 3.0
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = 0.5f + (progress * 0.5f)
                textSpeedValue.text = "${speed}x"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Text Size: 0..6 -> 14..20
        seekTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = 14 + progress
                textTextSizeValue.text = "${size}sp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun loadCurrentSettings() {
        val appLang = prefs.getString("app_lang", "Italiano")
        val ttsLang = prefs.getString("tts_lang", "Italiano")
        val theme = prefs.getString("reader_theme", "Giorno (Bianco)")
        val speed = prefs.getFloat("tts_speed", 1.0f)
        val textSize = prefs.getInt("text_size", 14) // default 14
        
        setSpinnerSelection(spinnerAppLang, appLang)
        setSpinnerSelection(spinnerTtsLang, ttsLang)
        setSpinnerSelection(spinnerTheme, theme)
        
        // Converti speed a progress
        // speed = 0.5 + p*0.5 => p = (speed - 0.5) * 2
        val speedProgress = ((speed - 0.5f) * 2).toInt().coerceIn(0, 5)
        seekSpeed.progress = speedProgress
        textSpeedValue.text = "${speed}x"
        
        val sizeProgress = (textSize - 14).coerceIn(0, 6)
        seekTextSize.progress = sizeProgress
        textTextSizeValue.text = "${textSize}sp"
    }
    
    private fun setSpinnerSelection(spinner: Spinner, value: String?) {
        val adapter = spinner.adapter as ArrayAdapter<String>
        val pos = adapter.getPosition(value)
        if (pos >= 0) spinner.setSelection(pos)
    }
    
    private fun saveSettings() {
        val editor = prefs.edit()
        editor.putString("app_lang", spinnerAppLang.selectedItem.toString())
        editor.putString("tts_lang", spinnerTtsLang.selectedItem.toString())
        editor.putString("reader_theme", spinnerTheme.selectedItem.toString())
        
        val speed = 0.5f + (seekSpeed.progress * 0.5f)
        editor.putFloat("tts_speed", speed)
        
        val size = 14 + seekTextSize.progress
        editor.putInt("text_size", size)
        
        editor.apply()
    }
}
