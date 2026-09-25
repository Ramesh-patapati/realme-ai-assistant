package com.assistant.voiceagent.service

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.BatteryManager
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhoneActionsManager(private val context: Context) {

    sealed class ActionResult {
        data class Success(val message: String) : ActionResult()
        data class PermissionNeeded(val spokenExplanation: String, val settingsIntent: Intent) : ActionResult()
        data class Failure(val reason: String) : ActionResult()
    }

    suspend fun makeCall(contactNameOrNumber: String): ActionResult {
        val target = contactNameOrNumber.trim()
        if (target.isBlank()) return ActionResult.Failure("I didn't catch who you want to call.")
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

        val phoneNumber = normalizePhoneNumber(target) ?: when (
            val found = withContext(Dispatchers.IO) { findPhoneNumberByName(target) }
        ) {
            FindResult.PermissionNeeded -> {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                return ActionResult.PermissionNeeded(
                    "I need permission to read your contacts to find $target. Opening settings now.",
                    intent
                )
            }
            is FindResult.Found -> normalizePhoneNumber(found.number)
                ?: return ActionResult.Failure("The saved number for $target isn't valid.")
            is FindResult.Ambiguous -> return ActionResult.Failure(
                "I found more than one number for $target. Please make the contact name more specific."
            )
            FindResult.NotFound -> return ActionResult.Failure("I couldn't find an exact contact named $target.")
        }

        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null) {
                telecomManager.placeCall(Uri.fromParts("tel", phoneNumber, null), Bundle())
            } else {
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.fromParts("tel", phoneNumber, null)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(callIntent)
            }
            ActionResult.Success("Calling $target")
        } catch (e: Exception) {
            Log.e("PhoneActions", "Failed to initiate call", e)
            ActionResult.Failure("Failed to dial the number.")
        }
    }

    sealed class FindResult {
        data class Found(val number: String) : FindResult()
        data class Ambiguous(val matchingNumbers: Int) : FindResult()
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

        val exactNumbers = linkedSetOf<String>()
        val filterUri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(cleanName)
        )
        collectExactContactNumbers(filterUri, projection, cleanName, exactNumbers)
        if (exactNumbers.isEmpty()) {
            val exactSelection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ? COLLATE NOCASE"
            collectExactContactNumbers(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                cleanName,
                exactNumbers,
                exactSelection,
                arrayOf(cleanName)
            )
        }
        if (exactNumbers.isEmpty()) {
            val partialSelection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? COLLATE NOCASE"
            collectExactContactNumbers(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                cleanName,
                exactNumbers,
                partialSelection,
                arrayOf("%$cleanName%"),
                allowPartial = true
            )
        }

        return when (exactNumbers.size) {
            0 -> FindResult.NotFound
            1 -> FindResult.Found(exactNumbers.first())
            else -> FindResult.Ambiguous(exactNumbers.size)
        }
    }

    private fun collectExactContactNumbers(
        uri: Uri,
        projection: Array<String>,
        requestedName: String,
        numbers: MutableSet<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        allowPartial: Boolean = false
    ) {
        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIndex)?.trim() ?: ""
                    val number = cursor.getString(numberIndex)?.trim()
                    val matches = if (allowPartial) {
                        displayName.contains(requestedName, ignoreCase = true)
                    } else {
                        displayName.equals(requestedName, ignoreCase = true)
                    }
                    if (matches && !number.isNullOrBlank()) {
                        val cleanNum = normalizePhoneNumber(number) ?: number.filter(Char::isDigit)
                        if (cleanNum.isNotBlank()) {
                            numbers.add(cleanNum)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("PhoneActions", "Contact query failed for $requestedName", e)
        }
    }

    private fun normalizePhoneNumber(value: String): String? {
        val compact = value.replace(Regex("[\\s().-]"), "")
        return compact.takeIf { it.matches(Regex("^\\+?[0-9]{3,}$")) }
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

        if (command.equals("TAKE_PHOTO", ignoreCase = true)) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                dispatchMediaKey(audioManager, KeyEvent.KEYCODE_CAMERA)
                dispatchMediaKey(audioManager, KeyEvent.KEYCODE_VOLUME_DOWN)
            }
            return ActionResult.Success("Taking picture")
        }

        if (command.equals("FRONT_CAMERA", ignoreCase = true)) {
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra("android.intent.extras.CAMERA_FACING", 1)
                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return startActivityResult(intent, "Opening front camera")
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

    fun playYouTube(query: String): ActionResult {
        val cleanedQuery = query.trim()
        if (cleanedQuery.isBlank()) return openApp("youtube")

        val youtubeIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", cleanedQuery)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val launchResult = startActivityResult(youtubeIntent, "Playing $cleanedQuery on YouTube")
        if (launchResult is ActionResult.Success || launchResult is ActionResult.PermissionNeeded) {
            return launchResult
        }

        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(cleanedQuery)}")
        ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        return startActivityResult(webIntent, "Opening YouTube results for $cleanedQuery")
    }

    fun openZomato(dish: String, restaurant: String?): ActionResult {
        val searchTerm = if (restaurant != null) "$dish $restaurant" else dish
        val appIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("zomato://search?q=${Uri.encode(searchTerm)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val appResult = startActivityResult(appIntent, "Opening Zomato for $searchTerm")
        if (appResult is ActionResult.Success || appResult is ActionResult.PermissionNeeded) return appResult

        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.zomato.com/search?q=${Uri.encode(searchTerm)}")
        ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        return startActivityResult(webIntent, "Opening search results for $searchTerm")
    }

    suspend fun sendWhatsApp(contactName: String, message: String): ActionResult {
        val recipient = contactName.trim()
        if (recipient.isBlank()) return ActionResult.Failure("I didn't catch who you want to message.")
        if (message.isBlank()) return ActionResult.Failure("I didn't catch the WhatsApp message.")

        val phoneNumber = normalizePhoneNumber(recipient) ?: when (
            val found = withContext(Dispatchers.IO) { findPhoneNumberByName(recipient) }
        ) {
            FindResult.PermissionNeeded -> {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                return ActionResult.PermissionNeeded(
                    "I need permission to read your contacts to find $recipient. Opening settings now.",
                    intent
                )
            }
            is FindResult.Found -> found.number
            is FindResult.Ambiguous -> return ActionResult.Failure(
                "I found more than one number for $recipient. Please make the contact name more specific."
            )
            FindResult.NotFound -> return ActionResult.Failure("I couldn't find an exact contact named $recipient.")
        }
        val waNumber = phoneNumber.filter(Char::isDigit)
        if (waNumber.length !in 7..15) {
            return ActionResult.Failure("The phone number for $recipient doesn't look valid for WhatsApp.")
        }

        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$waNumber&text=${Uri.encode(message)}")
        val whatsappPackage = when {
            context.packageManager.getLaunchIntentForPackage("com.whatsapp") != null -> "com.whatsapp"
            context.packageManager.getLaunchIntentForPackage("com.whatsapp.w4b") != null -> "com.whatsapp.w4b"
            else -> "com.whatsapp"
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(whatsappPackage)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return startActivityResult(intent, "Opened a WhatsApp draft for $recipient. Review it and tap send.")
    }

    fun openApp(appName: String): ActionResult {
        val cleanName = normalizeAppName(appName)
        val pm = context.packageManager

        if (cleanName == "whatsapp") {
            val waIntent = pm.getLaunchIntentForPackage("com.whatsapp")
                ?: pm.getLaunchIntentForPackage("com.whatsapp.w4b")
                ?: Intent(Intent.ACTION_MAIN).apply {
                    setClassName("com.whatsapp", "com.whatsapp.Main")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            return startActivityResult(waIntent, "Opening WhatsApp")
        }

        if (cleanName == "youtube") {
            val ytIntent = pm.getLaunchIntentForPackage("com.google.android.youtube")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            return startActivityResult(ytIntent, "Opening YouTube")
        }

        val packageMap = mapOf(
            "whatsapp" to listOf("com.whatsapp"),
            "youtube" to listOf("com.google.android.youtube"),
            "chrome" to listOf("com.android.chrome"),
            "browser" to listOf("com.android.chrome"),
            "camera" to listOf("com.oppo.camera", "com.oplus.camera", "com.android.camera2"),
            "settings" to listOf("com.android.settings"),
            "maps" to listOf("com.google.android.apps.maps"),
            "gmail" to listOf("com.google.android.gm"),
            "play store" to listOf("com.android.vending"),
            "instagram" to listOf("com.instagram.android"),
            "zomato" to listOf("com.application.zomato"),
            "swiggy" to listOf("in.swiggy.android"),
            "paytm" to listOf("net.one97.paytm"),
            "phonepe" to listOf("com.phonepe.app")
        )

        for (targetPkg in packageMap[cleanName].orEmpty()) {
            val intent = pm.getLaunchIntentForPackage(targetPkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return startActivityResult(intent, "Opening $cleanName")
            }
        }

        if (cleanName == "camera") {
            val cameraIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val result = startActivityResult(cameraIntent, "Opening camera")
            if (result is ActionResult.Success || result is ActionResult.PermissionNeeded) return result
        }

        try {
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in packages) {
                val label = normalizeAppName(pm.getApplicationLabel(app).toString())
                if (label == cleanName) {
                    val intent = pm.getLaunchIntentForPackage(app.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        return startActivityResult(intent, "Opening $cleanName")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PhoneActions", "Error launching app: $appName", e)
        }

        return ActionResult.Failure("I could not find $appName on your phone.")
    }

    private fun normalizeAppName(name: String): String {
        val normalized = name.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removeSuffix(" app")
            .removeSuffix(" application")
            .trim()
        return when (normalized) {
            "whats app", "whats up", "what s app", "what s up", "whatsapp", "whatsapp app" -> "whatsapp"
            "you tube", "youtube", "youtube app" -> "youtube"
            else -> normalized
        }
    }

    private fun startActivityResult(intent: Intent, successMessage: String): ActionResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(context) &&
            !hasVisibleAppActivity()
        ) {
            val overlaySettings = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return ActionResult.PermissionNeeded(
                "Android blocks Jarvis from opening apps while it runs in the background. Enable Display over other apps for Jarvis, then try again.",
                overlaySettings
            )
        }

        return try {
            context.startActivity(intent)
            ActionResult.Success(successMessage)
        } catch (e: Exception) {
            Log.e("PhoneActions", "Failed to launch activity ${intent.action}", e)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                val overlaySettings = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                ActionResult.PermissionNeeded(
                    "Android blocked opening that app in the background. Allow Display over other apps for Jarvis in settings, then try again.",
                    overlaySettings
                )
            } else {
                ActionResult.Failure("Android blocked opening the requested app. Please unlock your phone and try again.")
            }
        }
    }

    private fun hasVisibleAppActivity(): Boolean {
        val processInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processInfo)
        return processInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }
}
