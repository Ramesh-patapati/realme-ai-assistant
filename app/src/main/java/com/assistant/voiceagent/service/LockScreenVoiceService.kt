package com.assistant.voiceagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    private lateinit var voiceDetector: ContinuousVoiceDetector
    private lateinit var aiEngine: AIEngine
    private lateinit var phoneActionsManager: PhoneActionsManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isBusy = false
    private var isServiceRunning = false

    companion object {
        const val CHANNEL_ID = "ai_assistant_foreground_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_TRIGGER_VOICE_COMMAND = "com.assistant.voiceagent.TRIGGER_VOICE"
        const val ACTION_RESTART_LISTENING = "com.assistant.voiceagent.RESTART_LISTENING"
        const val ACTION_PAUSE_LISTENING = "com.assistant.voiceagent.PAUSE_LISTENING"
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AIAssistant:VoiceWakeLock")
        try {
            wakeLock?.acquire()
        } catch (e: Exception) {
            Log.w("VoiceService", "Could not acquire wakeLock: ${e.message}")
        }

        ttsManager = TtsManager(this)
        speechInputManager = SpeechInputManager(this)
        aiEngine = AIEngine(this)
        phoneActionsManager = PhoneActionsManager(this)

        // Keep one continuous AudioRecord stream and start SpeechRecognizer only after
        // a sustained sound onset; OEM recognition tones may still depend on the device.
        voiceDetector = ContinuousVoiceDetector(this) {
            mainHandler.post {
                onVoiceActivityDetected()
            }
        }

        Log.d("VoiceService", "LockScreenVoiceService initialized")
        voiceDetector.startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("VoiceService", "onStartCommand received action: ${intent?.action}")
        when (intent?.action) {
            ACTION_TRIGGER_VOICE_COMMAND -> {
                triggerVoiceInteraction()
            }
            ACTION_RESTART_LISTENING -> {
                resumeBackgroundListening()
            }
            ACTION_PAUSE_LISTENING -> {
                isBusy = true
                voiceDetector.pause()
                speechInputManager.destroyRecognizer()
                Log.d("VoiceService", "Voice service paused by external notification service")
            }
            "com.assistant.voiceagent.TEST_COMMAND" -> {
                val command = intent.getStringExtra("command") ?: "What is the capital of India?"
                Log.d("VoiceService", "Executing direct test command: $command")
                processUserSpokenCommand(command)
            }
            else -> {
                if (!isBusy) {
                    voiceDetector.resume()
                }
            }
        }
        return START_STICKY
    }

    private fun extractWakeWordAndCommand(speech: String, customWakeWord: String): Pair<Boolean, String> {
        val cleanSpeech = speech.lowercase().trim().replace(Regex("[.,?!]"), "")
        val wakeWords = mutableListOf<String>()
        val cleanCustom = customWakeWord.lowercase().trim().replace(Regex("[.,?!]"), "")
        if (cleanCustom.isNotBlank() && cleanCustom != "hey jarvis") {
            wakeWords.add(cleanCustom)
        }
        wakeWords.addAll(listOf("hey jarvis", "ok jarvis", "hello jarvis", "jarvis", "hey assistant", "ok assistant", "hello assistant", "hey siri", "hello siri", "assistant"))

        for (wake in wakeWords) {
            if (cleanSpeech == wake) {
                return Pair(true, "")
            }
            if (cleanSpeech.startsWith("$wake ")) {
                val command = cleanSpeech.removePrefix("$wake ").trim()
                return Pair(true, command)
            }
            if (cleanSpeech.startsWith(wake)) {
                val remainder = cleanSpeech.removePrefix(wake).trim()
                return Pair(true, remainder)
            }
            val idx = cleanSpeech.indexOf(wake)
            if (idx >= 0) {
                val remainder = (cleanSpeech.substring(0, idx) + " " + cleanSpeech.substring(idx + wake.length)).trim()
                return Pair(true, remainder)
            }
        }
        return Pair(false, "")
    }

    private fun onVoiceActivityDetected() {
        if (!isServiceRunning || isBusy) return

        isBusy = true
        voiceDetector.pause()
        Log.d("VoiceService", "Audio energy detected. Starting silent wake-word verification...")

        val prefs = getSharedPreferences("ai_assistant_prefs", Context.MODE_PRIVATE)
        val customWakeWord = prefs.getString("custom_wake_word", "hey jarvis") ?: "hey jarvis"

        // 2. SILENT recognition session: Verify wake word WITHOUT speaking "Yes, I'm listening!"
        speechInputManager.startRecognitionSession(
            onResult = { recognizedText ->
                Log.d("VoiceService", "Heard during audio detection: '$recognizedText'")
                val (wakeWordMatched, cleanCommand) = extractWakeWordAndCommand(recognizedText, customWakeWord)
                if (wakeWordMatched) {
                    wakeScreen()
                    if (cleanCommand.isNotBlank()) {
                        // User spoke the full command: "Hey Jarvis, call Mom" or "Hey Jarvis, what is my battery"
                        processUserSpokenCommand(cleanCommand)
                    } else {
                        // User only said the wake word: "Hey Jarvis"
                        speakAndResume("Yes, I'm listening!") {
                            mainHandler.postDelayed({
                                listenForActiveCommand()
                            }, 300L)
                        }
                    }
                } else {
                    // Never execute a command unless the transcript contains the wake
                    // phrase. Ambient speech and TV audio must not trigger phone actions.
                    Log.d("VoiceService", "No wake word found in '$recognizedText'. Remaining silent.")
                    resumeBackgroundListening()
                }
            },
            onError = { errorCode, errorMessage ->
                Log.d("VoiceService", "Silent verification finished without speech ($errorCode: $errorMessage).")
                resumeBackgroundListening()
            }
        )
    }

    private fun wakeScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val screenLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "AIAssistant:ScreenWakeLock"
            )
            screenLock.acquire(4000L)
            Log.d("VoiceService", "Screen awakened from lock/doze state")
        } catch (e: Exception) {
            Log.w("VoiceService", "Could not wake screen: ${e.message}")
        }
    }

    fun triggerVoiceInteraction(customPrompt: String = "How can I help you?") {
        isBusy = true
        voiceDetector.pause()
        speechInputManager.destroyRecognizer()
        ttsManager.speak(customPrompt) {
            mainHandler.postDelayed({
                listenForActiveCommand()
            }, 350L)
        }
    }

    private fun listenForActiveCommand() {
        if (!isServiceRunning) return
        isBusy = true

        speechInputManager.startRecognitionSession(
            onResult = { recognizedText ->
                Log.d("VoiceService", "Active command heard: '$recognizedText'")
                val prefs = getSharedPreferences("ai_assistant_prefs", Context.MODE_PRIVATE)
                val customWakeWord = prefs.getString("custom_wake_word", "hey jarvis") ?: "hey jarvis"
                val (wakeWordMatched, cleanCommand) = extractWakeWordAndCommand(recognizedText, customWakeWord)
                val finalCommand = if (wakeWordMatched && cleanCommand.isNotBlank()) cleanCommand else recognizedText
                if (finalCommand.isNotBlank() && (!wakeWordMatched || cleanCommand.isNotBlank())) {
                    processUserSpokenCommand(finalCommand)
                } else {
                    // Spoke only wake word again
                    speakAndResume("How can I assist you?") {
                        mainHandler.postDelayed({
                            listenForActiveCommand()
                        }, 300L)
                    }
                }
            },
            onError = { errorCode, errorMessage ->
                Log.w("VoiceService", "Active command error ($errorCode: $errorMessage)")
                resumeBackgroundListening()
            }
        )
    }


    private fun processUserSpokenCommand(userSpeech: String) {
        Log.d("VoiceService", "processUserSpokenCommand: processing '$userSpeech'")
        val prefs = getSharedPreferences("ai_assistant_prefs", Context.MODE_PRIVATE)
        val geminiKey = prefs.getString("gemini_api_key", "") ?: ""
        val openAiKey = prefs.getString("openai_api_key", "") ?: ""
        val aiEngineChoice = prefs.getString("preferred_ai_engine", "gemini") ?: "gemini"

        serviceScope.launch {
            val action = aiEngine.processCommand(userSpeech, aiEngineChoice, geminiKey, openAiKey)
            Log.d("VoiceService", "AI parsed action: $action")
            handleAIAction(action)
        }
    }

    private fun speakAndResume(text: String, onFinished: (() -> Unit)? = null) {
        speechInputManager.destroyRecognizer()
        ttsManager.speak(text) {
            try {
                onFinished?.invoke()
            } finally {
                resumeBackgroundListening()
            }
        }
    }

    private fun resumeBackgroundListening() {
        isBusy = false
        mainHandler.postDelayed({
            if (isServiceRunning && !isBusy) {
                voiceDetector.resume()
                Log.d("VoiceService", "Background voice detector resumed.")
            }
        }, 300L)
    }

    private suspend fun handleAIAction(action: AIAction) {
        Log.d("VoiceService", "Executing handleAIAction: $action")
        wakeScreen()
        when (action) {
            is AIAction.Stop -> {
                speakAndResume("Stopping now. Let me know when you need me!")
            }

            is AIAction.Clarify -> {
                speechInputManager.destroyRecognizer()
                ttsManager.speak(action.question) {
                    mainHandler.postDelayed({
                        listenForActiveCommand()
                    }, 350L)
                }
            }

            is AIAction.Call -> {
                val result = phoneActionsManager.makeCall(action.contactName)
                when (result) {
                    is PhoneActionsManager.ActionResult.Success -> {
                        speakAndResume("Calling ${action.contactName}")
                    }
                    is PhoneActionsManager.ActionResult.PermissionNeeded -> {
                        speakAndResume(result.spokenExplanation) {
                            startActivity(result.settingsIntent)
                        }
                    }
                    is PhoneActionsManager.ActionResult.Failure -> {
                        speakAndResume(result.reason)
                    }
                }
            }

            is AIAction.DeviceControl -> {
                val result = phoneActionsManager.executeDeviceControl(action.command)
                when (result) {
                    is PhoneActionsManager.ActionResult.Success -> {
                        val speechText = action.speech.ifBlank { result.message }
                        speakAndResume(speechText)
                    }
                    is PhoneActionsManager.ActionResult.PermissionNeeded -> {
                        speakAndResume(result.spokenExplanation) {
                            startActivity(result.settingsIntent)
                        }
                    }
                    is PhoneActionsManager.ActionResult.Failure -> {
                        speakAndResume("Could not perform that action.")
                    }
                }
            }

            is AIAction.PlayYouTube -> {
                speakAndResume("Playing ${action.songOrQuery} on YouTube") {
                    phoneActionsManager.playYouTube(action.songOrQuery)
                }
            }

            is AIAction.OrderFood -> {
                val restaurantText = if (action.restaurant != null) " from ${action.restaurant}" else ""
                speakAndResume("Opening Zomato for ${action.item}$restaurantText") {
                    phoneActionsManager.openZomato(action.item, action.restaurant)
                }
            }

            is AIAction.SendWhatsApp -> {
                val result = phoneActionsManager.sendWhatsApp(action.contactName, action.message)
                when (result) {
                    is PhoneActionsManager.ActionResult.Success -> {
                        speakAndResume(result.message)
                    }
                    is PhoneActionsManager.ActionResult.Failure -> {
                        speakAndResume(result.reason)
                    }
                    is PhoneActionsManager.ActionResult.PermissionNeeded -> {
                        speakAndResume(result.spokenExplanation) {
                            startActivity(result.settingsIntent)
                        }
                    }
                }
            }

            is AIAction.OpenApp -> {
                speakAndResume("Opening ${action.appName}") {
                    phoneActionsManager.openApp(action.appName)
                }
            }

            is AIAction.Answer -> {
                speakAndResume(action.replyText)
            }

            is AIAction.Unknown -> {
                speakAndResume(action.rawText)
            }
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
            .setContentText("Listening for 'Hey Jarvis' or lock-screen commands")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, "Talk Now", triggerPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        voiceDetector.stop()
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) { /* ignore */ }
        }
        serviceScope.cancel()
        ttsManager.shutdown()
        speechInputManager.destroyRecognizer()
    }
}
