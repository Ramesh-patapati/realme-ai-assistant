package com.assistant.voiceagent.service

import com.assistant.voiceagent.model.AIAction
import java.util.Locale

/** Short-lived conversational state. Nothing in this class is persisted to disk. */
class ConversationMemory(
    maxHistoryMessages: Int = DEFAULT_MAX_HISTORY_MESSAGES,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS
) {
    private val maxMessages = maxHistoryMessages.coerceAtLeast(2).let { it - it % 2 }
    private val history = mutableListOf<Pair<String, String>>()
    private var lastInteractionAtMs: Long? = null
    private var lastContactName: String? = null

    /** Expires old context and returns the history to attach to the current AI request. */
    fun beginTurn(nowMs: Long): List<Pair<String, String>> {
        val previousInteraction = lastInteractionAtMs
        if (previousInteraction != null && nowMs - previousInteraction > idleTimeoutMs) {
            clear()
        }
        lastInteractionAtMs = nowMs
        return history.toList()
    }

    fun resolveContactReference(action: AIAction): AIAction = when (action) {
        is AIAction.Call -> {
            if (!isReference(action.contactName)) action
            else lastContactName?.let { action.copy(contactName = it) }
                ?: AIAction.Clarify("Who do you mean? I don't have a recent contact in this conversation.")
        }
        is AIAction.SendWhatsApp -> {
            if (!isReference(action.contactName)) action
            else lastContactName?.let { action.copy(contactName = it) }
                ?: AIAction.Clarify("Who do you mean? I don't have a recent contact in this conversation.")
        }
        else -> action
    }

    fun rememberTurn(userInput: String, action: AIAction) {
        history.add("user" to userInput.trim())
        history.add("assistant" to summarize(action))

        when (action) {
            is AIAction.Call -> if (!isReference(action.contactName)) lastContactName = action.contactName
            is AIAction.SendWhatsApp -> if (!isReference(action.contactName)) lastContactName = action.contactName
            else -> Unit
        }

        while (history.size > maxMessages) {
            history.removeAt(0)
            history.removeAt(0)
        }
    }

    fun clear() {
        history.clear()
        lastContactName = null
        lastInteractionAtMs = null
    }

    private fun summarize(action: AIAction): String = when (action) {
        is AIAction.Call -> "Calling ${action.contactName}."
        is AIAction.SendWhatsApp -> "Opened a WhatsApp draft for ${action.contactName}: ${action.message}"
        is AIAction.Answer -> action.replyText
        is AIAction.Clarify -> action.question
        is AIAction.DeviceControl -> action.speech.ifBlank { action.command }
        is AIAction.PlayYouTube -> "Playing ${action.songOrQuery} on YouTube."
        is AIAction.OrderFood -> "Opening Zomato for ${action.item}."
        is AIAction.OpenApp -> "Opening ${action.appName}."
        is AIAction.Stop -> "Stopped listening."
        is AIAction.Unknown -> action.rawText
    }

    private fun isReference(name: String): Boolean {
        val normalized = name.trim().lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N} ]"), "")
            .replace(Regex("\\s+"), " ")
        return normalized in CONTACT_REFERENCES
    }

    companion object {
        const val DEFAULT_MAX_HISTORY_MESSAGES = 12
        const val DEFAULT_IDLE_TIMEOUT_MS = 10 * 60 * 1000L

        private val CONTACT_REFERENCES = setOf(
            "him", "her", "them", "he", "she", "they", "that person", "this person",
            "the same person", "same person", "that contact", "the same contact"
        )
    }
}
