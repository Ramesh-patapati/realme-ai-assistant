package com.assistant.voiceagent.service

import com.assistant.voiceagent.model.AIAction

object CommandParser {

    private val STOP_WORDS = setOf("stop", "close", "bye", "goodbye", "cancel", "never mind", "quit", "exit", "shut up")

    /**
     * Strict deterministic local parser for standard phone commands.
     * Returns null if the command requires AI reasoning or doesn't match an exact action pattern.
     */
    fun parseDeterministic(userInput: String): AIAction? {
        val clean = userInput.trim().lowercase()
        if (clean in STOP_WORDS) {
            return AIAction.Stop
        }

        // Exact Navigation & System Controls
        when (clean) {
            "go home", "home", "home screen", "open home" -> return AIAction.DeviceControl("HOME", "Going to home screen")
            "go back", "back" -> return AIAction.DeviceControl("BACK", "Going back")
            "take screenshot", "take a screenshot", "capture screen" -> return AIAction.DeviceControl("SCREENSHOT", "Taking screenshot")
            "open notifications", "show notifications", "notifications" -> return AIAction.DeviceControl("NOTIFICATIONS", "Opening notifications")
            "lock phone", "lock screen", "turn off screen" -> return AIAction.DeviceControl("LOCK", "Locking phone")
            "scroll down" -> return AIAction.DeviceControl("SCROLL_DOWN", "Scrolling down")
            "scroll up" -> return AIAction.DeviceControl("SCROLL_UP", "Scrolling up")
        }

        // Strict YouTube Playback Matching (e.g. "play believer", "play despacito on youtube")
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

        // Phone Calls with argument extraction and validation
        if (clean.startsWith("call ") || clean.startsWith("dial ")) {
            val contact = clean
                .removePrefix("call ")
                .removePrefix("dial ")
                .removePrefix("to ")
                .trim()
            if (contact.isNotBlank()) {
                return AIAction.Call(contact.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } })
            } else {
                return AIAction.Clarify("Who would you like me to call?")
            }
        }

        // Food Ordering
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
