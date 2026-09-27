package com.assistant.voiceagent.service

import android.content.Context
import android.util.Log
import com.assistant.voiceagent.model.AIAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import android.os.SystemClock
import java.util.concurrent.TimeUnit

class AIEngine(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val openAiProvider = OpenAiProvider(httpClient)
    private val geminiProvider = GeminiProvider(httpClient)
    private val conversationMutex = Mutex()
    private val conversationMemory = ConversationMemory()

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
    ): AIAction = conversationMutex.withLock {
        withContext(Dispatchers.IO) {
            val now = SystemClock.elapsedRealtime()
            val historyForRequest = conversationMemory.beginTurn(now)

            // First handle deterministic commands locally. Resolve person references
            // such as "call him" from the recent in-memory conversation context.
            val localAction = CommandParser.parseDeterministic(userInput)
            if (localAction != null) {
                val contextualAction = conversationMemory.resolveContactReference(localAction)
                conversationMemory.rememberTurn(userInput, contextualAction)
                Log.d("AIEngine", "Command resolved locally: $contextualAction")
                return@withContext contextualAction
            }

            // Include recent turns so provider-backed follow-ups retain context.
            val result = withTimeoutOrNull(10_000L) {
                try {
                    if (preferredEngine.equals("chatgpt", ignoreCase = true) && openAiKey.isNotBlank()) {
                        openAiProvider.callOpenAiWithTools(userInput, openAiKey, historyForRequest)
                    } else if (geminiKey.isNotBlank()) {
                        geminiProvider.callGeminiWithFallback(userInput, geminiKey, historyForRequest)
                    } else {
                        AIAction.Answer("Please set up your API key in the app settings.")
                    }
                } catch (e: Exception) {
                    Log.e("AIEngine", "AI execution failed", e)
                    AIAction.Answer("I had trouble connecting to the network. Please try again.")
                }
            } ?: AIAction.Answer("Request timed out. Please try again.")

            val contextualResult = conversationMemory.resolveContactReference(result)
            conversationMemory.rememberTurn(userInput, contextualResult)
            contextualResult
        }
    }
}
