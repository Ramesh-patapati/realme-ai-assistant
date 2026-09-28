package com.assistant.voiceagent.service

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeWordMatcherTest {

    @Test
    fun recognizesWakeWordAtStartAndReturnsRemainingCommand() {
        assertEquals(true to "call mom", WakeWordMatcher.match("Hey Jarvis, call Mom", "hey jarvis"))
    }

    @Test
    fun recognizesWakeWordWithoutACommand() {
        assertEquals(true to "", WakeWordMatcher.match("Jarvis!", "hey jarvis"))
    }

    @Test
    fun ignoresWakeWordEmbeddedInOrdinarySpeech() {
        assertEquals(false to "", WakeWordMatcher.match("Please ask Jarvis to call Mom", "hey jarvis"))
        assertEquals(false to "", WakeWordMatcher.match("I told Jarvis to wake up", "hey jarvis"))
    }

    @Test
    fun doesNotUseBroadAliasesUnlessTheyAreConfigured() {
        assertEquals(false to "", WakeWordMatcher.match("Assistant, call Mom", "hey jarvis"))
        assertEquals(true to "call mom", WakeWordMatcher.match("Assistant, call Mom", "assistant"))
    }

    @Test
    fun requiresAWordBoundaryAfterWakePhrase() {
        assertEquals(false to "", WakeWordMatcher.match("Jarvison call Mom", "jarvis"))
        assertEquals(false to "", WakeWordMatcher.match("Jarvisland", "jarvis"))
    }

    @Test
    fun handlesLeadingHesitationFillers() {
        assertEquals(true to "call mom", WakeWordMatcher.match("Uh, hey Jarvis, call Mom", "hey jarvis"))
        assertEquals(true to "what time is it", WakeWordMatcher.match("Um... jarvis what time is it?", "jarvis"))
        assertEquals(true to "turn volume up", WakeWordMatcher.match("Ah Jarvis turn volume up", "hey jarvis"))
        assertEquals(true to "stop", WakeWordMatcher.match("Er Jarvis stop", "hey jarvis"))
        assertEquals(true to "", WakeWordMatcher.match("Oh hey Jarvis", "hey jarvis"))
        assertEquals(true to "take photo", WakeWordMatcher.match("Uh um oh hey Jarvis take photo", "hey jarvis"))
    }

    @Test
    fun normalizesAccentsAndDiacritics() {
        assertEquals(true to "call mom", WakeWordMatcher.match("Hèy Jàrvis, call Mom!", "hey jarvis"))
        assertEquals(true to "open youtube", WakeWordMatcher.match("Járvis, open youtube", "jarvis"))
        assertEquals(true to "", WakeWordMatcher.match("Héy Järvis", "hey jarvis"))
    }

    @Test
    fun handlesWhitespaceAndPunctuationVariations() {
        assertEquals(true to "call mom", WakeWordMatcher.match("   Hey    Jarvis   ,    call   Mom...  ", "hey jarvis"))
        assertEquals(true to "", WakeWordMatcher.match("Hey Jarvis!?!", "hey jarvis"))
        assertEquals(true to "", WakeWordMatcher.match("Jarvis...", "jarvis"))
    }

    @Test
    fun handlesNullAndEmptyInputsSafely() {
        assertEquals(false to "", WakeWordMatcher.match(null, "hey jarvis"))
        assertEquals(false to "", WakeWordMatcher.match("", "hey jarvis"))
        assertEquals(false to "", WakeWordMatcher.match("    ", "hey jarvis"))
        assertEquals(false to "", WakeWordMatcher.match("??!!... ,,,", "hey jarvis"))
    }

    @Test
    fun defaultsToJarvisWhenCustomWakeWordIsNullorBlank() {
        assertEquals(true to "call mom", WakeWordMatcher.match("Hey Jarvis call mom", null))
        assertEquals(true to "call mom", WakeWordMatcher.match("Jarvis call mom", ""))
        assertEquals(true to "call mom", WakeWordMatcher.match("Ok Jarvis call mom", "   "))
    }

    @Test
    fun supportsCustomWakeWordsWithSalutations() {
        assertEquals(true to "turn on lights", WakeWordMatcher.match("Hey Computer turn on lights", "computer"))
        assertEquals(true to "turn on lights", WakeWordMatcher.match("Computer turn on lights", "computer"))
        assertEquals(true to "turn on lights", WakeWordMatcher.match("Ok Computer turn on lights", "computer"))
        assertEquals(true to "open camera", WakeWordMatcher.match("Computer open camera", "hey computer"))
    }
}
