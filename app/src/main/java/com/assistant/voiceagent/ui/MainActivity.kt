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

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Standard permissions granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permissions needed for voice and calls", Toast.LENGTH_SHORT).show()
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
    }

    private fun setupListeners() {
        // Save API Keys
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

        // Toggle Background Service
        binding.btnToggleService.setOnClickListener {
            val serviceIntent = Intent(this, LockScreenVoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            binding.tvServiceStatus.text = "● Service: Active & Running"
            Toast.makeText(this, "AI Assistant is running in background", Toast.LENGTH_SHORT).show()
        }

        // 1. Notification Access Button
        binding.btnNotificationAccess.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        // 2. Microphone & Calls Permission Button
        binding.btnStandardPerms.setOnClickListener {
            checkAndRequestAppPermissions()
        }

        // 3. Accessibility for Total Phone Control (Taps, Typing, Home, Back)
        binding.btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // 4. Realme Battery Optimization Exemption
        binding.btnBatterySettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        }

        // Live Voice Test FAB
        binding.fabMicTest.setOnClickListener {
            val triggerIntent = Intent(this, LockScreenVoiceService::class.java).apply {
                action = LockScreenVoiceService.ACTION_TRIGGER_VOICE_COMMAND
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(triggerIntent)
            } else {
                startService(triggerIntent)
            }
            binding.tvTestStatus.text = "Listening for your voice command..."
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

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            requestPermissionsLauncher.launch(needed.toTypedArray())
        } else {
            Toast.makeText(this, "Standard permissions already granted!", Toast.LENGTH_SHORT).show()
        }
    }
}
