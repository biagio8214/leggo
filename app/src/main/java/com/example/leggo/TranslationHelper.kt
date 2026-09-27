package com.example.leggo

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

object TranslationHelper {
    fun translate(text: String, sourceLang: String?, targetLang: String, onSuccess: (String) -> Unit, onError: () -> Unit) {
        try {
            val target = TranslateLanguage.fromLanguageTag(targetLang) ?: TranslateLanguage.ITALIAN
            val source = if (sourceLang != null) TranslateLanguage.fromLanguageTag(sourceLang) else TranslateLanguage.ENGLISH

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source ?: TranslateLanguage.ENGLISH)
                .setTargetLanguage(target)
                .build()

            val translator = Translation.getClient(options)
            val conditions = DownloadConditions.Builder().requireWifi().build()

            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    translator.translate(text)
                        .addOnSuccessListener { translatedText -> 
                            onSuccess(translatedText) 
                        }
                        .addOnFailureListener { 
                            onError() 
                        }
                }
                .addOnFailureListener { 
                    onError() 
                }
        } catch (e: Exception) {
            onError()
        }
    }
}