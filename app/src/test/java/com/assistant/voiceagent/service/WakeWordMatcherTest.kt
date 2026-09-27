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
    }

    @Test
    fun doesNotUseBroadAliasesUnlessTheyAreConfigured() {
        assertEquals(false to "", WakeWordMatcher.match("Assistant, call Mom", "hey jarvis"))
        assertEquals(true to "call mom", WakeWordMatcher.match("Assistant, call Mom", "assistant"))
    }

    @Test
    fun requiresAWordBoundaryAfterWakePhrase() {
        assertEquals(false to "", WakeWordMatcher.match("Jarvison call Mom", "jarvis"))
    }
}
