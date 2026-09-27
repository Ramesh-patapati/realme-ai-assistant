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
        Log.d("WhatsAppNotification", "WhatsAppNotificationService created and active")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName != "com.whatsapp" && packageName != "com.whatsapp.w4b" && packageName != "com.google.android.gm") {
            return
        }

        // Ignore group summary headers (only process actual child message notifications that contain RemoteInput)
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            Log.d("WhatsAppNotification", "Ignoring group summary notification from $packageName")
            return
        }

        // Avoid duplicate triggers within 3 seconds for the same notification key
        val currentTime = System.currentTimeMillis()
        if (sbn.key == lastProcessedKey && (currentTime - lastProcessedTime) < 3000) {
            return
        }

        val extras = sbn.notification.extras ?: return
        val sender = extras.getString(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getString(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: return

        val message = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull()?.toString()
            ?: return

        // Ignore summary/generic notification headers or call notifications
        if (sender.equals("WhatsApp", ignoreCase = true) ||
            message.contains("new messages", ignoreCase = true) ||
            message.contains("incoming call", ignoreCase = true) ||
            message.contains("missed call", ignoreCase = true)
        ) {
            Log.d("WhatsAppNotification", "Ignoring non-message header: sender=$sender, message=$message")
            return
        }

        lastProcessedKey = sbn.key
        lastProcessedTime = currentTime

        val appLabel = if (packageName.contains("whatsapp")) "WhatsApp message" else "Email"
        val announcement = "New $appLabel from $sender: $message. What should I reply?"

        Log.d("WhatsAppNotification", "Announcing incoming message from $sender: '$message'")

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
            Log.w("WhatsAppNotification", "Failed to pause voice service: ${e.message}")
        }
    }

    private fun resumeVoiceService() {
        try {
            val intent = Intent(this, LockScreenVoiceService::class.java).apply {
                action = LockScreenVoiceService.ACTION_RESTART_LISTENING
            }
            startService(intent)
        } catch (e: Exception) {
            Log.w("WhatsAppNotification", "Failed to resume voice service: ${e.message}")
        }
    }

    private fun listenForVoiceReply(sbn: StatusBarNotification, sender: String) {
        // Wait 450ms after TTS finishes speaking so the audio session cleanly switches to mic input
        mainHandler.postDelayed({
            Log.d("WhatsAppNotification", "Opening speech recognizer to capture user's voice reply...")
            speechInputManager.startListening(
                onResult = { recognizedText ->
                    Log.d("WhatsAppNotification", "Voice reply transcribed: '$recognizedText'")
                    val lower = recognizedText.lowercase().trim()
                    if (lower in listOf("no", "stop", "close", "cancel", "never mind", "don't reply", "no reply", "nothing")) {
                        ttsManager.speak("Understood, no reply sent.") {
                            resumeVoiceService()
                        }
                    } else {
                        val replyText = cleanReplyPrefixes(recognizedText)
                        if (replyText.isBlank()) {
                            ttsManager.speak("I didn't catch the reply message.") {
                                resumeVoiceService()
                            }
                        } else {
                            Log.d("WhatsAppNotification", "Sending quick reply to $sender: '$replyText'")
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
                onError = { errorMessage ->
                    Log.d("WhatsAppNotification", "Voice reply listening finished or timed out: $errorMessage")
                    ttsManager.speak("No reply heard, closing.") {
                        resumeVoiceService()
                    }
                }
            )
        }, 450)
    }

    companion object {
        fun cleanReplyPrefixes(rawText: String): String {
            var clean = rawText.trim()
            val prefixes = listOf(
                "yes tell him to ", "yes tell her to ", "yes tell them to ",
                "tell him to ", "tell her to ", "tell them to ",
                "yes tell him ", "yes tell her ", "yes tell them ",
                "tell him ", "tell her ", "tell them ",
                "yes reply ", "reply ", "say ", "send that ", "send "
            )
            for (prefix in prefixes) {
                if (clean.startsWith(prefix, ignoreCase = true)) {
                    clean = clean.substring(prefix.length).trim()
                    break
                }
            }
            return clean
        }
    }


    private fun sendQuickReply(sbn: StatusBarNotification, replyText: String): Boolean {
        val actions = sbn.notification.actions
        if (actions.isNullOrEmpty()) {
            Log.w("WhatsAppNotification", "No notification actions found on notification")
            return false
        }

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
                    Log.d("WhatsAppNotification", "Quick reply sent successfully via RemoteInput")
                    return true
                } catch (e: Exception) {
                    Log.e("WhatsAppNotification", "Failed to send quick reply intent", e)
                }
            }
        }
        Log.w("WhatsAppNotification", "No RemoteInput found among actions")
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
        speechInputManager.destroyRecognizer()
    }
}
