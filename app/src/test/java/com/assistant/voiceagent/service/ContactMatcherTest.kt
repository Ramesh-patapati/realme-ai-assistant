package com.assistant.voiceagent.service

import org.junit.Assert.*
import org.junit.Test

class ContactMatcherTest {

    @Test
    fun exactMatchByFullName() {
        val contacts = listOf(
            ContactRecord("Naveen Kumar", "9876543210")
        )
        val result = ContactMatcher.findBestMatch("naveen kumar", contacts)
        assertTrue(result is ContactMatchResult.Found)
        assertEquals("9876543210", (result as ContactMatchResult.Found).phoneNumber)
    }

    @Test
    fun exactMatchIgnoresTokenOrder() {
        val contacts = listOf(
            ContactRecord("Naveen Kumar", "9876543210")
        )
        val result = ContactMatcher.findBestMatch("kumar naveen", contacts)
        assertTrue(result is ContactMatchResult.Found)
        assertEquals("9876543210", (result as ContactMatchResult.Found).phoneNumber)
    }

    @Test
    fun firstNameOnlyMatchesSingleContact() {
        val contacts = listOf(
            ContactRecord("Naveen Kumar", "9876543210")
        )
        val result = ContactMatcher.findBestMatch("naveen", contacts)
        assertTrue(result is ContactMatchResult.Found)
        assertEquals("9876543210", (result as ContactMatchResult.Found).phoneNumber)
    }

    @Test
    fun firstNameMatchesMultipleContactsReturnsAmbiguous() {
        val contacts = listOf(
            ContactRecord("Naveen Kumar", "9876543210"),
            ContactRecord("Naveen Singh", "9876543211")
        )
        val result = ContactMatcher.findBestMatch("naveen", contacts)
        assertTrue(result is ContactMatchResult.Ambiguous)
        val candidates = (result as ContactMatchResult.Ambiguous).candidates
        assertEquals(2, candidates.size)
    }

    @Test
    fun singleContactMultipleNumbersReturnsBestNumber() {
        val contacts = listOf(
            ContactRecord("Dad", "04012345678", phoneType = 1), // TYPE_HOME
            ContactRecord("Dad", "9876543210", phoneType = 2)   // TYPE_MOBILE
        )
        val result = ContactMatcher.findBestMatch("dad", contacts)
        assertTrue(result is ContactMatchResult.Found)
        assertEquals("9876543210", (result as ContactMatchResult.Found).phoneNumber)
    }

    @Test
    fun singleContactMultipleNumbersPrefersPrimary() {
        val contacts = listOf(
            ContactRecord("Dad", "04012345678", phoneType = 1, isPrimary = true),
            ContactRecord("Dad", "9876543210", phoneType = 2, isPrimary = false)
        )
        val result = ContactMatcher.findBestMatch("dad", contacts)
        assertTrue(result is ContactMatchResult.Found)
        assertEquals("04012345678", (result as ContactMatchResult.Found).phoneNumber)
    }

    @Test
    fun singleContactMultipleNumbersPrefersSuperPrimary() {
        val contacts = listOf(
            ContactRecord("Dad", "1111111111", phoneType = 2, isPrimary = true, isSuperPrimary = false),
            ContactRecord("Dad", "2222222222", phoneType = 1, isPrimary = false, isSuperPrimary = true)
        )
        val result = ContactMatcher.findBestMatch("dad", contacts)
        assertTrue(result is ContactMatchResult.Found)
        assertEquals("2222222222", (result as ContactMatchResult.Found).phoneNumber)
    }

    @Test
    fun noMatchReturnsNotFound() {
        val contacts = listOf(
            ContactRecord("Naveen Kumar", "9876543210")
        )
        val result = ContactMatcher.findBestMatch("xyz", contacts)
        assertTrue(result is ContactMatchResult.NotFound)
    }

    @Test
    fun emptyQueryReturnsNotFound() {
        val contacts = listOf(
            ContactRecord("Naveen Kumar", "9876543210")
        )
        assertTrue(ContactMatcher.findBestMatch("", contacts) is ContactMatchResult.NotFound)
        assertTrue(ContactMatcher.findBestMatch("   ", contacts) is ContactMatchResult.NotFound)
    }

    @Test
    fun emptyContactListReturnsNotFound() {
        val contacts = emptyList<ContactRecord>()
        assertTrue(ContactMatcher.findBestMatch("naveen", contacts) is ContactMatchResult.NotFound)
    }

    @Test
    fun exactMatchBeatsSubsetMatch() {
        val contacts = listOf(
            ContactRecord("Naveen Kumar", "9876543210"),
            ContactRecord("Naveen", "9876543211")
        )
        val result = ContactMatcher.findBestMatch("naveen", contacts)
        assertTrue(result is ContactMatchResult.Found)
        assertEquals("9876543211", (result as ContactMatchResult.Found).phoneNumber)
    }

    @Test
    fun countryCodeAddedToLocalNumber() {
        val result = ContactMatcher.ensureCountryCode("9876543210", "91")
        assertEquals("919876543210", result)
    }

    @Test
    fun countryCodeStripsPlus() {
        val result = ContactMatcher.ensureCountryCode("+919876543210", "91")
        assertEquals("919876543210", result)
    }

    @Test
    fun countryCodeStripsLeadingZero() {
        val result = ContactMatcher.ensureCountryCode("09876543210", "91")
        assertEquals("919876543210", result)
    }

    @Test
    fun countryCodePreservesInternational() {
        val result = ContactMatcher.ensureCountryCode("+14155551234", "91")
        assertEquals("14155551234", result)
    }

    @Test
    fun levenshteinDistanceBasicCases() {
        assertEquals(3, ContactMatcher.levenshteinDistance("kitten", "sitting"))
        assertEquals(0, ContactMatcher.levenshteinDistance("abc", "abc"))
        assertEquals(3, ContactMatcher.levenshteinDistance("", "abc"))
    }

    @Test
    fun pickBestNumberPrefersMobile() {
        val contacts = listOf(
            ContactRecord("Dad", "04012345678", phoneType = 1),
            ContactRecord("Dad", "9876543210", phoneType = 2)
        )
        val bestNumber = ContactMatcher.pickBestNumber(contacts)
        assertEquals("9876543210", bestNumber)
    }

    @Test
    fun specialCharactersInNameNormalized() {
        val result1 = ContactMatcher.normalizeContactName("O'Brien")
        val result2 = ContactMatcher.normalizeContactName("O Brien")
        assertEquals(result1, result2)
        
        val contacts = listOf(
            ContactRecord("O'Brien", "12345")
        )
        val matchResult = ContactMatcher.findBestMatch("o brien", contacts)
        assertTrue(matchResult is ContactMatchResult.Found)
    }
}
