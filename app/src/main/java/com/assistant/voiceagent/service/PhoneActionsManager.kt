package com.assistant.voiceagent.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
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

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                FindResult.Found(cursor.getString(numberIndex))
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

    fun executeDeviceControl(command: String): ActionResult {
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

    fun sendWhatsApp(contactName: String, message: String) {
        val phoneNumber = if (contactName.matches(Regex("^[0-9+ ]+$"))) {
            contactName.replace(" ", "")
        } else {
            val found = findPhoneNumberByName(contactName)
            if (found is FindResult.Found) found.number.replace(" ", "") else ""
        }

        try {
            val cleanNumber = phoneNumber.replace("+", "").trim()
            val uri = if (cleanNumber.isNotBlank()) {
                Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}")
            } else {
                Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(message)}")
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_TEXT, message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(sendIntent)
        }
    }
}
