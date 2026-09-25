package com.assistant.voiceagent.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

class WhatsAppNotificationService : NotificationListenerService() {

    private lateinit var ttsManager: TtsManager
    private lateinit var speechInputManager: SpeechInputManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastProcessedKey: String? = null
    private var lastProcessedTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        ttsManager = TtsManager(this)
        speechInputManager = SpeechInputManager(this)
        Log.d("NotificationService", "WhatsAppNotificationService created")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName != "com.whatsapp" && packageName != "com.whatsapp.w4b" && packageName != "com.google.android.gm") {
            return
        }

        // Avoid duplicate triggers within 3 seconds for the same notification key
        val currentTime = System.currentTimeMillis()
        if (sbn.key == lastProcessedKey && (currentTime - lastProcessedTime) < 3000) {
            return
        }

        val extras = sbn.notification.extras ?: return
        val sender = extras.getString(Notification.EXTRA_TITLE) ?: return
        val message = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        // Ignore summary/generic notification headers
        if (sender.equals("WhatsApp", ignoreCase = true) || message.contains("new messages", ignoreCase = true)) {
            return
        }

        lastProcessedKey = sbn.key
        lastProcessedTime = currentTime

        val appLabel = if (packageName.contains("whatsapp")) "WhatsApp message" else "Email"
        val announcement = "New $appLabel from $sender: $message. Would you like to reply?"

        mainHandler.post {
            pauseVoiceService()
            ttsManager.speak(announcement) {
                listenForVoiceReply(sbn, sender)
            }
        }
    }

    private fun pauseVoiceService() {
        try {
            val intent = Intent(this, LockScreenVoiceService::class.java).apply {
                action = LockScreenVoiceService.ACTION_PAUSE_LISTENING
            }
            startService(intent)
        } catch (e: Exception) {
            Log.w("NotificationService", "Failed to pause voice service: ${e.message}")
        }
    }

    private fun resumeVoiceService() {
        try {
            val intent = Intent(this, LockScreenVoiceService::class.java).apply {
                action = LockScreenVoiceService.ACTION_RESTART_LISTENING
            }
            startService(intent)
        } catch (e: Exception) {
            Log.w("NotificationService", "Failed to resume voice service: ${e.message}")
        }
    }

    private fun listenForVoiceReply(sbn: StatusBarNotification, sender: String) {
        mainHandler.postDelayed({
            speechInputManager.startListening(
                onResult = { recognizedText ->
                    val lower = recognizedText.lowercase()
                    if (lower in listOf("no", "stop", "close", "cancel", "never mind", "don't reply")) {
                        ttsManager.speak("Understood, closing now.") {
                            resumeVoiceService()
                        }
                    } else {
                        val replyText = recognizedText
                            .replace(Regex("^(reply|say)\\b\\s*", RegexOption.IGNORE_CASE), "")
                            .trim()
                        if (replyText.isBlank()) {
                            ttsManager.speak("I didn't hear a reply message.") {
                                resumeVoiceService()
                            }
                        } else {
                            val sent = sendQuickReply(sbn, replyText)
                            val confirmation = if (sent) {
                                "Replied to $sender: $replyText"
                            } else {
                                "Could not send reply directly."
                            }
                            ttsManager.speak(confirmation) {
                                resumeVoiceService()
                            }
                        }
                    }
                },
                onError = {
                    Log.d("NotificationService", "No reply spoken")
                    ttsManager.speak("No reply detected, closing for now.") {
                        resumeVoiceService()
                    }
                }
            )
        }, 300)
    }

    private fun sendQuickReply(sbn: StatusBarNotification, replyText: String): Boolean {
        val actions = sbn.notification.actions
        if (actions.isNullOrEmpty()) return false

        for (action in actions) {
            val remoteInputs = action.remoteInputs
            if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                val intent = Intent()
                val bundle = Bundle()
                for (input in remoteInputs) {
                    bundle.putCharSequence(input.resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
                try {
                    action.actionIntent.send(this, 0, intent)
                    return true
                } catch (e: Exception) {
                    Log.e("NotificationService", "Failed to send quick reply intent", e)
                }
            }
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
        speechInputManager.destroyRecognizer()
    }
}
