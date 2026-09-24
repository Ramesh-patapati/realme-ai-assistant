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
        val clean = userInput.trim().lowercase().replace(Regex("[.?!,]"), "")
        if (clean in STOP_WORDS) {
            return AIAction.Stop
        }

        // 1. Time & Date Queries (0ms response)
        if (clean in listOf("what time is it", "what is the time", "tell me the time", "current time", "time now")) {
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            return AIAction.Answer("It is $time.")
        }
        if (clean in listOf("what is the date", "what date is it", "today's date", "what is today's date", "tell me the date")) {
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

        // 3. YouTube Playback Matching
        if (clean.startsWith("play ") && !clean.contains("game") && !clean.contains("cricket") && !clean.contains("football")) {
            val query = clean
                .removePrefix("play ")
                .replace(" on youtube", "")
                .replace(" in youtube", "")
                .replace(" youtube", "")
                .trim()
            if (query.isNotBlank()) {
                return AIAction.PlayYouTube(query)
            }
        }

        if (clean.startsWith("search ") && (clean.endsWith(" on youtube") || clean.endsWith(" in youtube"))) {
            val query = clean
                .removePrefix("search ")
                .replace(" on youtube", "")
                .replace(" in youtube", "")
                .trim()
            if (query.isNotBlank()) {
                return AIAction.PlayYouTube(query)
            }
        }

        // 4. Phone Calls with flexible natural phrasing (e.g. "make a call to Naveen", "call Naveen", "dial 9876543210")
        val callRegex = Regex("^(call|dial|phone|ring|make a call to|make a phone call to|place a call to)\\s+(.+)$")
        val callMatch = callRegex.find(clean)
        if (callMatch != null) {
            val rawContact = callMatch.groupValues[2].removePrefix("to ").trim()
            if (rawContact.isNotBlank()) {
                val formattedContact = rawContact.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                return AIAction.Call(formattedContact)
            } else {
                return AIAction.Clarify("Who would you like me to call?")
            }
        }

        // 5. WhatsApp Message Parsing (e.g. "send message to Naveen saying hello", "whatsapp Naveen hi")
        val whatsappRegex = Regex("^(send whatsapp to|whatsapp to|whatsapp|send message to|send a message to|send text to|message to|message|text to|text)\\s+(.+)$")
        val waMatch = whatsappRegex.find(clean)
        if (waMatch != null) {
            val remainder = waMatch.groupValues[2].trim()
            val parts = remainder.split(Regex(" (saying|that|message|msg|text) |: "))
            if (parts.size >= 2) {
                val contact = parts[0].trim().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                val message = parts.subList(1, parts.size).joinToString(" ").trim()
                if (contact.isNotBlank() && message.isNotBlank()) {
                    return AIAction.SendWhatsApp(contact, message)
                }
            } else {
                val words = remainder.split(" ")
                if (words.size >= 2) {
                    val contact = words[0].replaceFirstChar { it.uppercase() }
                    val message = words.subList(1, words.size).joinToString(" ")
                    return AIAction.SendWhatsApp(contact, message)
                } else if (words.isNotEmpty()) {
                    val contact = words[0].replaceFirstChar { it.uppercase() }
                    return AIAction.SendWhatsApp(contact, "Hello")
                }
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

        return null
    }
}
