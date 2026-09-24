package com.assistant.voiceagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.SpeechRecognizer
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

    private var isListeningForActiveCommand = false
    private val restartHandler = Handler(Looper.getMainLooper())
    private var restartRunnable: Runnable? = null
    private var isServiceRunning = false

    // Watchdog: force-restarts listening if nothing happens for 25 seconds
    private var watchdogRunnable: Runnable? = null
    private var lastListeningActivity = 0L

    companion object {
        const val CHANNEL_ID = "ai_assistant_foreground_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_TRIGGER_VOICE_COMMAND = "com.assistant.voiceagent.TRIGGER_VOICE"
        const val ACTION_RESTART_LISTENING = "com.assistant.voiceagent.RESTART_LISTENING"
        private const val WATCHDOG_INTERVAL_MS = 25_000L
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

        Log.d("VoiceService", "LockScreenVoiceService initialized")
        startContinuousWakeListening()
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("VoiceService", "onStartCommand received action: ${intent?.action}")
        when (intent?.action) {
            ACTION_TRIGGER_VOICE_COMMAND -> {
                triggerVoiceInteraction()
            }
            ACTION_RESTART_LISTENING -> {
                Log.d("VoiceService", "Restarting continuous listening with updated wake phrase")
                startContinuousWakeListening()
            }
            "com.assistant.voiceagent.TEST_COMMAND" -> {
                val command = intent.getStringExtra("command") ?: "What is the capital of India?"
                Log.d("VoiceService", "Executing direct test command: $command")
                processUserSpokenCommand(command)
            }
            else -> {
                startContinuousWakeListening()
            }
        }
        return START_STICKY
    }

    private fun extractWakeWordAndCommand(speech: String, customWakeWord: String): Pair<Boolean, String> {
        val cleanSpeech = speech.lowercase().trim()
        val wakeWords = mutableListOf<String>()
        val cleanCustom = customWakeWord.lowercase().trim()
        if (cleanCustom.isNotBlank() && cleanCustom != "hey assistant") {
            wakeWords.add(cleanCustom)
        }
        wakeWords.addAll(listOf("hey jarvis", "ok jarvis", "hello jarvis", "jarvis", "hey assistant", "ok assistant", "hello assistant", "hey siri", "hello siri", "siripulse", "assistant"))

        for (wake in wakeWords) {
            if (cleanSpeech == wake) {
                return Pair(true, "")
            }
            if (cleanSpeech.startsWith("$wake ")) {
                val command = cleanSpeech.removePrefix("$wake ").trim()
                return Pair(true, command)
            }
            if (cleanSpeech.startsWith(wake)) {
                val remainder = cleanSpeech.removePrefix(wake).trim(' ', ',', '.', '!', '?')
                return Pair(true, remainder)
            }
        }
        return Pair(false, "")
    }

    fun startContinuousWakeListening() {
        if (!isServiceRunning || isListeningForActiveCommand) return
        restartRunnable?.let { restartHandler.removeCallbacks(it) }
        lastListeningActivity = System.currentTimeMillis()

        val prefs = getSharedPreferences("ai_assistant_prefs", Context.MODE_PRIVATE)
        val customWakeWord = prefs.getString("custom_wake_word", "hey jarvis") ?: "hey jarvis"
        Log.d("VoiceService", "startContinuousWakeListening: active wake phrase is '$customWakeWord'")

        speechInputManager.startContinuousListening(
            onResult = { recognizedText ->
                lastListeningActivity = System.currentTimeMillis()
                Log.d("VoiceService", "Continuous listening heard: '$recognizedText'")
                val (wakeDetected, command) = extractWakeWordAndCommand(recognizedText, customWakeWord)
                if (wakeDetected) {
                    if (command.isNotBlank()) {
                        Log.d("VoiceService", "Wake phrase + command in one breath: '$command'")
                        processUserSpokenCommand(command)
                    } else {
                        Log.d("VoiceService", "Wake phrase detected! Prompting...")
                        speechInputManager.destroyRecognizer()
                        ttsManager.speak("Yes, I'm listening!") {
                            // Delay slightly after TTS to let audio echo completely fade out before mic turns on
                            restartHandler.postDelayed({
                                listenForActiveCommand()
                            }, 450L)
                        }
                    }
                } else {
                    // Ambient speech that didn't match wake word -> smoothly resume listening
                    restartContinuousListening(300L)
                }
            },
            onError = { errorCode, errorMessage ->
                lastListeningActivity = System.currentTimeMillis()
                Log.d("VoiceService", "Continuous listening ended ($errorCode: $errorMessage). Rescheduling loop.")
                val delay = when (errorCode) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1200L
                    SpeechRecognizer.ERROR_AUDIO -> 1500L
                    else -> 400L
                }
                restartContinuousListening(delay)
            },
            muteBeep = true
        )
    }

    private fun restartContinuousListening(delayMs: Long) {
        if (!isServiceRunning || isListeningForActiveCommand) return
        restartRunnable?.let { restartHandler.removeCallbacks(it) }
        restartRunnable = Runnable {
            if (isServiceRunning && !isListeningForActiveCommand) {
                startContinuousWakeListening()
            }
        }
        restartHandler.postDelayed(restartRunnable!!, delayMs)
    }

    private fun startWatchdog() {
        watchdogRunnable = object : Runnable {
            override fun run() {
                if (!isServiceRunning) return
                val elapsed = System.currentTimeMillis() - lastListeningActivity
                if (elapsed > WATCHDOG_INTERVAL_MS && !isListeningForActiveCommand) {
                    Log.w("VoiceService", "WATCHDOG: Listening loop idle (${elapsed}ms). Refreshing listening session.")
                    restartRunnable?.let { restartHandler.removeCallbacks(it) }
                    speechInputManager.destroyRecognizer()
                    startContinuousWakeListening()
                }
                restartHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
        restartHandler.postDelayed(watchdogRunnable!!, WATCHDOG_INTERVAL_MS)
    }

    fun triggerVoiceInteraction(customPrompt: String = "How can I help you?") {
        speechInputManager.destroyRecognizer()
        ttsManager.speak(customPrompt) {
            restartHandler.postDelayed({
                listenForActiveCommand()
            }, 450L)
        }
    }

    fun listenForActiveCommand() {
        if (!isServiceRunning) return
        isListeningForActiveCommand = true
        lastListeningActivity = System.currentTimeMillis()
        restartRunnable?.let { restartHandler.removeCallbacks(it) }

        speechInputManager.startContinuousListening(
            onResult = { recognizedText ->
                isListeningForActiveCommand = false
                lastListeningActivity = System.currentTimeMillis()
                Log.d("VoiceService", "Active command heard: '$recognizedText'")
                processUserSpokenCommand(recognizedText)
            },
            onError = { errorCode, errorMessage ->
                isListeningForActiveCommand = false
                lastListeningActivity = System.currentTimeMillis()
                Log.w("VoiceService", "Active command error ($errorCode: $errorMessage)")
                // If it was just quietness/timeout, return to wake word standby without irritating prompt
                restartContinuousListening(500L)
            },
            muteBeep = false // Audible indicator when actively awaiting user command
        )
    }

    private fun processUserSpokenCommand(userSpeech: String) {
        Log.d("VoiceService", "processUserSpokenCommand: received speech '$userSpeech'")
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
                restartContinuousListening(500L)
            }
        }
    }

    private fun handleAIAction(action: AIAction) {
        Log.d("VoiceService", "Executing handleAIAction: $action")
        when (action) {
            is AIAction.Stop -> {
                speakAndResume("Stopping now. Let me know when you need me!")
            }

            is AIAction.Clarify -> {
                speechInputManager.destroyRecognizer()
                ttsManager.speak(action.question) {
                    restartHandler.postDelayed({
                        listenForActiveCommand()
                    }, 450L)
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
                        speakAndResume(action.speech)
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
                speakAndResume("Sending message to ${action.contactName}")
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
        isServiceRunning = false
        restartRunnable?.let { restartHandler.removeCallbacks(it) }
        watchdogRunnable?.let { restartHandler.removeCallbacks(it) }
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
