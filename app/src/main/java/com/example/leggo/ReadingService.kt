package com.example.leggo

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
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
        tts?.shutdown()
        super.onDestroy()
    }
}