# 🤝 Codex & Antigravity Collaboration Guide
**Project:** Realme AI Assistant (Jarvis)  
**Target Hardware:** Realme 7 Pro (RMX2170, Qualcomm Snapdragon 720G, Android 11/12 with ColorOS / Realme UI)  
**Repository:** `https://github.com/Ramesh-patapati/realme-ai-assistant`

---

## 1. Project Goal & Overview
Build a high-performance, real-time AI voice assistant for Android that:
1. **Listens Hands-Free**: Listens for wake words (e.g. `"Hey Jarvis"`, `"Jarvis"`) from lock screen or standby.
2. **Executes Phone Automations**:
   - Phone calls (Contacts lookup + `ACTION_CALL`)
   - WhatsApp / Gmail notification auto-read and voice replies
   - YouTube playback (music / video search)
   - Food ordering (Zomato navigation)
   - Device controls (Home, Back, Volume, Flashlight)
   - General knowledge Q&A via AI Engine
3. **Dual Engine Support**: Google Gemini API & OpenAI Codex / GPT-4o with strict Function Calling.

---

## 2. Updated Modular Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                        MainActivity (UI Layer)                         │
│  - Quick Talk Button                                                   │
│  - Wake Word Presets ("Hey Jarvis", "Jarvis", "Assistant")             │
│  - API Key Configuration (Gemini / OpenAI)                             │
│  - System Permission & Accessibility Toggles                           │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Intents
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│             LockScreenVoiceService (Foreground Service)                 │
│  - Single Microphone Owner in App                                      │
│  - WakeLock Management                                                 │
│  - Continuous Voice Gate (ContinuousVoiceDetector.kt)                  │
│  - Audio Routing & TTS Speech Dispatcher                               │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Spoken text
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                           AIEngine.kt                                  │
│  - Bounded 10s timeout with coroutine cancellation                     │
│  - Orchestrator delegating to specialized providers:                   │
├───────────────────────────────────┬────────────────────────────────────┤
│         CommandParser.kt          │         OpenAiProvider.kt          │
│  - Sub-millisecond deterministic  │  - Strict Function Calling (tools) │
│    local regex matching (< 1ms)   │  - JSON Schema typed arguments     │
├───────────────────────────────────┼────────────────────────────────────┤
│        GeminiProvider.kt          │        SpeechInputManager.kt       │
│  - Structured schema JSON         │  - API 31+ On-Device Speech        │
│  - 3.6-flash / 3.5-flash-lite     │    recognition when available      │
└───────────────────────────────────┴────────────────────────────────────┘
                                    │ AIAction
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                       PhoneActionsManager.kt                           │
│  - Phone calls, Accessibility gestures, App launches, Media playback   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Real Device Constraints & Hardware Findings (Realme 7 Pro)

1. **ColorOS / OEM Audio HAL**:
   - Calling `SpeechRecognizer.startListening()` in a tight 5-second loop triggers `OplusAtlasAudioDetectionManager` and `adev_get_parameters:get_audiodet_call` on Qualcomm hardware, causing clicking / buzzer sounds and dropping user speech.
   - **Resolution**: `ContinuousVoiceDetector` (16kHz PCM `AudioRecord`) continuously monitors sound level with zero audio HAL restarts, and only activates `SpeechRecognizer` when voice energy is detected.

2. **Microphone Exclusivity**:
   - Centralized 100% of microphone ownership in `LockScreenVoiceService` to eliminate duplicate audio record conflicts.

3. **Background Battery & Process Killing**:
   - Foreground service running with `android:foregroundServiceType="microphone|mediaPlayback"` and `PARTIAL_WAKE_LOCK`.

---

## 4. Updates Implemented Based on Codex Review
- ✅ **Strict OpenAI Function Calling (`tools` schema)**: Implemented in `OpenAiProvider.kt` with validated JSON parameter schemas for `call_contact`, `send_whatsapp`, `play_youtube`, `order_food`, `device_control`, and `clarify`.
- ✅ **Deterministic Local Command Router**: Implemented in `CommandParser.kt` for instant execution without network latency.
- ✅ **Modular Provider Architecture**: Decoupled `AIEngine.kt` into `CommandParser`, `OpenAiProvider`, and `GeminiProvider`.
- ✅ **Bounded Request Timeouts**: Added a strict 10-second bounded timeout with coroutine cancellation.
- ✅ **On-Device Speech Recognition**: Updated `SpeechInputManager.kt` to prefer `SpeechRecognizer.createOnDeviceSpeechRecognizer` on API 31+ when available.
