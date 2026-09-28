package com.assistant.voiceagent.service

import com.assistant.voiceagent.model.AIAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    // 1. Phone Calling
    @Test
    fun parsesMultiwordCallContactLocally() {
        assertEquals(AIAction.Call("Naveen Kumar"), CommandParser.parseDeterministic("Hey Jarvis, call to Naveen Kumar"))
    }

    // 2. WhatsApp Messaging
    @Test
    fun preservesWhatsAppMessageCaseAndPunctuation() {
        assertEquals(
            AIAction.SendWhatsApp("Naveen Kumar", "Hi Naveen! I'll call at 5:30."),
            CommandParser.parseDeterministic("Hey Jarvis, send WhatsApp message to Naveen Kumar saying Hi Naveen! I'll call at 5:30.")
        )
    }

    @Test
    fun asksForMessageWhenWhatsAppTextIsMissing() {
        val result = CommandParser.parseDeterministic("send WhatsApp message to Naveen Kumar")
        assertTrue(result is AIAction.Clarify)
    }

    @Test
    fun parsesWhatsAppContactAndMessageWithoutSeparatorWords() {
        assertEquals(
            AIAction.SendWhatsApp("Mom", "I am on my way"),
            CommandParser.parseDeterministic("whatsapp Mom I am on my way")
        )
        assertEquals(
            AIAction.SendWhatsApp("Brother", "please call me back"),
            CommandParser.parseDeterministic("text brother please call me back")
        )
        assertEquals(
            AIAction.SendWhatsApp("Dad", "I reached safely"),
            CommandParser.parseDeterministic("send a message to Dad I reached safely")
        )
    }

    @Test
    fun preservesMultiWordContactBeforeNaturalMessageOpening() {
        assertEquals(
            AIAction.SendWhatsApp("Naveen Kumar", "I am on my way"),
            CommandParser.parseDeterministic("whatsapp Naveen Kumar I am on my way")
        )
    }

    @Test
    fun choosesLongestKnownMultiWordContactForUnmarkedMessage() {
        assertEquals(
            AIAction.SendWhatsApp("Naveen Kumar", "lunch at 8"),
            CommandParser.parseDeterministic(
                "whatsapp Naveen Kumar lunch at 8",
                listOf("Naveen", "Naveen Kumar")
            )
        )
    }

    @Test
    fun cleansPunctuationAtNaturalContactBoundaryButPreservesMessagePunctuation() {
        assertEquals(
            AIAction.SendWhatsApp("Mom", "I am on my way!"),
            CommandParser.parseDeterministic("WhatsApp Mom, I am on my way!")
        )
    }

    @Test
    fun asksForClarificationInsteadOfGuessingInsideAnUnmarkedMultiWordPhrase() {
        val result = CommandParser.parseDeterministic("whatsapp Naveen Kumar lunch at 8")
        assertTrue(result is AIAction.Clarify)
    }

    @Test
    fun requestsMessageWhenKnownContactIsProvidedWithoutMessage() {
        val result = CommandParser.parseDeterministic("whatsapp Naveen Kumar", listOf("Naveen Kumar"))
        assertTrue(result is AIAction.Clarify)
        assertEquals("What message should I send to Naveen Kumar?", (result as AIAction.Clarify).question)
    }

    // 3. Greetings & Identity
    @Test
    fun testGreetings() {
        val greetings = listOf("hi", "hello", "hey", "hello jarvis", "hi jarvis", "hey there")
        for (g in greetings) {
            val result = CommandParser.parseDeterministic(g)
            assertTrue("Expected Answer for '$g', got $result", result is AIAction.Answer)
            assertEquals("Hello! How can I help you today?", (result as AIAction.Answer).replyText)
        }
    }

    @Test
    fun testHowAreYou() {
        val result = CommandParser.parseDeterministic("how are you")
        assertTrue(result is AIAction.Answer)
        assertEquals("I am doing great and ready to assist you!", (result as AIAction.Answer).replyText)
    }

    @Test
    fun testWhoAreYou() {
        val result = CommandParser.parseDeterministic("who are you")
        assertTrue(result is AIAction.Answer)
        assertEquals("I am Jarvis, your personal AI voice assistant.", (result as AIAction.Answer).replyText)
    }

    // 4. Stop Words & Cancellation
    @Test
    fun testStopCommands() {
        val stopWords = listOf("stop", "close", "bye", "cancel", "never mind", "quit", "exit", "shut up")
        for (word in stopWords) {
            val result = CommandParser.parseDeterministic(word)
            assertEquals("Expected Stop for '$word'", AIAction.Stop, result)
        }
    }

    // 5. Conversational Filler Stripping
    @Test
    fun testFillerStrippingOnAppLaunch() {
        val result = CommandParser.parseDeterministic("can you please open whatsapp")
        assertTrue(result is AIAction.OpenApp)
        assertEquals("whatsapp", (result as AIAction.OpenApp).appName.lowercase())
    }

    // 6. Device Controls: Battery, Volume, Media
    @Test
    fun testBatteryQueries() {
        val queries = listOf("what is my battery", "battery status", "battery level", "check battery")
        for (q in queries) {
            val result = CommandParser.parseDeterministic(q)
            assertTrue("Expected DeviceControl BATTERY for '$q', got $result", result is AIAction.DeviceControl)
            assertEquals("BATTERY", (result as AIAction.DeviceControl).command)
        }
    }

    @Test
    fun testVolumeControls() {
        val up = CommandParser.parseDeterministic("volume up")
        assertTrue(up is AIAction.DeviceControl)
        assertEquals("VOLUME_UP", (up as AIAction.DeviceControl).command)

        val down = CommandParser.parseDeterministic("volume down")
        assertTrue(down is AIAction.DeviceControl)
        assertEquals("VOLUME_DOWN", (down as AIAction.DeviceControl).command)
    }

    @Test
    fun testMediaControls() {
        val pause = CommandParser.parseDeterministic("pause music")
        assertTrue(pause is AIAction.DeviceControl)
        assertEquals("MEDIA_PAUSE", (pause as AIAction.DeviceControl).command)

        val resume = CommandParser.parseDeterministic("resume music")
        assertTrue(resume is AIAction.DeviceControl)
        assertEquals("MEDIA_PLAY", (resume as AIAction.DeviceControl).command)
    }

    // 7. Camera Controls
    @Test
    fun testCameraControls() {
        val photo = CommandParser.parseDeterministic("take a photo")
        assertTrue(photo is AIAction.DeviceControl)
        assertEquals("TAKE_PHOTO", (photo as AIAction.DeviceControl).command)

        val selfie = CommandParser.parseDeterministic("take a selfie")
        assertTrue(selfie is AIAction.DeviceControl)
        assertEquals("FRONT_CAMERA", (selfie as AIAction.DeviceControl).command)
    }

    // 8. Navigation Controls
    @Test
    fun testNavigation() {
        val home = CommandParser.parseDeterministic("go home")
        assertTrue(home is AIAction.DeviceControl)
        assertEquals("HOME", (home as AIAction.DeviceControl).command)

        val back = CommandParser.parseDeterministic("go back")
        assertTrue(back is AIAction.DeviceControl)
        assertEquals("BACK", (back as AIAction.DeviceControl).command)
    }

    // 9. Time & Date
    @Test
    fun testTimeAndDate() {
        val time = CommandParser.parseDeterministic("what time is it")
        assertTrue(time is AIAction.Answer)
        assertTrue((time as AIAction.Answer).replyText.startsWith("It is"))
    }
}
