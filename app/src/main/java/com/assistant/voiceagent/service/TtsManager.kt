package com.assistant.voiceagent.service

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

class TtsManager(private val context: Context, private val onInitSuccess: (() -> Unit)? = null) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private val pendingUtteranceCallbacks = mutableMapOf<String, () -> Unit>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ENGLISH
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                isReady = true
                setupUtteranceListener()
                onInitSuccess?.invoke()
            } else {
                Log.e("TtsManager", "TextToSpeech initialization failed: $status")
            }
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { id ->
                    pendingUtteranceCallbacks.remove(id)?.invoke()
                }
            }

            override fun onError(utteranceId: String?) {
                utteranceId?.let { id ->
                    pendingUtteranceCallbacks.remove(id)?.invoke()
                }
            }
        })
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isReady || tts == null) {
            Log.w("TtsManager", "TTS is not ready yet")
            onDone?.invoke()
            return
        }

        val utteranceId = UUID.randomUUID().toString()
        if (onDone != null) {
            pendingUtteranceCallbacks[utteranceId] = onDone
        }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun stop() {
        tts?.stop()
        pendingUtteranceCallbacks.clear()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
