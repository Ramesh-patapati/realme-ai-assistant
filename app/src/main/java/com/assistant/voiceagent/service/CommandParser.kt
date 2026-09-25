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
        var originalCommand = userInput.trim()
        // Strip conversational leading filler words (e.g. "and ", "please ", "can you ")
        val fillers = listOf(
            "hey jarvis ", "ok jarvis ", "hello jarvis ", "jarvis ",
            "and ", "please ", "can you please ", "can you ", "could you please ", "could you ",
            "i want you to ", "i want to ", "just "
        )
        var strippedFiller: Boolean
        do {
            strippedFiller = false
            val filler = fillers.firstOrNull { clean.startsWith(it) }
            if (filler != null) {
                clean = clean.removePrefix(filler).trim()
                originalCommand = removeLeadingPhrase(originalCommand, filler.trim())
                strippedFiller = true
            }
        } while (strippedFiller)

        // Check after removing the wake word and conversational fillers, so phrases
        // such as "Hey Jarvis, please stop" are handled locally too.
        if (clean in STOP_WORDS) {
            return AIAction.Stop
        }

        // Unbundle compound prefixes like "open whatsapp and ...", "open youtube and ..."
        if (clean.startsWith("open whatsapp and ") || clean.startsWith("launch whatsapp and ")) {
            val prefix = if (clean.startsWith("open whatsapp and ")) "open whatsapp and" else "launch whatsapp and"
            originalCommand = removeLeadingPhrase(originalCommand, prefix)
            clean = clean.removePrefix("open whatsapp and ").removePrefix("launch whatsapp and ").trim()
        } else if (clean.startsWith("open youtube and ") || clean.startsWith("launch youtube and ")) {
            val prefix = if (clean.startsWith("open youtube and ")) "open youtube and" else "launch youtube and"
            originalCommand = removeLeadingPhrase(originalCommand, prefix)
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

        // Battery Status Queries (< 1ms offline response)
        if (clean in listOf(
                "what is my battery", "what is my battery percentage", "what is the battery percentage",
                "what's my battery", "what's the battery", "battery status", "battery level",
                "battery percentage", "check battery", "how much battery do i have", "how much battery is left",
                "what is battery level", "battery"
            ) || clean.startsWith("battery status") || clean.startsWith("battery level") ||
            clean.contains("how much battery") || clean.contains("battery percentage") ||
            clean == "my battery" || clean == "battery"
        ) {
            return AIAction.DeviceControl("BATTERY", "")
        }

        // 1b. Volume and active media controls: keep these deterministic and offline.
        when (clean) {
            "volume up", "turn volume up", "turn up volume", "turn up the volume",
            "increase volume", "increase the volume", "raise volume", "make it louder", "louder" ->
                return AIAction.DeviceControl("VOLUME_UP", "Volume up")

            "volume down", "turn volume down", "turn down volume", "turn down the volume",
            "decrease volume", "decrease the volume", "lower volume", "make it quieter", "quieter" ->
                return AIAction.DeviceControl("VOLUME_DOWN", "Volume down")

            "mute", "mute volume", "mute the volume", "mute music" ->
                return AIAction.DeviceControl("VOLUME_MUTE", "Muted")

            "unmute", "unmute volume", "unmute the volume", "unmute music" ->
                return AIAction.DeviceControl("VOLUME_UNMUTE", "Unmuted")

            "pause", "pause music", "pause the music", "pause playback" ->
                return AIAction.DeviceControl("MEDIA_PAUSE", "Pausing music")

            "resume", "resume music", "resume the music", "resume playback",
            "continue music", "continue the music", "continue playback", "continue playing music" ->
                return AIAction.DeviceControl("MEDIA_PLAY", "Resuming music")
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

        // 5. WhatsApp: require a clear contact/message boundary so multi-word names
        // are not accidentally split into a one-word contact and partial message.
        val whatsappAction = parseWhatsAppCommand(originalCommand)
        if (whatsappAction != null) return whatsappAction

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

    private fun parseWhatsAppCommand(command: String): AIAction? {
        val prefixes = listOf(
            "send a whatsapp message to ", "send whatsapp message to ",
            "send a whatsapp to ", "send whatsapp to ", "whatsapp to ",
            "whatsapp message to ",
            "send a message on whatsapp to ", "send message on whatsapp to ",
            "send a message to ", "send message to ",
            "send a text on whatsapp to ", "send text on whatsapp to ",
            "send a text to ", "send text to ", "message to ", "text to "
        )
        val prefix = prefixes.firstOrNull { command.startsWith(it, ignoreCase = true) } ?: return null
        val body = command.substring(prefix.length).trim()

        val separator = Regex("\\s+(?:saying|say|that|message|msg|text)\\s+|\\s*:\\s*", RegexOption.IGNORE_CASE)
        val match = separator.find(body)
        if (match == null) {
            val contactText = removeWhatsAppChannel(body)
            val contact = formatName(contactText)
            return if (contact.isBlank()) {
                AIAction.Clarify("Who should I message on WhatsApp?")
            } else {
                AIAction.Clarify("What message should I send to $contact?")
            }
        }

        val contactText = removeWhatsAppChannel(body.substring(0, match.range.first).trim())
        val contact = formatName(contactText)
        val message = body.substring(match.range.last + 1).trim()
        if (contact.isBlank()) return AIAction.Clarify("Who should I message on WhatsApp?")
        if (message.isBlank()) return AIAction.Clarify("What message should I send to $contact?")
        return AIAction.SendWhatsApp(contact, message)
    }

    private fun removeWhatsAppChannel(contact: String): String {
        return contact.replace(
            Regex("\\s+(?:on|in|via)\\s+whats\\s?app\\b", RegexOption.IGNORE_CASE),
            " "
        ).trim()
    }

    private fun removeLeadingPhrase(text: String, phrase: String): String {
        return if (text.startsWith(phrase, ignoreCase = true)) text.substring(phrase.length).trimStart(' ', '\t', ',', ':') else text
    }
}
