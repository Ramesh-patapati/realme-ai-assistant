package com.assistant.voiceagent.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class SpeechInputManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listenerGeneration = 0  // Prevents stale callbacks from interfering

    var isListening: Boolean = false
        private set

    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        startContinuousListening(onResult) { _, message -> onError(message) }
    }

    fun startContinuousListening(
        onResult: (String) -> Unit,
        onError: (errorCode: Int, errorMessage: String) -> Unit
    ) {
        mainHandler.post {
            // Synchronously destroy any existing recognizer first
            destroyRecognizerSync()

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError(SpeechRecognizer.ERROR_CLIENT, "Speech recognition not available on this device")
                return@post
            }

            val currentGeneration = ++listenerGeneration

            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            if (currentGeneration != listenerGeneration) return
                            isListening = true
                            Log.d("SpeechInput", "Ready for speech... (gen=$currentGeneration)")
                        }

                        override fun onBeginningOfSpeech() {
                            if (currentGeneration != listenerGeneration) return
                            Log.d("SpeechInput", "Speech started")
                        }

                        override fun onRmsChanged(rmsdB: Float) {}

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            if (currentGeneration != listenerGeneration) return
                            Log.d("SpeechInput", "Speech ended")
                            isListening = false
                        }

                        override fun onError(error: Int) {
                            if (currentGeneration != listenerGeneration) {
                                Log.d("SpeechInput", "Ignoring stale error from gen=$currentGeneration (current=$listenerGeneration)")
                                return
                            }
                            isListening = false
                            val message = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timed out"
                                SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition"
                                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                                else -> "Recognition error: $error"
                            }
                            Log.d("SpeechInput", "Recognition error $error: $message (gen=$currentGeneration)")
                            // Do NOT destroy here — let the caller restart
                            onError(error, message)
                        }

                        override fun onResults(results: Bundle?) {
                            if (currentGeneration != listenerGeneration) {
                                Log.d("SpeechInput", "Ignoring stale result from gen=$currentGeneration (current=$listenerGeneration)")
                                return
                            }
                            isListening = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                val recognized = matches[0]
                                Log.d("SpeechInput", "Recognized: $recognized (gen=$currentGeneration)")
                                onResult(recognized)
                            } else {
                                onError(SpeechRecognizer.ERROR_NO_MATCH, "No speech recognized")
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    // Keep listening longer before timeout
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
                }

                speechRecognizer?.startListening(intent)
                Log.d("SpeechInput", "SpeechRecognizer.startListening called (gen=$currentGeneration)")
            } catch (e: Exception) {
                isListening = false
                Log.e("SpeechInput", "Failed to start listening", e)
                onError(SpeechRecognizer.ERROR_CLIENT, "Failed to start speech recognizer: ${e.message}")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e("SpeechInput", "Error stopping listening", e)
            }
        }
    }

    /**
     * Synchronous destroy — must be called from main thread.
     * This avoids race conditions where a posted destroyRecognizer kills a newly created one.
     */
    private fun destroyRecognizerSync() {
        try {
            isListening = false
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e("SpeechInput", "Error destroying speech recognizer", e)
            speechRecognizer = null
        }
    }

    fun destroyRecognizer() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyRecognizerSync()
        } else {
            mainHandler.post { destroyRecognizerSync() }
        }
    }
}
