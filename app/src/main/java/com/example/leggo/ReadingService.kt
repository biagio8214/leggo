package com.example.leggo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

class ReadingService : Service(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private val binder = LocalBinder()
    private var sentences: List<String> = emptyList()
    @Volatile private var currentSentenceIndex = 0
    private var isReading = false
    private var callback: ReadingCallback? = null

    private var currentSpeed: Float = 1.0f
    private var currentLanguage: String = "Italiano"
    private var currentVoiceName: String? = null

    private val CHUNK_SIZE = 5

    interface ReadingCallback {
        fun onBlockSpoken(startIndex: Int)
        fun onReadingStopped()
        fun onReadingFinished()
    }

    inner class LocalBinder : Binder() {
        fun getService(): ReadingService = this@ReadingService
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val prefs = getSharedPreferences("LeggoSettings", Context.MODE_PRIVATE)
            currentVoiceName = prefs.getString("tts_voice_name", null)
            updateSettings(currentSpeed, currentLanguage, currentVoiceName)

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId?.startsWith("leggo_block_") == true) {
                        val spokenBlockStartIndex = utteranceId.removePrefix("leggo_block_").toInt()
                        
                        if (isReading) {
                            currentSentenceIndex = spokenBlockStartIndex + CHUNK_SIZE
                            if (currentSentenceIndex < sentences.size) {
                                speakNextBlock()
                            } else {
                                isReading = false
                                callback?.onReadingFinished()
                            }
                        }
                    }
                }
                override fun onError(utteranceId: String?) {}
            })
        } else {
            Log.e("TTS", "Inizializzazione fallita")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, "leggo_channel")
            .setContentTitle("Leggo in background")
            .setContentText("La lettura vocale è attiva.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) 
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        tts.stop() 
        stopSelf() 
    }

    fun setSentences(sentences: List<String>, startIndex: Int) {
        this.sentences = sentences
        this.currentSentenceIndex = (startIndex / CHUNK_SIZE) * CHUNK_SIZE
    }

    fun startReading() {
        if (sentences.isNotEmpty()) {
            isReading = true
            speakNextBlock()
        }
    }

    fun startReadingFrom(index: Int) {
        currentSentenceIndex = (index / CHUNK_SIZE) * CHUNK_SIZE
        isReading = true 
        speakNextBlock()
    }

    fun stopReading() {
        isReading = false
        tts.stop()
        callback?.onReadingStopped()
    }

    fun isReading(): Boolean = isReading

    fun movePosition(amount: Int) {
        // Move by blocks now
        val newIndex = (currentSentenceIndex + (amount * CHUNK_SIZE)).coerceIn(0, sentences.size - 1)
        currentSentenceIndex = (newIndex / CHUNK_SIZE) * CHUNK_SIZE

        if (isReading) {
            speakNextBlock()
        } else {
            callback?.onBlockSpoken(currentSentenceIndex)
        }
    }
    
    fun getCurrentIndex(): Int = currentSentenceIndex

    private fun speakNextBlock() {
        if (!isReading || currentSentenceIndex >= sentences.size) {
            if (isReading) { // se la lettura è finita
                isReading = false
                callback?.onReadingFinished()
            }
            return
        }

        val endIndex = (currentSentenceIndex + CHUNK_SIZE).coerceAtMost(sentences.size)
        val textToSpeak = sentences.subList(currentSentenceIndex, endIndex).joinToString(separator = " ")
        
        if (textToSpeak.isNotBlank()) {
            val utteranceId = "leggo_block_$currentSentenceIndex"
            tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            callback?.onBlockSpoken(currentSentenceIndex)
        } else {
            // Skip empty block
            currentSentenceIndex += CHUNK_SIZE
            speakNextBlock()
        }
    }

    fun updateSettings(speed: Float, language: String, voiceName: String? = null) {
        this.currentSpeed = speed
        this.currentLanguage = language
        this.currentVoiceName = voiceName
        
        if (!::tts.isInitialized) return

        tts.setSpeechRate(speed)
        
        if (voiceName != null) {
            val voices = tts.voices
            val targetVoice = voices?.find { it.name == voiceName }
            if (targetVoice != null) {
                tts.voice = targetVoice
                return 
            }
        }

        val locale = when (language) {
            "English" -> Locale.US
            "Français" -> Locale.FRENCH
            "Deutsch" -> Locale.GERMAN
            "Español" -> Locale("es", "ES")
            else -> Locale.ITALIAN
        }
        
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e("TTS", "Language not supported or missing data: $language")
        }
    }

    fun setCallback(callback: ReadingCallback) {
        this.callback = callback
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "leggo_channel",
                "Leggo Background Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}