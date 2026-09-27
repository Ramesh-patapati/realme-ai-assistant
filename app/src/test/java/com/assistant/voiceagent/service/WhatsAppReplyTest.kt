package com.assistant.voiceagent.service

import com.assistant.voiceagent.service.WhatsAppNotificationService
import org.junit.Assert.*
import org.junit.Test

class WhatsAppReplyTest {

    @Test
    fun testPrefixStripping() {
        val testCases = mapOf(
            "yes tell him to wait for me" to "wait for me",
            "tell her to come downstairs" to "come downstairs",
            "tell them to start the meeting" to "start the meeting",
            "yes reply I am on the way" to "I am on the way",
            "reply I will reach in 10 minutes" to "I will reach in 10 minutes",
            "say I am driving right now" to "I am driving right now",
            "send that I have already reached" to "I have already reached",
            "send yes I will be there" to "yes I will be there",
            "I'm at the coffee shop" to "I'm at the coffee shop",
            "Okay see you soon" to "Okay see you soon"
        )

        for ((input, expected) in testCases) {
            val actual = WhatsAppNotificationService.cleanReplyPrefixes(input)
            assertEquals("Failed for input '$input'", expected, actual)
        }
    }

    @Test
    fun testCancellationWords() {
        val cancelWords = listOf("no", "stop", "close", "cancel", "never mind", "don't reply", "no reply", "nothing")
        for (word in cancelWords) {
            val isCancelled = word.lowercase().trim() in listOf(
                "no", "stop", "close", "cancel", "never mind", "don't reply", "no reply", "nothing"
            )
            assertTrue("Expected cancellation for '$word'", isCancelled)
        }
    }
}
