package com.assistant.voiceagent.model

sealed class AIAction {
    data class Call(val contactName: String) : AIAction()
    data class SendWhatsApp(val contactName: String, val message: String) : AIAction()
    data class PlayYouTube(val songOrQuery: String) : AIAction()
    data class OrderFood(val item: String, val restaurant: String? = null) : AIAction()
    data class DeviceControl(val command: String, val speech: String) : AIAction()
    data class Answer(val replyText: String) : AIAction()
    data class Clarify(val question: String) : AIAction()
    object Stop : AIAction()
    data class Unknown(val rawText: String) : AIAction()
}
