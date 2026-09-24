package com.assistant.voiceagent.service

import android.util.Log
import com.assistant.voiceagent.model.AIAction
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenAiProvider(private val client: OkHttpClient) {

    private val toolsJsonArray = JSONArray("""
        [
            {
                "type": "function",
                "function": {
                    "name": "call_contact",
                    "description": "Call a phone contact by name",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "contact_name": {"type": "string", "description": "Name of the contact"}
                        },
                        "required": ["contact_name"]
                    }
                }
            },
            {
                "type": "function",
                "function": {
                    "name": "send_whatsapp",
                    "description": "Send a WhatsApp message to a contact",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "contact_name": {"type": "string", "description": "Name of the person"},
                            "message": {"type": "string", "description": "Message text"}
                        },
                        "required": ["contact_name", "message"]
                    }
                }
            },
            {
                "type": "function",
                "function": {
                    "name": "play_youtube",
                    "description": "Play a song or video on YouTube",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "query": {"type": "string", "description": "Song title or video search query"}
                        },
                        "required": ["query"]
                    }
                }
            },
            {
                "type": "function",
                "function": {
                    "name": "order_food",
                    "description": "Order food item via Zomato",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "item": {"type": "string", "description": "Food item to order"},
                            "restaurant": {"type": "string", "description": "Optional restaurant name"}
                        },
                        "required": ["item"]
                    }
                }
            },
            {
                "type": "function",
                "function": {
                    "name": "device_control",
                    "description": "Control device navigation or hardware",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "command": {
                                "type": "string",
                                "enum": ["HOME", "BACK", "SCREENSHOT", "NOTIFICATIONS", "LOCK", "SCROLL_DOWN", "SCROLL_UP"]
                            },
                            "speech": {"type": "string", "description": "Spoken confirmation to the user"}
                        },
                        "required": ["command", "speech"]
                    }
                }
            },
            {
                "type": "function",
                "function": {
                    "name": "clarify",
                    "description": "Ask clarification when critical information is missing",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "question": {"type": "string", "description": "Polite question asking for missing info"}
                        },
                        "required": ["question"]
                    }
                }
            }
        ]
    """.trimIndent())

    fun callOpenAiWithTools(userInput: String, apiKey: String): AIAction {
        val payload = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are Jarvis, an ultra-fast voice assistant on an Android phone. Use tools to perform device actions. For general questions, respond directly in 1-2 punchy spoken sentences.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userInput)
                })
            })
            put("tools", toolsJsonArray)
            put("tool_choice", "auto")
            put("max_tokens", 150)
            put("temperature", 0.3)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                Log.e("OpenAiProvider", "OpenAI error ${response.code}: $errorBody")
                return AIAction.Answer("OpenAI service returned an error. Please check your API key.")
            }

            val body = response.body?.string() ?: return AIAction.Answer("Empty response received.")
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices") ?: return AIAction.Answer("No choices returned.")
            if (choices.length() == 0) return AIAction.Answer("No response generated.")

            val message = choices.getJSONObject(0).getJSONObject("message")

            // Check for tool calls first (Function Calling)
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val toolCall = toolCalls.getJSONObject(0)
                val functionObj = toolCall.getJSONObject("function")
                val functionName = functionObj.getString("name")
                val arguments = JSONObject(functionObj.getString("arguments"))
                Log.d("OpenAiProvider", "Tool invoked: $functionName with args: $arguments")

                return when (functionName) {
                    "call_contact" -> {
                        val contact = arguments.optString("contact_name", "")
                        if (contact.isNotBlank()) AIAction.Call(contact) else AIAction.Clarify("Who would you like to call?")
                    }
                    "send_whatsapp" -> {
                        val contact = arguments.optString("contact_name", "")
                        val text = arguments.optString("message", "")
                        if (contact.isNotBlank() && text.isNotBlank()) AIAction.SendWhatsApp(contact, text) else AIAction.Clarify("What message should I send to $contact?")
                    }
                    "play_youtube" -> {
                        val query = arguments.optString("query", "")
                        AIAction.PlayYouTube(query)
                    }
                    "order_food" -> {
                        val item = arguments.optString("item", "")
                        val restaurant = if (arguments.has("restaurant") && !arguments.isNull("restaurant")) arguments.getString("restaurant") else null
                        AIAction.OrderFood(item, restaurant)
                    }
                    "device_control" -> {
                        val command = arguments.optString("command", "HOME")
                        val speech = arguments.optString("speech", "Done")
                        AIAction.DeviceControl(command, speech)
                    }
                    "clarify" -> {
                        val question = arguments.optString("question", "Could you clarify that?")
                        AIAction.Clarify(question)
                    }
                    else -> AIAction.Answer("Action not supported.")
                }
            }

            // Fallback to direct conversational response
            val content = message.optString("content", "")
            return if (content.isNotBlank()) {
                AIAction.Answer(content.trim())
            } else {
                AIAction.Answer("Done!")
            }
        }
    }
}
