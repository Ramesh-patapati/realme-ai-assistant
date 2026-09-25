package com.assistant.voiceagent.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.os.BatteryManager
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat

class PhoneActionsManager(private val context: Context) {

    sealed class ActionResult {
        data class Success(val message: String) : ActionResult()
        data class PermissionNeeded(val spokenExplanation: String, val settingsIntent: Intent) : ActionResult()
        data class Failure(val reason: String) : ActionResult()
    }

    fun makeCall(contactNameOrNumber: String): ActionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return ActionResult.PermissionNeeded(
                "I need permission to make phone calls. Please tap allow in the settings screen I just opened.",
                intent
            )
        }

        val phoneNumber = if (contactNameOrNumber.matches(Regex("^[0-9+ ]+$"))) {
            contactNameOrNumber
        } else {
            val found = findPhoneNumberByName(contactNameOrNumber)
            if (found is FindResult.PermissionNeeded) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                return ActionResult.PermissionNeeded(
                    "I need permission to read your contacts to find $contactNameOrNumber. Opening settings now.",
                    intent
                )
            } else if (found is FindResult.Found) {
                found.number
            } else {
                return ActionResult.Failure("I could not find $contactNameOrNumber in your contacts.")
            }
        }

        return try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${phoneNumber.replace(" ", "")}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(callIntent)
            ActionResult.Success("Calling $contactNameOrNumber")
        } catch (e: Exception) {
            Log.e("PhoneActions", "Failed to initiate call", e)
            ActionResult.Failure("Failed to dial the number.")
        }
    }

    sealed class FindResult {
        data class Found(val number: String) : FindResult()
        object NotFound : FindResult()
        object PermissionNeeded : FindResult()
    }

    private fun findPhoneNumberByName(name: String): FindResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return FindResult.PermissionNeeded
        }

        val cleanName = name.trim()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )

        // 1. First try Android's official CONTENT_FILTER_URI (case-insensitive phonetic & prefix matching)
        var cursor: Cursor? = null
        try {
            val filterUri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(cleanName))
            cursor = context.contentResolver.query(filterUri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                var fallbackNumber: String? = null
                do {
                    val displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else ""
                    val number = if (numberIndex >= 0) cursor.getString(numberIndex) else ""
                    if (displayName.equals(cleanName, ignoreCase = true) && !number.isNullOrBlank()) {
                        return FindResult.Found(number)
                    }
                    if (fallbackNumber == null && !number.isNullOrBlank()) {
                        fallbackNumber = number
                    }
                } while (cursor.moveToNext())

                if (!fallbackNumber.isNullOrBlank()) {
                    return FindResult.Found(fallbackNumber)
                }
            }
        } catch (e: Exception) {
            Log.w("PhoneActions", "CONTENT_FILTER_URI search failed for $cleanName, falling back to LIKE", e)
        } finally {
            cursor?.close()
        }

        // 2. Fallback to case-insensitive LIKE query
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? COLLATE NOCASE"
        val selectionArgs = arrayOf("%$cleanName%")

        return try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                var fallbackNumber: String? = null
                do {
                    val displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else ""
                    val number = if (numberIndex >= 0) cursor.getString(numberIndex) else ""
                    if (displayName.equals(cleanName, ignoreCase = true) && !number.isNullOrBlank()) {
                        return FindResult.Found(number)
                    }
                    if (fallbackNumber == null && !number.isNullOrBlank()) {
                        fallbackNumber = number
                    }
                } while (cursor.moveToNext())

                if (!fallbackNumber.isNullOrBlank()) {
                    FindResult.Found(fallbackNumber)
                } else {
                    FindResult.NotFound
                }
            } else {
                FindResult.NotFound
            }
        } catch (e: Exception) {
            Log.e("PhoneActions", "Error querying contacts", e)
            FindResult.NotFound
        } finally {
            cursor?.close()
        }
    }

    fun getBatteryStatus(): String {
        return try {
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.registerReceiver(null, batteryFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            if (batteryPct >= 0) {
                if (isCharging) {
                    "Your battery is at $batteryPct percent and currently charging."
                } else {
                    "Your battery is at $batteryPct percent."
                }
            } else {
                "I couldn't read the battery level."
            }
        } catch (e: Exception) {
            Log.e("PhoneActions", "Error checking battery", e)
            "I couldn't read the battery level."
        }
    }

    fun executeDeviceControl(command: String): ActionResult {
        if (command.equals("BATTERY", ignoreCase = true)) {
            return ActionResult.Success(getBatteryStatus())
        }

        executeAudioControl(command)?.let { return it }

        val service = AgentAccessibilityService.instance
        if (service == null) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return ActionResult.PermissionNeeded(
                "I need total phone control permission in Accessibility settings. Please turn on SiriPulse Phone Automation.",
                intent
            )
        }

        val ok = when (command.uppercase()) {
            "HOME" -> service.goHome()
            "BACK" -> service.goBack()
            "SCREENSHOT" -> service.takeScreenshot()
            "NOTIFICATIONS" -> service.openNotifications()
            "LOCK" -> service.lockScreen()
            "SCROLL_DOWN" -> service.scrollForward()
            "SCROLL_UP" -> service.scrollBackward()
            else -> false
        }

        return if (ok) {
            ActionResult.Success("Executed $command")
        } else {
            ActionResult.Failure("Could not execute $command")
        }
    }

    /** Executes volume and media-key commands locally without requiring Accessibility access. */
    private fun executeAudioControl(command: String): ActionResult? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return if (command.uppercase() in AUDIO_COMMANDS) {
                ActionResult.Failure("Audio controls are unavailable on this device.")
            } else {
                null
            }

        return try {
            when (command.uppercase()) {
                "VOLUME_UP" -> {
                    if (audioManager.isVolumeFixed) return ActionResult.Failure("The device uses fixed media volume.")
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    ActionResult.Success("Volume increased")
                }
                "VOLUME_DOWN" -> {
                    if (audioManager.isVolumeFixed) return ActionResult.Failure("The device uses fixed media volume.")
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                    ActionResult.Success("Volume decreased")
                }
                "VOLUME_MUTE" -> {
                    if (audioManager.isVolumeFixed) return ActionResult.Failure("The device uses fixed media volume.")
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_MUTE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    ActionResult.Success("Media volume muted")
                }
                "VOLUME_UNMUTE" -> {
                    if (audioManager.isVolumeFixed) return ActionResult.Failure("The device uses fixed media volume.")
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_UNMUTE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    ActionResult.Success("Media volume unmuted")
                }
                "MEDIA_PAUSE" -> {
                    dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PAUSE)
                    ActionResult.Success("Pause command sent to active media")
                }
                "MEDIA_PLAY" -> {
                    dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY)
                    ActionResult.Success("Play command sent to active media")
                }
                else -> null
            }
        } catch (e: SecurityException) {
            Log.e("PhoneActions", "Audio control permission denied for $command", e)
            ActionResult.Failure("Android denied the audio control request.")
        } catch (e: Exception) {
            Log.e("PhoneActions", "Failed to execute audio control $command", e)
            ActionResult.Failure("Could not perform that audio control.")
        }
    }

    private fun dispatchMediaKey(audioManager: AudioManager, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private companion object {
        val AUDIO_COMMANDS = setOf(
            "VOLUME_UP", "VOLUME_DOWN", "VOLUME_MUTE", "VOLUME_UNMUTE", "MEDIA_PAUSE", "MEDIA_PLAY"
        )
    }

    fun playYouTube(query: String) {
        try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
        }
    }

    fun openZomato(dish: String, restaurant: String?) {
        val searchTerm = if (restaurant != null) "$dish $restaurant" else dish
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("zomato://search?q=${Uri.encode(searchTerm)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.zomato.com/search?q=${Uri.encode(searchTerm)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
        }
    }

    fun sendWhatsApp(contactName: String, message: String): ActionResult {
        val phoneNumber = if (contactName.matches(Regex("^[0-9+ ]+$"))) {
            contactName.replace(" ", "")
        } else {
            val found = findPhoneNumberByName(contactName)
            when (found) {
                is FindResult.Found -> found.number.replace(" ", "")
                is FindResult.PermissionNeeded -> {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    return ActionResult.PermissionNeeded(
                        "I need permission to read your contacts to message $contactName on WhatsApp.",
                        intent
                    )
                }
                is FindResult.NotFound -> {
                    return ActionResult.Failure("I could not find $contactName in your contacts.")
                }
            }
        }

        return try {
            val cleanNumber = phoneNumber.replace("+", "").trim()
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val speech = if (message.isNotBlank()) {
                "Opening WhatsApp with message for $contactName"
            } else {
                "Opening WhatsApp chat with $contactName"
            }
            ActionResult.Success(speech)
        } catch (e: Exception) {
            try {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage("com.whatsapp")
                    putExtra(Intent.EXTRA_TEXT, message)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(sendIntent)
                ActionResult.Success("Opening WhatsApp")
            } catch (ex: Exception) {
                Log.e("PhoneActions", "Failed to launch WhatsApp", ex)
                ActionResult.Failure("WhatsApp is not installed on this phone.")
            }
        }
    }

    fun openApp(appName: String): ActionResult {
        val cleanName = appName.trim().lowercase()
        val pm = context.packageManager

        val packageMap = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "camera" to "com.oppo.camera",
            "settings" to "com.android.settings",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "play store" to "com.android.vending",
            "instagram" to "com.instagram.android",
            "zomato" to "com.application.zomato",
            "swiggy" to "in.swiggy.android",
            "paytm" to "net.one97.paytm",
            "phonepe" to "com.phonepe.app"
        )

        val targetPkg = packageMap[cleanName]
        if (targetPkg != null) {
            val intent = pm.getLaunchIntentForPackage(targetPkg)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return ActionResult.Success("Opening $appName")
            }
        }

        try {
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in packages) {
                val label = pm.getApplicationLabel(app).toString().lowercase()
                if (label == cleanName || label.contains(cleanName)) {
                    val intent = pm.getLaunchIntentForPackage(app.packageName)
                    if (intent != null) {
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                        return ActionResult.Success("Opening $appName")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PhoneActions", "Error launching app: $appName", e)
        }

        return ActionResult.Failure("I could not find $appName on your phone.")
    }
}
