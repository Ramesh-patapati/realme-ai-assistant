package com.assistant.voiceagent.service

import android.util.Log
import com.assistant.voiceagent.model.AIAction
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenAiProvider(private val client: OkHttpClient) {

    // Strict OpenAI Function Calling Schema (strict: true, additionalProperties: false, all properties required)
    private val toolsJsonArray = JSONArray("""
        [
            {
                "type": "function",
                "function": {
                    "name": "call_contact",
                    "description": "Call a phone contact by name",
                    "strict": true,
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "contact_name": {
                                "type": "string",
                                "description": "The name of the person or contact to call"
                            }
                        },
                        "required": ["contact_name"],
                        "additionalProperties": false
                    }
                }
            },
            {
                "type": "function",
                "function": {
                    "name": "send_whatsapp",
                    "description": "Open the exact contact's WhatsApp chat with a prefilled message draft; the user reviews and sends it",
                    "strict": true,
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "contact_name": {
                                "type": "string",
                                "description": "The name of the person to message"
                            },
                            "message": {
                                "type": "string",
                                "description": "The exact message content to send"
                            }
                        },
                        "required": ["contact_name", "message"],
                        "additionalProperties": false
                    }
                }
            },
            {
                "type": "function",
                "function": {
                    "name": "play_youtube",
                    "description": "Play a song, artist, or search video on YouTube",
                    "strict": true,
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "query": {
                                "type": "string",
                                "description": "The song title, artist, or video search query"
                            }
                        },
                        "required": ["query"],
                        "additionalProperties": false
                    }
                }
            },
            {
                "type": "function",
                "function": {
                    "name": "order_food",
                    "description": "Order food item via Zomato delivery",
                    "strict": true,
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "item": {
                                "type": "string",
                                "description": "The food item dish to order"
                            },
                            "restaurant": {
                                "type": "string",
                                "description": "Optional restaurant name, or empty string if not specified"
                            }
                        },
                        "required": ["item", "restaurant"],
                        "additionalProperties": false
                    }
                }
            },
            {
                "type": "function",
                "function": {
                    "name": "device_control",
                    "description": "Control device navigation or hardware action",
                    "strict": true,
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "command": {
                                "type": "string",
                                "enum": ["HOME", "BACK", "SCREENSHOT", "NOTIFICATIONS", "LOCK", "SCROLL_DOWN", "SCROLL_UP", "VOLUME_UP", "VOLUME_DOWN", "VOLUME_MUTE", "VOLUME_UNMUTE", "MEDIA_PAUSE", "MEDIA_PLAY"],
                                "description": "The hardware navigation command"
                            },
                            "speech": {
                                "type": "string",
                                "description": "Short spoken feedback to the user"
                            }
                        },
                        "required": ["command", "speech"],
                        "additionalProperties": false
                    }
                }
            },
            {
                "type": "function",
                "function": {
                    "name": "clarify",
                    "description": "Ask the user a short clarifying question when critical details are missing",
                    "strict": true,
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "question": {
                                "type": "string",
                                "description": "The polite question asking for the missing info"
                            }
                        },
                        "required": ["question"],
                        "additionalProperties": false
                    }
                }
            }
        ]
    """.trimIndent())

    suspend fun callOpenAiWithTools(userInput: String, apiKey: String): AIAction {
        val payload = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are Jarvis, a fast voice assistant on Android. Use tools to execute phone actions. For general knowledge, answer in 1-2 concise spoken sentences.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userInput)
                })
            })
            put("tools", toolsJsonArray)
            put("tool_choice", "auto")
            put("max_tokens", 150)
            put("temperature", 0.2)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
        response.use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: ""
                Log.e("OpenAiProvider", "OpenAI HTTP error ${resp.code}: $errorBody")
                return AIAction.Answer("OpenAI service returned an error. Please verify your API key.")
            }

            val body = resp.body?.string() ?: return AIAction.Answer("Empty response from OpenAI.")
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices") ?: return AIAction.Answer("No choices returned.")
            if (choices.length() == 0) return AIAction.Answer("No response generated.")

            val message = choices.getJSONObject(0).getJSONObject("message")

            // Parse tool calls if returned
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val toolCall = toolCalls.getJSONObject(0)
                val functionObj = toolCall.optJSONObject("function") ?: return AIAction.Answer("Invalid tool call structure.")
                val functionName = functionObj.optString("name", "")
                val argumentsStr = functionObj.optString("arguments", "{}")
                val arguments = try { JSONObject(argumentsStr) } catch (e: Exception) { JSONObject() }

                Log.d("OpenAiProvider", "Strict Tool Invoked: $functionName with arguments: $arguments")

                return when (functionName) {
                    "call_contact" -> {
                        val contact = arguments.optString("contact_name", "").trim()
                        if (contact.isNotBlank()) AIAction.Call(contact) else AIAction.Clarify("Who would you like me to call?")
                    }
                    "send_whatsapp" -> {
                        val contact = arguments.optString("contact_name", "").trim()
                        val text = arguments.optString("message", "").trim()
                        if (contact.isNotBlank() && text.isNotBlank()) {
                            AIAction.SendWhatsApp(contact, text)
                        } else if (contact.isBlank()) {
                            AIAction.Clarify("Who should I send the WhatsApp message to?")
                        } else {
                            AIAction.Clarify("What message should I send to $contact?")
                        }
                    }
                    "play_youtube" -> {
                        val query = arguments.optString("query", "").trim()
                        if (query.isNotBlank()) AIAction.PlayYouTube(query) else AIAction.Clarify("What would you like to play on YouTube?")
                    }
                    "order_food" -> {
                        val item = arguments.optString("item", "").trim()
                        val restaurant = arguments.optString("restaurant", "").trim().ifBlank { null }
                        if (item.isNotBlank()) AIAction.OrderFood(item, restaurant) else AIAction.Clarify("What food would you like to order?")
                    }
                    "device_control" -> {
                        val command = arguments.optString("command", "HOME").uppercase()
                        val speech = arguments.optString("speech", "Done")
                        AIAction.DeviceControl(command, speech)
                    }
                    "clarify" -> {
                        val question = arguments.optString("question", "Could you clarify that?")
                        AIAction.Clarify(question)
                    }
                    else -> {
                        Log.w("OpenAiProvider", "Unknown tool name: $functionName")
                        AIAction.Answer("Action not supported.")
                    }
                }
            }

            // Direct text response
            val content = message.optString("content", "")
            return if (content.isNotBlank()) {
                AIAction.Answer(content.trim())
            } else {
                AIAction.Answer("Done!")
            }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            cancel()
        }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) {
                    response.close()
                }
            }
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }
        })
    }
}
