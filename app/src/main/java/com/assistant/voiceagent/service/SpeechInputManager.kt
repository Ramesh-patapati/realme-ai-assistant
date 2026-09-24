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

    var isListening: Boolean = false
        private set

    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        startListening(onResult) { _, message -> onError(message) }
    }

    fun startListening(
        onResult: (String) -> Unit,
        onError: (errorCode: Int, errorMessage: String) -> Unit
    ) {
        mainHandler.post {
            destroyRecognizer()

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError(SpeechRecognizer.ERROR_CLIENT, "Speech recognition not available on this device")
                return@post
            }

            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isListening = true
                            Log.d("SpeechInput", "Ready for speech...")
                        }

                        override fun onBeginningOfSpeech() {
                            Log.d("SpeechInput", "Speech started")
                        }

                        override fun onRmsChanged(rmsdB: Float) {}

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            Log.d("SpeechInput", "Speech ended")
                            isListening = false
                        }

                        override fun onError(error: Int) {
                            isListening = false
                            val message = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timed out"
                                SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition"
                                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                                else -> "Recognition error: $error"
                            }
                            Log.d("SpeechInput", "Recognition error $error: $message")
                            destroyRecognizer()
                            onError(error, message)
                        }

                        override fun onResults(results: Bundle?) {
                            isListening = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            destroyRecognizer()
                            if (!matches.isNullOrEmpty()) {
                                val recognized = matches[0]
                                Log.d("SpeechInput", "Recognized: $recognized")
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
                }

                speechRecognizer?.startListening(intent)
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

    fun destroyRecognizer() {
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.e("SpeechInput", "Error destroying speech recognizer", e)
            }
        }
    }
}
