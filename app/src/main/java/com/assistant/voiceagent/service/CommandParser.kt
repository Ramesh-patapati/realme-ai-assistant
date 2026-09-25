package com.assistant.voiceagent.service

import com.assistant.voiceagent.model.AIAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CommandParser {

    private val STOP_WORDS = setOf("stop", "close", "bye", "goodbye", "cancel", "never mind", "quit", "exit", "shut up")

    /**
     * Ultra-fast deterministic local parser (< 1ms execution, 0 network latency).
     * Handles navigation, apps, phone calls, WhatsApp, YouTube, and device controls.
     */
    fun parseDeterministic(userInput: String): AIAction? {
        var clean = userInput.trim().lowercase().replace(Regex("[.?!,]"), "")
        if (clean in STOP_WORDS) {
            return AIAction.Stop
        }

        // Strip conversational leading filler words (e.g. "and ", "please ", "can you ")
        val fillers = listOf(
            "hey jarvis ", "ok jarvis ", "hello jarvis ", "jarvis ",
            "and ", "please ", "can you please ", "can you ", "could you please ", "could you ",
            "i want you to ", "i want to ", "just "
        )
        for (filler in fillers) {
            if (clean.startsWith(filler)) {
                clean = clean.removePrefix(filler).trim()
            }
        }

        // Unbundle compound prefixes like "open whatsapp and ...", "open youtube and ..."
        if (clean.startsWith("open whatsapp and ") || clean.startsWith("launch whatsapp and ")) {
            clean = clean.removePrefix("open whatsapp and ").removePrefix("launch whatsapp and ").trim()
        } else if (clean.startsWith("open youtube and ") || clean.startsWith("launch youtube and ")) {
            clean = clean.removePrefix("open youtube and ").removePrefix("launch youtube and ").trim()
            if (!clean.startsWith("play ") && !clean.startsWith("stream ") && !clean.startsWith("search ")) {
                clean = "play $clean"
            }
        }

        // 1. Time & Date Queries (0ms response)
        if (clean in listOf("what time is it", "what is the time", "tell me the time", "current time", "time now", "what's the time")) {
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            return AIAction.Answer("It is $time.")
        }
        if (clean in listOf("what is the date", "what date is it", "today's date", "what is today's date", "tell me the date", "what's the date")) {
            val date = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
            return AIAction.Answer("Today is $date.")
        }

        // 2. Exact Navigation & System Controls
        when (clean) {
            "go home", "home", "home screen", "open home" -> return AIAction.DeviceControl("HOME", "Going to home screen")
            "go back", "back" -> return AIAction.DeviceControl("BACK", "Going back")
            "take screenshot", "take a screenshot", "capture screen", "screenshot" -> return AIAction.DeviceControl("SCREENSHOT", "Taking screenshot")
            "open notifications", "show notifications", "notifications" -> return AIAction.DeviceControl("NOTIFICATIONS", "Opening notifications")
            "lock phone", "lock screen", "turn off screen" -> return AIAction.DeviceControl("LOCK", "Locking phone")
            "scroll down" -> return AIAction.DeviceControl("SCROLL_DOWN", "Scrolling down")
            "scroll up" -> return AIAction.DeviceControl("SCROLL_UP", "Scrolling up")
        }

        // Messages & Notification Queries (e.g. "new messages", "any new messages", "check messages", "read messages")
        if (clean.contains("new messages") || clean.contains("unread messages") || clean.contains("check messages") || clean.contains("read messages") || clean.contains("any messages") || clean == "messages") {
            return AIAction.OpenApp("whatsapp")
        }

        if (clean in listOf("repeat", "say that again", "repeat that", "what did you say", "could you repeat that", "pardon")) {
            return AIAction.Answer("I am here! You can ask me to make calls, send WhatsApp messages, play music, or open apps.")
        }

        // 3. Music & YouTube Playback Matching (e.g. "play Telugu music", "and play Telugu music", "play believer")
        val playRegex = Regex("^(play|start playing|put on|stream)\\s+(.+)$")
        val playMatch = playRegex.find(clean)
        if (playMatch != null && !clean.contains("game") && !clean.contains("cricket") && !clean.contains("football")) {
            val rawQuery = playMatch.groupValues[2]
                .replace(" on youtube", "")
                .replace(" in youtube", "")
                .replace(" youtube", "")
                .trim()
            if (rawQuery.isNotBlank()) {
                return AIAction.PlayYouTube(rawQuery)
            }
        }

        if (clean.contains("telugu music") || clean.contains("telugu songs") || clean.contains("hindi songs") || clean.contains("music") || clean.contains("songs")) {
            val query = clean.replace("on youtube", "").replace("in youtube", "").trim()
            return AIAction.PlayYouTube(query)
        }

        // 4. Phone Calls with flexible natural phrasing (e.g. "make a call to Naveen", "call Naveen", "dial 9876543210")
        val callRegex = Regex("^(call|dial|phone|ring|make a call to|make a phone call to|place a call to)\\s+(.+)$")
        val callMatch = callRegex.find(clean)
        if (callMatch != null) {
            val rawContact = callMatch.groupValues[2].removePrefix("to ").trim()
            if (rawContact.isNotBlank()) {
                return AIAction.Call(formatName(rawContact))
            } else {
                return AIAction.Clarify("Who would you like me to call?")
            }
        }

        // 5. WhatsApp Message & Contact Parsing (e.g. "message to Naveen say hi", "Mr Naveen in WhatsApp", "send message to Naveen")
        val isExplicitWhatsApp = clean.contains("whatsapp") || clean.contains("in whatsapp") || clean.contains("on whatsapp")
        var waClean = clean
            .replace(" in whatsapp", "")
            .replace(" on whatsapp", "")
            .replace(" via whatsapp", "")
            .trim()
        if (waClean.endsWith(" whatsapp")) {
            waClean = waClean.removeSuffix(" whatsapp").trim()
        }

        val whatsappRegex = Regex("^(send whatsapp message to|send whatsapp to|whatsapp to|whatsapp|send a message to|send message to|send a text to|send text to|message to|message|text to|text|chat with)\\s+(.+)$")
        val waMatch = whatsappRegex.find(waClean)
        if (waMatch != null) {
            val remainder = waMatch.groupValues[2].trim()
            val parts = remainder.split(Regex(" (saying|say|that|message|msg|text) |: "))
            if (parts.size >= 2) {
                val contact = formatName(parts[0].trim())
                val message = parts.subList(1, parts.size).joinToString(" ").trim()
                if (contact.isNotBlank()) {
                    return AIAction.SendWhatsApp(contact, message)
                }
            } else {
                val words = remainder.split(" ").filter { it.isNotBlank() }
                if (words.size >= 2) {
                    val contact = formatName(words[0])
                    val message = words.subList(1, words.size).joinToString(" ")
                    return AIAction.SendWhatsApp(contact, message)
                } else if (words.isNotEmpty()) {
                    val contact = formatName(words[0])
                    return AIAction.SendWhatsApp(contact, "")
                }
            }
        } else if (isExplicitWhatsApp && !clean.startsWith("open ") && !clean.startsWith("launch ")) {
            val raw = waClean
                .removePrefix("chat with ")
                .removePrefix("message ")
                .removePrefix("to ")
                .trim()
            val contact = formatName(raw)
            if (contact.isNotBlank()) {
                return AIAction.SendWhatsApp(contact, "")
            }
        }

        // 6. Food Ordering
        if (clean.startsWith("order ") && (clean.contains("food") || clean.contains("pizza") || clean.contains("burger") || clean.contains("biryani") || clean.contains("zomato"))) {
            val item = clean
                .removePrefix("order ")
                .replace(" on zomato", "")
                .replace(" from zomato", "")
                .replace(" zomato", "")
                .trim()
            if (item.isNotBlank()) {
                return AIAction.OrderFood(item, null)
            }
        }

        // 7. App Launching (e.g. "open WhatsApp", "open YouTube", "open Camera", "open Chrome", "open Settings")
        if (clean.startsWith("open ") || clean.startsWith("launch ") || clean.startsWith("start ")) {
            val app = clean
                .removePrefix("open ")
                .removePrefix("launch ")
                .removePrefix("start ")
                .trim()
            if (app == "youtube") {
                return AIAction.PlayYouTube("")
            } else if (app.isNotBlank() && !app.contains(" and ") && !app.contains(" message ") && !app.contains(" saying ")) {
                return AIAction.OpenApp(app)
            }
        }

        // Exact 1-word app names
        when (clean) {
            "whatsapp" -> return AIAction.OpenApp("whatsapp")
            "camera" -> return AIAction.OpenApp("camera")
            "youtube" -> return AIAction.PlayYouTube("")
            "settings" -> return AIAction.OpenApp("settings")
            "chrome", "browser" -> return AIAction.OpenApp("chrome")
            "maps" -> return AIAction.OpenApp("maps")
            "gallery", "photos" -> return AIAction.OpenApp("photos")
        }

        return null
    }

    private fun formatName(name: String): String {
        return name.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
    }
}
