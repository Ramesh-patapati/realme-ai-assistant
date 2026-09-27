package com.assistant.voiceagent.service

import java.util.Locale

/** Matches an explicit wake phrase at the start of a recognized utterance. */
object WakeWordMatcher {
    fun match(speech: String, customWakeWord: String): Pair<Boolean, String> {
        val cleanSpeech = normalize(speech)
        if (cleanSpeech.isBlank()) return false to ""

        val custom = normalize(customWakeWord)
        val wakeWords = linkedSetOf<String>()
        if (custom.isNotBlank()) wakeWords += custom

        // Common recognition variations of Jarvis. Avoid broad defaults such as
        // "assistant", "service", and "travis" that can activate on ordinary speech.
        if (custom == "hey jarvis" || custom == "jarvis") {
            wakeWords += listOf("hey jarvis", "ok jarvis", "hello jarvis", "hi jarvis", "jarvis", "hey javis", "javis", "jarves")
        }

        for (wakeWord in wakeWords.sortedByDescending { it.length }) {
            if (cleanSpeech == wakeWord) return true to ""
            val prefix = "$wakeWord "
            if (cleanSpeech.startsWith(prefix)) {
                return true to cleanSpeech.removePrefix(prefix).trim()
            }
        }
        return false to ""
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
