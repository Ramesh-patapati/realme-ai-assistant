package com.assistant.voiceagent.service

import android.content.Context
import android.util.Log
import com.assistant.voiceagent.model.AIAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AIEngine(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val openAiProvider = OpenAiProvider(httpClient)
    private val geminiProvider = GeminiProvider(httpClient)

    /**
     * Process a user's spoken command with:
     * 1. Fast deterministic local matching (< 1ms, zero network)
     * 2. Bounded AI call (OpenAI Function Calling or Gemini Structured Output) with 10-second hard timeout
     */
    suspend fun processCommand(
        userInput: String,
        preferredEngine: String,
        geminiKey: String,
        openAiKey: String
    ): AIAction = withContext(Dispatchers.IO) {
        // Step 1: Fast deterministic local parsing
        val localAction = CommandParser.parseDeterministic(userInput)
        if (localAction != null) {
            Log.d("AIEngine", "Command resolved locally: $localAction")
            return@withContext localAction
        }

        // Step 2: Bounded Network AI Call (10 seconds max)
        val result = withTimeoutOrNull(10_000L) {
            try {
                if (preferredEngine.equals("chatgpt", ignoreCase = true) && openAiKey.isNotBlank()) {
                    openAiProvider.callOpenAiWithTools(userInput, openAiKey)
                } else if (geminiKey.isNotBlank()) {
                    geminiProvider.callGeminiWithFallback(userInput, geminiKey)
                } else {
                    AIAction.Answer("Please set up your API key in the app settings.")
                }
            } catch (e: Exception) {
                Log.e("AIEngine", "AI execution failed", e)
                AIAction.Answer("I had trouble connecting to the network. Please try again.")
            }
        }

        return@withContext result ?: AIAction.Answer("Request timed out. Please try again.")
    }
}
