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

    private val lock = Any()
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isRecording = false

    @Volatile
    private var isPaused = false

    private var recordingThread: Thread? = null

    // Adaptive noise threshold
    private var baselineEnergy = 400.0
    private val consecutiveSpeechFramesNeeded = 4

    @SuppressLint("MissingPermission")
    fun startListening() {
        synchronized(lock) {
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
                    isRecording = false
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

                        var read = 0
                        synchronized(lock) {
                            if (!isPaused && isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                try {
                                    read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                                } catch (e: Exception) {
                                    Log.w("VoiceDetector", "AudioRecord read exception", e)
                                    read = 0
                                }
                            }
                        }

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

                            // Trigger threshold: 3.5x baseline and at least 1050 RMS
                            val triggerThreshold = (baselineEnergy * 3.5).coerceAtLeast(1050.0)

                            if (rms > triggerThreshold) {
                                speechFramesCount++
                                if (speechFramesCount >= consecutiveSpeechFramesNeeded) {
                                    Log.d("VoiceDetector", "Voice activity confirmed! RMS=$rms (Threshold=$triggerThreshold)")
                                    speechFramesCount = 0
                                    // Stop hardware capture immediately before dispatching callback
                                    pause()
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
                isRecording = false
            }
        }
    }

    /**
     * Pauses detection and stops the hardware AudioRecord stream.
     * Guaranteed to stop the hardware stream even if isPaused was already set.
     */
    fun pause() {
        synchronized(lock) {
            isPaused = true
            try {
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord?.stop()
                    Log.d("VoiceDetector", "AudioRecord stopped hardware stream on pause")
                }
            } catch (e: Exception) {
                Log.w("VoiceDetector", "Error stopping AudioRecord in pause", e)
            }
        }
    }

    /**
     * Resumes background detection and restarts the hardware AudioRecord stream.
     */
    fun resume() {
        synchronized(lock) {
            if (isRecording) {
                try {
                    if (audioRecord?.state == AudioRecord.STATE_INITIALIZED &&
                        audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING
                    ) {
                        audioRecord?.startRecording()
                        Log.d("VoiceDetector", "AudioRecord resumed hardware stream on resume")
                    }
                } catch (e: Exception) {
                    Log.w("VoiceDetector", "Error resuming AudioRecord", e)
                }
                isPaused = false
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            isRecording = false
            isPaused = false
            recordingThread?.interrupt()
            recordingThread = null

            try {
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord?.stop()
                }
                audioRecord?.release()
                audioRecord = null
                Log.d("VoiceDetector", "Continuous AudioRecord released")
            } catch (e: Exception) {
                Log.e("VoiceDetector", "Error stopping/releasing AudioRecord", e)
            }
        }
    }
}
