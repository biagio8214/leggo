package com.example.leggo

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class ReadingService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val binder = LocalBinder()
    private var callback: ReadingCallback? = null
    private var sentences: List<String> = emptyList()
    private var currentIndex = 0
    private var isReading = false
    private var speed = 1.0f
    private var language = Locale.ITALIAN
    private var wakeLock: PowerManager.WakeLock? = null

    interface ReadingCallback {
        fun onBlockSpoken(startIndex: Int)
        fun onWordRangeSpoken(blockIndex: Int, start: Int, end: Int)
        fun onReadingStopped()
        fun onReadingFinished()
    }

    inner class LocalBinder : Binder() {
        fun getService(): ReadingService = this@ReadingService
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        startForegroundNotification()
    }

    private fun startForegroundNotification() {
        val channelId = "leggo_reading_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Lettura in corso", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Leggo - Lettura in corso")
                .setContentText("Lettura audiolibro attiva in background")
                .setSmallIcon(R.drawable.ic_media_play)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Leggo - Lettura in corso")
                .setContentText("Lettura audiolibro attiva in background")
                .setSmallIcon(R.drawable.ic_media_play)
                .build()
        }
        startForeground(1, notification)
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Leggo::ReadingServiceWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(15 * 60 * 1000L) // 15 minutes timeout safety
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            updateTtsParams()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (isReading) {
                        currentIndex++
                        if (currentIndex < sentences.size) {
                            speakNext()
                        } else {
                            isReading = false
                            releaseWakeLock()
                            callback?.onReadingFinished()
                        }
                    }
                }
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    private fun updateTtsParams() {
        tts?.language = language
        tts?.setSpeechRate(speed)
    }

    override fun onBind(intent: Intent): IBinder = binder

    fun setCallback(cb: ReadingCallback) { callback = cb }

    fun setSentences(newSentences: List<String>, startIndex: Int) {
        sentences = newSentences
        currentIndex = startIndex
    }

    fun startReading() {
        if (sentences.isNotEmpty() && currentIndex < sentences.size) {
            isReading = true
            acquireWakeLock()
            speakNext()
        }
    }

    private fun speakNext() {
        if (currentIndex < sentences.size) {
            val text = sentences[currentIndex]
            callback?.onBlockSpoken(currentIndex)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "id_$currentIndex")
        }
    }

    fun stopReading() {
        isReading = false
        releaseWakeLock()
        if (tts != null) {
            tts!!.stop()
        }
        callback?.onReadingStopped()
    }

    fun isReading() = isReading

    fun skipForward(amount: Int) {
        if (currentIndex + amount < sentences.size) {
            currentIndex += amount
            if (isReading) speakNext()
        }
    }

    fun skipBackward(amount: Int) {
        if (currentIndex - amount >= 0) {
            currentIndex -= amount
            if (isReading) speakNext()
        }
    }

    fun updateSettings(newSpeed: Float, langName: String, voiceName: String?) {
        speed = newSpeed
        language = when(langName) {
            "Inglese" -> Locale.ENGLISH
            "Francese" -> Locale.FRENCH
            "Tedesco" -> Locale.GERMAN
            "Spagnolo" -> Locale("es", "ES")
            else -> Locale.ITALIAN
        }
        
        tts?.let { t ->
            t.language = language
            t.setSpeechRate(speed)
            
            if (voiceName != null) {
                t.voices?.firstOrNull { it.name == voiceName }?.let { voice ->
                    t.voice = voice
                }
            }
        }
    }

    fun getCurrentSentenceIndex() = currentIndex

    override fun onDestroy() {
        releaseWakeLock()
        tts?.shutdown()
        super.onDestroy()
    }
}
