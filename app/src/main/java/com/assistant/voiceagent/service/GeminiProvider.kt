package com.assistant.voiceagent.service

import android.util.Log
import com.assistant.voiceagent.model.AIAction
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class GeminiProvider(private val client: OkHttpClient) {

    private val geminiModels = listOf(
        "gemini-3.6-flash",
        "gemini-3.5-flash-lite"
    )

    private val systemInstruction = """
        You are Jarvis, a fast voice assistant with full control over an Android phone.
        Speed is critical. Spoken answers must be punchy, natural, and under 1-2 sentences.

        Analyze the user's voice command and respond STRICTLY with a single JSON object:
        - If stopping: {"action": "STOP", "speech": "Stopping now."}
        - If incomplete/ambiguous: {"action": "CLARIFY", "question": "<Question>"}
        - If phone navigation/hardware: {"action": "DEVICE_CONTROL", "command": "HOME|BACK|SCREENSHOT|NOTIFICATIONS|LOCK|SCROLL_DOWN|SCROLL_UP", "speech": "<Short speech>"}
        - If calling: {"action": "CALL", "contact": "<Name>", "speech": "Calling <Name>"}
        - If WhatsApp: {"action": "WHATSAPP", "contact": "<Name>", "message": "<Text>", "speech": "Sending to <Name>"}
        - If YouTube: {"action": "YOUTUBE", "query": "<Song/Video>", "speech": "Playing <Song> on YouTube"}
        - If food: {"action": "ZOMATO", "item": "<Food>", "restaurant": "<Optional>", "speech": "Opening Zomato"}
        - If general question: {"action": "ANSWER", "speech": "<Short spoken answer>"}
    """.trimIndent()

    fun callGeminiWithFallback(userInput: String, apiKey: String): AIAction {
        var lastError: Exception? = null
        for (model in geminiModels) {
            try {
                return callGemini(userInput, apiKey, model)
            } catch (e: Exception) {
                lastError = e
                Log.w("GeminiProvider", "Model $model failed (${e.message}), trying next fallback...")
            }
        }
        Log.e("GeminiProvider", "All Gemini models failed", lastError)
        return AIAction.Answer("Connection was slow. Could you repeat that?")
    }

    private fun callGemini(userInput: String, apiKey: String, model: String): AIAction {
        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", userInput)
                        })
                    })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", systemInstruction)
                    })
                })
            })

            val genConfig = JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.2)
                put("maxOutputTokens", 200)
                if (model.contains("3.6")) {
                    put("thinkingConfig", JSONObject().apply {
                        put("thinkingBudget", 0)
                    })
                }
            }
            put("generationConfig", genConfig)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                Log.e("GeminiProvider", "Gemini HTTP ${response.code} error: $errorBody")
                throw RuntimeException("Gemini HTTP ${response.code}: $errorBody")
            }

            val body = response.body?.string() ?: throw RuntimeException("Empty response")
            val json = JSONObject(body)
            val candidates = json.optJSONArray("candidates") ?: throw RuntimeException("No candidates in response")
            if (candidates.length() == 0) throw RuntimeException("Empty candidates list")

            val text = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            return parseGeminiJsonResponse(text)
        }
    }

    private fun parseGeminiJsonResponse(rawText: String): AIAction {
        try {
            val clean = rawText.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(clean)
            val action = obj.optString("action", "ANSWER").uppercase()

            return when (action) {
                "CALL" -> {
                    val contact = obj.optString("contact", "").trim()
                    if (contact.isNotBlank()) AIAction.Call(contact) else AIAction.Clarify("Who would you like me to call?")
                }
                "WHATSAPP" -> {
                    val contact = obj.optString("contact", "").trim()
                    val message = obj.optString("message", "").trim()
                    if (contact.isNotBlank() && message.isNotBlank()) AIAction.SendWhatsApp(contact, message)
                    else AIAction.Clarify("What message should I send to $contact?")
                }
                "YOUTUBE" -> {
                    val query = obj.optString("query", "").trim()
                    AIAction.PlayYouTube(query)
                }
                "ZOMATO" -> {
                    val item = obj.optString("item", "").trim()
                    val restaurant = if (obj.has("restaurant") && !obj.isNull("restaurant")) obj.getString("restaurant") else null
                    AIAction.OrderFood(item, restaurant)
                }
                "DEVICE_CONTROL" -> {
                    val command = obj.optString("command", "HOME").uppercase()
                    val speech = obj.optString("speech", "Done")
                    AIAction.DeviceControl(command, speech)
                }
                "CLARIFY" -> {
                    val question = obj.optString("question", "Could you clarify that?")
                    AIAction.Clarify(question)
                }
                "STOP" -> AIAction.Stop
                else -> {
                    val speech = obj.optString("speech", "")
                    if (speech.isNotBlank()) AIAction.Answer(speech) else AIAction.Answer(rawText)
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiProvider", "Error parsing JSON response: $rawText", e)
            return AIAction.Answer(rawText)
        }
    }
}
