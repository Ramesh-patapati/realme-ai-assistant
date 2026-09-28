package com.assistant.voiceagent.service

import java.text.Normalizer
import java.util.Locale

/**
 * Deterministic, high-performance wake phrase matcher for incoming speech utterances.
 *
 * Scans recognized speech for configured wake phrases (e.g., "Jarvis", "Hey Jarvis", or custom
 * names like "Assistant") occurring at the start of an utterance, with support for leading
 * conversational hesitation fillers (e.g. "uh", "um", "ah", "er", "oh").
 *
 * Architectural Properties:
 * - Pure Kotlin standard library: 0 Android/platform dependencies.
 * - Thread-safe and stateless: no mutable internal state or cache.
 * - Time Complexity: O(N) linear scan where N is the character length of [speech].
 * - Memory Complexity: O(N) allocation for the normalized string representation.
 */
object WakeWordMatcher {

    private val COMBINING_MARKS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val HESITATION_FILLERS = setOf("uh", "um", "ah", "er", "oh")
    private val DEFAULT_JARVIS_ALIASES = listOf(
        "hey jarvis", "ok jarvis", "hello jarvis", "hi jarvis",
        "jarvis", "hey javis", "javis", "jarves"
    )
    private val SALUTATION_PREFIXES = listOf("hey ", "ok ", "hello ", "hi ")

    /**
     * Checks if the recognized [speech] begins with a valid wake word or phrase.
     *
     * @param speech The recognized utterance from speech recognition (nullable).
     * @param customWakeWord The configured custom wake word or phrase (nullable, defaults to "hey jarvis").
     * @return A [Pair] where:
     *         - `first`: `true` if a wake phrase was matched at the start (or after hesitation fillers), `false` otherwise.
     *         - `second`: The remaining command text after removing the wake phrase and any leading/trailing
     *                     punctuation, or empty string `""` if no command followed or no match occurred.
     *
     * Complexity:
     * - Time: O(N) where N = speech.length.
     * - Memory: O(N) where N = speech.length.
     */
    fun match(speech: String?, customWakeWord: String? = null): Pair<Boolean, String> {
        val cleanSpeech = normalize(speech)
        if (cleanSpeech.isBlank()) return false to ""

        val custom = normalize(customWakeWord)
        val wakeWords = buildWakeWordCandidates(custom)

        // 1. Direct match check (longest wake word candidate matches first)
        val directMatch = findMatch(cleanSpeech, wakeWords)
        if (directMatch != null) return directMatch

        // 2. Leading hesitation filler stripping check (e.g., "uh hey jarvis", "um... jarvis call mom")
        var stripped = cleanSpeech
        var strippedAny = false
        while (stripped.isNotEmpty()) {
            val spaceIdx = stripped.indexOf(' ')
            val firstToken = if (spaceIdx >= 0) stripped.substring(0, spaceIdx) else stripped
            if (firstToken in HESITATION_FILLERS) {
                strippedAny = true
                stripped = if (spaceIdx >= 0) stripped.substring(spaceIdx + 1).trimStart() else ""
            } else {
                break
            }
        }

        if (strippedAny && stripped.isNotEmpty()) {
            val fillerMatch = findMatch(stripped, wakeWords)
            if (fillerMatch != null) return fillerMatch
        }

        return false to ""
    }

    /**
     * Normalizes a string by:
     * 1. Stripping Latin combining diacritical marks via NFD decomposition.
     * 2. Lowercasing with [Locale.ROOT] for locale-invariant casing.
     * 3. Converting non-alphanumeric punctuation to spaces while preserving Indic script matras.
     * 4. Collapsing consecutive whitespace and trimming.
     *
     * Complexity:
     * - Time: O(N) where N = value.length.
     * - Memory: O(N) where N = value.length.
     */
    fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""

        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")

        val len = decomposed.length
        val sb = StringBuilder(len)
        var prevWasSpace = true

        var i = 0
        while (i < len) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            val lower = Character.toLowerCase(cp)
            if (isWordCodePoint(lower)) {
                sb.appendCodePoint(lower)
                prevWasSpace = false
            } else if (!prevWasSpace) {
                sb.append(' ')
                prevWasSpace = true
            }
        }

        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
            sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }

    private fun isWordCodePoint(cp: Int): Boolean {
        if (Character.isLetter(cp) || Character.isDigit(cp)) return true
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    private fun buildWakeWordCandidates(custom: String): List<String> {
        val candidates = linkedSetOf<String>()
        if (custom.isNotBlank()) candidates.add(custom)

        val isJarvis = custom.isBlank() ||
            custom == "hey jarvis" ||
            custom == "jarvis" ||
            custom.endsWith("jarvis") ||
            custom.endsWith("javis")

        if (isJarvis) {
            candidates.addAll(DEFAULT_JARVIS_ALIASES)
        } else {
            var baseWord = custom
            for (prefix in SALUTATION_PREFIXES) {
                if (baseWord.startsWith(prefix)) {
                    baseWord = baseWord.removePrefix(prefix).trim()
                    break
                }
            }
            if (baseWord.isNotBlank()) {
                candidates.add(baseWord)
            }
            for (prefix in SALUTATION_PREFIXES) {
                val salutation = "$prefix$baseWord"
                candidates.add(salutation)
            }
        }

        return candidates.sortedByDescending { it.length }
    }

    private fun findMatch(text: String, wakeWords: List<String>): Pair<Boolean, String>? {
        for (wakeWord in wakeWords) {
            if (text == wakeWord) return true to ""
            val prefix = "$wakeWord "
            if (text.startsWith(prefix)) {
                return true to text.removePrefix(prefix).trim()
            }
        }
        return null
    }
}
