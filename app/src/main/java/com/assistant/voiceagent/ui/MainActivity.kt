package com.assistant.voiceagent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.assistant.voiceagent.databinding.ActivityMainBinding
import com.assistant.voiceagent.service.LockScreenVoiceService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingServiceStart = false
    private var pendingServiceAction: String? = null

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
            if (pendingServiceStart) {
                val action = pendingServiceAction
                pendingServiceStart = false
                pendingServiceAction = null
                startVoiceService(action)
            }
        } else {
            pendingServiceStart = false
            pendingServiceAction = null
            Toast.makeText(this, "Permissions needed for voice and phone actions", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSavedSettings()
        setupListeners()
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("ai_assistant_prefs", Context.MODE_PRIVATE)
        binding.etGeminiKey.setText(prefs.getString("gemini_api_key", ""))
        binding.etOpenAiKey.setText(prefs.getString("openai_api_key", ""))

        val preferredEngine = prefs.getString("preferred_ai_engine", "gemini")
        if (preferredEngine == "chatgpt") {
            binding.rbChatGpt.isChecked = true
        } else {
            binding.rbGemini.isChecked = true
        }

        val customWakeWord = prefs.getString("custom_wake_word", "hey jarvis") ?: "hey jarvis"
        binding.tvCurrentWakeWord.text = "Active Wake Phrase: '$customWakeWord'"
        binding.etCustomWakeWord.setText(customWakeWord)
    }

    private fun saveWakeWord(wakeWord: String) {
        val clean = wakeWord.trim().lowercase()
        getSharedPreferences("ai_assistant_prefs", Context.MODE_PRIVATE).edit().apply {
            putString("custom_wake_word", clean)
            putBoolean("voice_enrolled", true)
            apply()
        }
        binding.tvCurrentWakeWord.text = "Active Wake Phrase: '$clean'"
        binding.etCustomWakeWord.setText(clean)
        Toast.makeText(this, "Wake phrase saved: '$clean'", Toast.LENGTH_SHORT).show()

        // Notify/start the service through the same permission gate as the other buttons.
        startVoiceService(LockScreenVoiceService.ACTION_RESTART_LISTENING)
    }

    private fun setupListeners() {
        // Quick Talk to Jarvis Button
        binding.btnQuickTalk.setOnClickListener {
            startVoiceService(LockScreenVoiceService.ACTION_TRIGGER_VOICE_COMMAND)
        }

        // Preset Wake Word Buttons
        binding.btnPresetJarvis.setOnClickListener {
            saveWakeWord("hey jarvis")
        }

        binding.btnPresetJarvisShort.setOnClickListener {
            saveWakeWord("jarvis")
        }

        binding.btnPresetAssistant.setOnClickListener {
            saveWakeWord("hey assistant")
        }

        // Custom Wake Word Save
        binding.btnSaveWakeWord.setOnClickListener {
            val text = binding.etCustomWakeWord.text?.toString()?.trim() ?: "hey jarvis"
            saveWakeWord(text)
        }

        // Save AI Engine Keys
        binding.btnSaveKeys.setOnClickListener {
            val geminiKey = binding.etGeminiKey.text?.toString()?.trim() ?: ""
            val openAiKey = binding.etOpenAiKey.text?.toString()?.trim() ?: ""
            val preferred = if (binding.rbChatGpt.isChecked) "chatgpt" else "gemini"

            getSharedPreferences("ai_assistant_prefs", Context.MODE_PRIVATE).edit().apply {
                putString("gemini_api_key", geminiKey)
                putString("openai_api_key", openAiKey)
                putString("preferred_ai_engine", preferred)
                apply()
            }
            Toast.makeText(this, "Settings saved successfully!", Toast.LENGTH_SHORT).show()
        }

        // Restart Background Service
        binding.btnToggleService.setOnClickListener {
            startVoiceService(null)
        }

        // Permissions
        binding.btnNotificationAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnStandardPerms.setOnClickListener {
            checkAndRequestAppPermissions()
        }

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnOverlayPermission.setOnClickListener {
            openOverlayPermissionSettings()
        }

        binding.btnBatterySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun checkAndRequestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun startVoiceService(action: String?) {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required += Manifest.permission.POST_NOTIFICATIONS
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            pendingServiceStart = true
            pendingServiceAction = action
            requestPermissionsLauncher.launch(missing.toTypedArray())
            return
        }

        val serviceIntent = Intent(this, LockScreenVoiceService::class.java).apply {
            this.action = action
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            binding.tvServiceStatus.text = "● Service: Active & Listening"
            Toast.makeText(
                this,
                if (action == LockScreenVoiceService.ACTION_TRIGGER_VOICE_COMMAND) {
                    "Listening for your command..."
                } else {
                    "Jarvis background service started"
                },
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            binding.tvServiceStatus.text = "● Service: Not running"
            Toast.makeText(this, "Could not start Jarvis: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openOverlayPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Background app-launch permission is already enabled", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Open Android Settings and allow Display over other apps for Jarvis", Toast.LENGTH_LONG).show()
        }
    }
}
