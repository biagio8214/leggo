package com.example.leggo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.util.Locale

class ReadingService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()
    private var textToSpeech: TextToSpeech? = null
    private var isReading = false
    private var readingCallback: ReadingCallback? = null
    
    private var currentSentences: List<String> = emptyList()
    private var currentSentenceIndex = 0
    
    // Impostazioni correnti
    private var currentSpeed: Float = 1.0f
    private var currentLang: Locale = Locale.ITALIAN

    interface ReadingCallback {
        fun onSentenceSpoken(index: Int)
        fun onReadingStopped()
    }

    inner class LocalBinder : Binder() {
        fun getService(): ReadingService = this@ReadingService
    }

    override fun onCreate() {
        super.onCreate()
        textToSpeech = TextToSpeech(this, this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopReading()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun setCallback(callback: ReadingCallback?) {
        this.readingCallback = callback
    }
    
    fun updateSettings(speed: Float, languageName: String) {
        currentSpeed = speed
        currentLang = when(languageName) {
            "English" -> Locale.ENGLISH
            "Français" -> Locale.FRENCH
            "Deutsch" -> Locale.GERMAN
            "Español" -> Locale("es", "ES")
            else -> Locale.ITALIAN
        }
        
        // Applica subito se possibile
        textToSpeech?.setSpeechRate(currentSpeed)
        textToSpeech?.language = currentLang
    }

    fun setSentences(sentences: List<String>, startIndex: Int) {
        this.currentSentences = sentences
        this.currentSentenceIndex = startIndex
    }

    fun startReading() {
        if (currentSentences.isNotEmpty()) {
            isReading = true
            startForegroundService()
            
            // Assicurati che le impostazioni siano applicate
            textToSpeech?.setSpeechRate(currentSpeed)
            textToSpeech?.language = currentLang
            
            speakCurrentSentence()
        }
    }

    fun stopReading() {
        isReading = false
        textToSpeech?.stop()
        readingCallback?.onReadingStopped()
    }

    fun isReading(): Boolean = isReading

    fun nextSentence() {
        currentSentenceIndex++
        if (currentSentenceIndex < currentSentences.size) {
            speakCurrentSentence()
        } else {
            stopReading()
        }
    }
    
    fun movePosition(offset: Int) {
        val wasReading = isReading
        textToSpeech?.stop()
        currentSentenceIndex = (currentSentenceIndex + offset).coerceIn(0, currentSentences.size - 1)
        readingCallback?.onSentenceSpoken(currentSentenceIndex)
        
        if (wasReading) {
            speakCurrentSentence()
        } else {
            isReading = true
            speakCurrentSentence()
        }
    }

    private fun speakCurrentSentence() {
        if (currentSentenceIndex < currentSentences.size) {
            val sentence = currentSentences[currentSentenceIndex]
            readingCallback?.onSentenceSpoken(currentSentenceIndex)

            val params = android.os.Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "sentence_$currentSentenceIndex")
            textToSpeech?.speak(sentence, TextToSpeech.QUEUE_FLUSH, params, "sentence_$currentSentenceIndex")
        } else {
            stopReading()
        }
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, PdfReaderActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, ReadingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Leggo")
            .setContentText("Lettura in corso...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        startForeground(1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Lettura vocale",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = currentLang
            textToSpeech?.setSpeechRate(currentSpeed)
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (isReading) {
                        nextSentence()
                    }
                }
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    override fun onDestroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "ReadingChannel"
        const val ACTION_STOP = "STOP_READING"
    }
}
