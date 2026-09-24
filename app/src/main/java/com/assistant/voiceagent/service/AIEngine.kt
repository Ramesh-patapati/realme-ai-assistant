package com.assistant.voiceagent.service

import android.content.Context
import android.util.Log
import com.assistant.voiceagent.model.AIAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AIEngine(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    // 2026 High-speed models with automatic fallback
    private val geminiModels = listOf(
        "gemini-3.6-flash",
        "gemini-3.5-flash-lite"
    )

    private val systemPrompt = """
        You are SiriPulse, a voice assistant with full control over an Android phone.
        Speed is critical. Spoken answers must be punchy, natural, and under 1-2 sentences.

        Analyze the user's voice command and respond STRICTLY with JSON:

        Actions:
        1. If user says 'stop', 'close', 'bye', 'cancel', 'exit', 'never mind':
           {"action": "STOP", "speech": "Stopping now."}
        2. If command is incomplete or ambiguous:
           {"action": "CLARIFY", "question": "<Polite question asking for missing info>"}
        3. If user wants to control phone navigation/hardware (e.g. 'go home', 'go back', 'take a screenshot', 'open notifications', 'lock phone', 'scroll down', 'scroll up'):
           {"action": "DEVICE_CONTROL", "command": "HOME|BACK|SCREENSHOT|NOTIFICATIONS|LOCK|SCROLL_DOWN|SCROLL_UP", "speech": "Going home / Done"}
        4. If user wants to call someone:
           {"action": "CALL", "contact": "<Contact Name>", "speech": "Calling <Name>"}
        5. If user wants to send a WhatsApp message:
           {"action": "WHATSAPP", "contact": "<Contact Name>", "message": "<Message text>", "speech": "Sending WhatsApp to <Name>"}
        6. If user wants to play a song/video:
           {"action": "YOUTUBE", "query": "<Song Name and Artist>", "speech": "Playing <Song> on YouTube"}
        7. If user wants to order food:
           {"action": "ZOMATO", "item": "<Food Item>", "restaurant": "<Optional Restaurant>", "speech": "Opening Zomato for <Item>"}
        8. General question:
           {"action": "ANSWER", "speech": "<Short spoken answer>"}

        Respond ONLY with a single valid JSON object.
    """.trimIndent()

    suspend fun processCommand(
        userInput: String,
        preferredEngine: String,
        geminiKey: String,
        openAiKey: String
    ): AIAction = withContext(Dispatchers.IO) {
        val lower = userInput.trim().lowercase()
        if (lower in listOf("stop", "close", "bye", "goodbye", "cancel", "never mind", "quit", "exit")) {
            return@withContext AIAction.Stop
        }

        // Direct device and common action shortcuts to eliminate network latency
        when {
            lower == "go home" || lower == "home screen" -> return@withContext AIAction.DeviceControl("HOME", "Going to home screen")
            lower == "go back" || lower == "back" -> return@withContext AIAction.DeviceControl("BACK", "Going back")
            lower.contains("take screenshot") || lower.contains("take a screenshot") -> return@withContext AIAction.DeviceControl("SCREENSHOT", "Taking screenshot")
            lower.contains("open notifications") || lower.contains("show notifications") -> return@withContext AIAction.DeviceControl("NOTIFICATIONS", "Opening notifications")
            lower.contains("lock phone") || lower.contains("lock screen") -> return@withContext AIAction.DeviceControl("LOCK", "Locking phone")
            lower.contains("scroll down") -> return@withContext AIAction.DeviceControl("SCROLL_DOWN", "Scrolling down")
            lower.contains("scroll up") -> return@withContext AIAction.DeviceControl("SCROLL_UP", "Scrolling up")
            lower.startsWith("play ") && (lower.contains(" on youtube") || lower.contains(" youtube")) -> {
                val q = lower.removePrefix("play ").replace(" on youtube", "").replace(" youtube", "").trim()
                return@withContext AIAction.PlayYouTube(q)
            }
            lower.startsWith("call ") -> {
                val contact = lower.removePrefix("call ").trim()
                return@withContext AIAction.Call(contact)
            }
            lower.startsWith("order ") || lower.contains("on zomato") -> {
                val dish = lower.removePrefix("order ").replace(" on zomato", "").replace(" zomato", "").trim()
                return@withContext AIAction.OrderFood(dish, null)
            }
        }

        try {
            if (preferredEngine.equals("chatgpt", ignoreCase = true) && openAiKey.isNotBlank()) {
                callOpenAi(userInput, openAiKey)
            } else if (geminiKey.isNotBlank()) {
                callGeminiWithFallback(userInput, geminiKey)
            } else {
                AIAction.Answer("Please enter your API key in the app settings first.")
            }
        } catch (e: Exception) {
            Log.e("AIEngine", "Failed to process command with AI", e)
            AIAction.Answer("Connection was slow. Could you say that again?")
        }
    }

    private fun callGeminiWithFallback(userInput: String, apiKey: String): AIAction {
        for (model in geminiModels) {
            val result = callGemini(userInput, apiKey, model)
            if (result != null) {
                return result
            }
        }
        return AIAction.Answer("I could not reach Gemini AI servers. Please try again.")
    }

    private fun callGemini(userInput: String, apiKey: String, model: String): AIAction? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val jsonBody = JSONObject().apply {
            val contents = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", "$systemPrompt\n\nUser command: $userInput"))
                    })
                })
            }
            put("contents", contents)
            val genConfig = JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 800)
                put("responseMimeType", "application/json")
            }
            if (model.contains("3.6")) {
                genConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
            put("generationConfig", genConfig)
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("AIEngine", "Model $model returned error ${response.code}")
                    return null
                }
                val respString = response.body?.string() ?: return null
                val respJson = JSONObject(respString)
                val candidates = respJson.optJSONArray("candidates")
                val content = candidates?.optJSONObject(0)?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val text = parts?.optJSONObject(0)?.optString("text") ?: ""

                parseJsonToAction(text, userInput)
            }
        } catch (e: Exception) {
            Log.w("AIEngine", "Failed calling model $model: ${e.message}")
            null
        }
    }

    private fun callOpenAi(userInput: String, apiKey: String): AIAction {
        val url = "https://api.openai.com/v1/chat/completions"

        val jsonBody = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("max_tokens", 800)
            put("temperature", 0.2)
            put("response_format", JSONObject().put("type", "json_object"))
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userInput)
                })
            }
            put("messages", messages)
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return AIAction.Answer("OpenAI API returned code ${response.code}")
            }
            val respString = response.body?.string() ?: return AIAction.Answer("Empty response from AI")
            val respJson = JSONObject(respString)
            val choices = respJson.optJSONArray("choices")
            val msg = choices?.optJSONObject(0)?.optJSONObject("message")
            val content = msg?.optString("content") ?: ""

            return parseJsonToAction(content, userInput)
        }
    }

    private fun parseJsonToAction(rawJson: String, fallbackQuery: String): AIAction {
        return try {
            val clean = rawJson.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(clean)
            val action = obj.optString("action", "ANSWER").uppercase()

            when (action) {
                "STOP" -> AIAction.Stop
                "CLARIFY" -> AIAction.Clarify(obj.optString("question", "Could you clarify that?"))
                "DEVICE_CONTROL" -> AIAction.DeviceControl(
                    obj.optString("command", "HOME").uppercase(),
                    obj.optString("speech", "Done")
                )
                "CALL" -> AIAction.Call(obj.optString("contact", ""))
                "WHATSAPP" -> AIAction.SendWhatsApp(
                    obj.optString("contact", ""),
                    obj.optString("message", "")
                )
                "YOUTUBE" -> AIAction.PlayYouTube(obj.optString("query", fallbackQuery))
                "ZOMATO" -> AIAction.OrderFood(
                    obj.optString("item", ""),
                    obj.optString("restaurant", null)
                )
                "ANSWER" -> AIAction.Answer(obj.optString("speech", "Here is what I found."))
                else -> AIAction.Answer(obj.optString("speech", clean))
            }
        } catch (e: Exception) {
            Log.e("AIEngine", "Failed to parse AI output: $rawJson", e)
            AIAction.Answer(rawJson)
        }
    }
}
