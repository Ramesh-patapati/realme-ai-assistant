package com.assistant.voiceagent.service

import com.assistant.voiceagent.model.AIAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMemoryTest {
    @Test
    fun resolvesContactPronounToMostRecentContact() {
        val memory = ConversationMemory()
        memory.rememberTurn("Call Naveen", AIAction.Call("Naveen"))

        assertEquals(AIAction.Call("Naveen"), memory.resolveContactReference(AIAction.Call("him")))
        assertEquals(
            AIAction.SendWhatsApp("Naveen", "I am on my way"),
            memory.resolveContactReference(AIAction.SendWhatsApp("her", "I am on my way"))
        )
    }

    @Test
    fun asksInsteadOfGuessingWhenThereIsNoRecentContact() {
        val result = ConversationMemory().resolveContactReference(AIAction.Call("them"))

        assertTrue(result is AIAction.Clarify)
    }

    @Test
    fun exposesRecentTurnsAndCapsHistory() {
        val memory = ConversationMemory(maxHistoryMessages = 4)
        memory.beginTurn(100L)
        memory.rememberTurn("First", AIAction.Answer("First response"))
        memory.rememberTurn("Second", AIAction.Answer("Second response"))
        memory.rememberTurn("Third", AIAction.Answer("Third response"))

        assertEquals(
            listOf(
                "user" to "Second",
                "assistant" to "Second response",
                "user" to "Third",
                "assistant" to "Third response"
            ),
            memory.beginTurn(200L)
        )
    }

    @Test
    fun expiresHistoryAndContactAfterIdleTimeout() {
        val memory = ConversationMemory(idleTimeoutMs = 100L)
        memory.beginTurn(100L)
        memory.rememberTurn("Call Alex", AIAction.Call("Alex"))

        assertTrue(memory.beginTurn(201L).isEmpty())
        assertTrue(memory.resolveContactReference(AIAction.Call("her")) is AIAction.Clarify)
    }
}
