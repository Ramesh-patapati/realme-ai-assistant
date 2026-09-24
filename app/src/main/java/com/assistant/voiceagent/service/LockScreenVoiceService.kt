package com.assistant.voiceagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.assistant.voiceagent.model.AIAction
import com.assistant.voiceagent.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LockScreenVoiceService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var ttsManager: TtsManager
    private lateinit var speechInputManager: SpeechInputManager
    private lateinit var aiEngine: AIEngine
    private lateinit var phoneActionsManager: PhoneActionsManager
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "ai_assistant_foreground_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_TRIGGER_VOICE_COMMAND = "com.assistant.voiceagent.TRIGGER_VOICE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AIAssistant:VoiceWakeLock")

        ttsManager = TtsManager(this)
        speechInputManager = SpeechInputManager(this)
        aiEngine = AIEngine(this)
        phoneActionsManager = PhoneActionsManager(this)

        Log.d("VoiceService", "LockScreenVoiceService initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TRIGGER_VOICE_COMMAND) {
            triggerVoiceInteraction()
        }
        return START_STICKY
    }

    fun triggerVoiceInteraction(customPrompt: String = "How can I help you?") {
        wakeLock?.acquire(15000L)
        ttsManager.speak(customPrompt) {
            startListeningLoop()
        }
    }

    private fun startListeningLoop() {
        speechInputManager.startListening(
            onResult = { recognizedText ->
                processUserSpokenCommand(recognizedText)
            },
            onError = { error ->
                Log.w("VoiceService", "Voice recognition ended: $error")
                ttsManager.speak("I didn't hear anything, so I'm going to sleep now.") {
                    releaseWakeLock()
                }
            }
        )
    }

    private fun processUserSpokenCommand(userSpeech: String) {
        val prefs = getSharedPreferences("ai_assistant_prefs", Context.MODE_PRIVATE)
        val geminiKey = prefs.getString("gemini_api_key", "") ?: ""
        val openAiKey = prefs.getString("openai_api_key", "") ?: ""
        val aiEngineChoice = prefs.getString("preferred_ai_engine", "gemini") ?: "gemini"

        serviceScope.launch {
            val action = aiEngine.processCommand(userSpeech, aiEngineChoice, geminiKey, openAiKey)
            handleAIAction(action)
        }
    }

    private fun handleAIAction(action: AIAction) {
        when (action) {
            is AIAction.Stop -> {
                ttsManager.speak("Stopping now. Let me know when you need me!") {
                    releaseWakeLock()
                }
            }

            is AIAction.Clarify -> {
                ttsManager.speak(action.question) {
                    startListeningLoop()
                }
            }

            is AIAction.Call -> {
                val result = phoneActionsManager.makeCall(action.contactName)
                when (result) {
                    is PhoneActionsManager.ActionResult.Success -> {
                        ttsManager.speak("Calling ${action.contactName}") {
                            releaseWakeLock()
                        }
                    }
                    is PhoneActionsManager.ActionResult.PermissionNeeded -> {
                        // Openly speak about missing permission and open the settings!
                        ttsManager.speak(result.spokenExplanation) {
                            startActivity(result.settingsIntent)
                            releaseWakeLock()
                        }
                    }
                    is PhoneActionsManager.ActionResult.Failure -> {
                        ttsManager.speak(result.reason) {
                            releaseWakeLock()
                        }
                    }
                }
            }

            is AIAction.DeviceControl -> {
                val result = phoneActionsManager.executeDeviceControl(action.command)
                when (result) {
                    is PhoneActionsManager.ActionResult.Success -> {
                        ttsManager.speak(action.speech) {
                            releaseWakeLock()
                        }
                    }
                    is PhoneActionsManager.ActionResult.PermissionNeeded -> {
                        ttsManager.speak(result.spokenExplanation) {
                            startActivity(result.settingsIntent)
                            releaseWakeLock()
                        }
                    }
                    is PhoneActionsManager.ActionResult.Failure -> {
                        ttsManager.speak("Could not perform that action.") {
                            releaseWakeLock()
                        }
                    }
                }
            }

            is AIAction.PlayYouTube -> {
                ttsManager.speak("Playing ${action.songOrQuery} on YouTube") {
                    phoneActionsManager.playYouTube(action.songOrQuery)
                    releaseWakeLock()
                }
            }

            is AIAction.OrderFood -> {
                val restaurantText = if (action.restaurant != null) " from ${action.restaurant}" else ""
                ttsManager.speak("Opening Zomato for ${action.item}$restaurantText") {
                    phoneActionsManager.openZomato(action.item, action.restaurant)
                    releaseWakeLock()
                }
            }

            is AIAction.SendWhatsApp -> {
                ttsManager.speak("Sending message to ${action.contactName}") {
                    releaseWakeLock()
                }
            }

            is AIAction.Answer -> {
                ttsManager.speak(action.replyText) {
                    releaseWakeLock()
                }
            }

            is AIAction.Unknown -> {
                ttsManager.speak(action.rawText) {
                    releaseWakeLock()
                }
            }
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Assistant Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the AI assistant alive for lock-screen commands"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val triggerIntent = Intent(this, LockScreenVoiceService::class.java).apply {
            action = ACTION_TRIGGER_VOICE_COMMAND
        }
        val triggerPendingIntent = PendingIntent.getService(
            this,
            1,
            triggerIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Voice Assistant Active")
            .setContentText("Listening for lock-screen voice and messages")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, "Talk Now", triggerPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel()
        ttsManager.shutdown()
        speechInputManager.destroyRecognizer()
    }
}
