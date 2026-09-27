package com.assistant.voiceagent.service

import com.assistant.voiceagent.service.PhoneActionsManager
import org.junit.Assert.*
import org.junit.Test

class ContactNormalizationTest {

    @Test
    fun testContactNameNormalization() {
        val cases = mapOf(
            "Naveen Hostel" to "naveen hostel",
            "Dr. Ramesh (Office)" to "dr ramesh office",
            "Mom & Dad" to "mom dad",
            "  Alice   Smith  " to "alice smith",
            "John-Doe_123" to "john doe 123",
            "Brother @ Home" to "brother home"
        )

        for ((input, expected) in cases) {
            val actual = PhoneActionsManager.normalizeContactName(input)
            assertEquals("Normalization mismatch for '$input'", expected, actual)
        }
    }

    @Test
    fun testPhoneNumberNormalization() {
        val validCases = mapOf(
            "+91 98765-43210" to "+919876543210",
            "(080) 1234-5678" to "08012345678",
            "+1 (555) 234-5678" to "+15552345678",
            "9876543210" to "9876543210",
            "100" to "100"
        )

        for ((input, expected) in validCases) {
            val actual = PhoneActionsManager.normalizePhoneNumber(input)
            assertEquals("Phone number normalization mismatch for '$input'", expected, actual)
        }

        val invalidCases = listOf("abc", "", "12", "phone#")
        for (input in invalidCases) {
            val actual = PhoneActionsManager.normalizePhoneNumber(input)
            assertNull("Expected null for invalid phone number '$input'", actual)
        }
    }
}
