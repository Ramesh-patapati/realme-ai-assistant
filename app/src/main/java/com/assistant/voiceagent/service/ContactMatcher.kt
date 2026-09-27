package com.assistant.voiceagent.service

data class ContactRecord(
    val displayName: String,
    val phoneNumber: String,
    val phoneType: Int = 0,
    val isPrimary: Boolean = false,
    val isSuperPrimary: Boolean = false
)

sealed class ContactMatchResult {
    data class Found(val phoneNumber: String, val contactName: String) : ContactMatchResult()
    data class Ambiguous(val candidates: List<Pair<String, String>>) : ContactMatchResult()
    object NotFound : ContactMatchResult()
}

object ContactMatcher {
    fun normalizeContactName(value: String): String {
        return value.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    fun findBestMatch(query: String, contacts: List<ContactRecord>): ContactMatchResult {
        if (query.isBlank() || contacts.isEmpty()) return ContactMatchResult.NotFound
        
        val normalizedQuery = normalizeContactName(query)
        val queryTokens = normalizedQuery.split(" ").filter { it.isNotBlank() }.toSet()
        if (queryTokens.isEmpty()) return ContactMatchResult.NotFound

        // Group by normalized name first
        val groupedContacts = contacts.groupBy { normalizeContactName(it.displayName) }
        
        var bestScore = 0
        val bestMatches = mutableListOf<Pair<String, String>>()

        for ((normalizedName, records) in groupedContacts) {
            if (normalizedName.isBlank()) continue
            val contactTokens = normalizedName.split(" ").filter { it.isNotBlank() }.toSet()
            
            val score = calculateScore(normalizedQuery, queryTokens, normalizedName, contactTokens)
            if (score > 0) {
                if (score > bestScore) {
                    bestScore = score
                    bestMatches.clear()
                    val bestNumber = pickBestNumber(records)
                    bestMatches.add(records.first().displayName to bestNumber)
                } else if (score == bestScore) {
                    val bestNumber = pickBestNumber(records)
                    bestMatches.add(records.first().displayName to bestNumber)
                }
            }
        }

        return when {
            bestMatches.isEmpty() -> ContactMatchResult.NotFound
            bestMatches.size == 1 -> ContactMatchResult.Found(bestMatches[0].second, bestMatches[0].first)
            else -> ContactMatchResult.Ambiguous(bestMatches)
        }
    }

    private fun calculateScore(
        normalizedQuery: String, 
        queryTokens: Set<String>, 
        normalizedName: String, 
        contactTokens: Set<String>
    ): Int {
        // Score 3: Exact match (full string or token set equality)
        if (normalizedQuery == normalizedName || queryTokens == contactTokens) return 3
        // Score 2: All query tokens present in contact tokens (subset match)
        if (queryTokens.isNotEmpty() && contactTokens.containsAll(queryTokens)) return 2
        
        // Score 1: Prefix match - query is a prefix of any token in the contact (min 3 chars)
        if (normalizedQuery.length >= 3 && queryTokens.size == 1) {
            val singleToken = queryTokens.first()
            if (contactTokens.any { it.startsWith(singleToken) && it != singleToken }) {
                return 1
            }
        }
        
        return 0
    }

    fun pickBestNumber(numbers: List<ContactRecord>): String {
        if (numbers.isEmpty()) return ""
        
        val superPrimary = numbers.find { it.isSuperPrimary }
        if (superPrimary != null) return superPrimary.phoneNumber
        
        val primary = numbers.find { it.isPrimary }
        if (primary != null) return primary.phoneNumber
        
        val mobile = numbers.find { it.phoneType == 2 }
        if (mobile != null) return mobile.phoneNumber
        
        return numbers.first().phoneNumber
    }

    fun ensureCountryCode(phoneNumber: String, defaultCountryCode: String = "91"): String {
        val cleaned = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (cleaned.startsWith("+")) {
            return cleaned.substring(1) // Strip '+' and return digits only
        }
        
        val digitsOnly = cleaned.replace(Regex("[^0-9]"), "")
        if (digitsOnly.length == 10) {
            return "$defaultCountryCode$digitsOnly"
        }
        
        if (digitsOnly.startsWith("0")) {
            return "$defaultCountryCode${digitsOnly.substring(1)}"
        }
        
        return digitsOnly
    }

    fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }

        for (i in 0..a.length) {
            for (j in 0..b.length) {
                if (i == 0) {
                    dp[i][j] = j
                } else if (j == 0) {
                    dp[i][j] = i
                } else {
                    dp[i][j] = minOf(
                        dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1
                    )
                }
            }
        }
        return dp[a.length][b.length]
    }
}
