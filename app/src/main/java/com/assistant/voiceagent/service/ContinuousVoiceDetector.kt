package com.assistant.voiceagent.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

class ContinuousVoiceDetector(
    private val context: Context,
    private val onVoiceActivityDetected: () -> Unit
) {

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(sampleRate / 2)

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var isPaused = false
    private var recordingThread: Thread? = null

    // Adaptive noise threshold
    private var baselineEnergy = 400.0
    private val consecutiveSpeechFramesNeeded = 3

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (isRecording) return
        isRecording = true
        isPaused = false

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("VoiceDetector", "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            Log.d("VoiceDetector", "Continuous AudioRecord started successfully (Zero buzzer/clicks)")

            recordingThread = Thread({
                val buffer = ShortArray(1024)
                var speechFramesCount = 0

                while (isRecording) {
                    if (isPaused) {
                        try {
                            Thread.sleep(100)
                        } catch (e: InterruptedException) {
                            break
                        }
                        continue
                    }

                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += (buffer[i] * buffer[i]).toDouble()
                        }
                        val rms = sqrt(sum / read)

                        // Adapt ambient baseline slowly
                        if (rms < baselineEnergy * 1.5) {
                            baselineEnergy = (baselineEnergy * 0.95) + (rms * 0.05)
                        }

                        // Trigger threshold: 3.5x baseline and at least 900 RMS
                        val triggerThreshold = (baselineEnergy * 3.5).coerceAtLeast(900.0)

                        if (rms > triggerThreshold) {
                            speechFramesCount++
                            if (speechFramesCount >= consecutiveSpeechFramesNeeded) {
                                Log.d("VoiceDetector", "Voice activity confirmed! RMS=$rms (Threshold=$triggerThreshold)")
                                speechFramesCount = 0
                                isPaused = true
                                onVoiceActivityDetected()
                            }
                        } else {
                            if (speechFramesCount > 0) {
                                speechFramesCount--
                            }
                        }
                    }
                }
            }, "ContinuousVoiceDetectorThread").apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }
        } catch (e: Exception) {
            Log.e("VoiceDetector", "Failed to start AudioRecord", e)
        }
    }

    fun pause() {
        isPaused = true
    }

    fun resume() {
        isPaused = false
    }

    fun stop() {
        isRecording = false
        isPaused = false
        recordingThread?.interrupt()
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.d("VoiceDetector", "Continuous AudioRecord stopped")
        } catch (e: Exception) {
            Log.e("VoiceDetector", "Error stopping AudioRecord", e)
        }
    }
}
