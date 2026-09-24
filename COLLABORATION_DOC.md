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
3. **Dual Engine Support**: Google Gemini API & OpenAI Codex / GPT-4o.

---

## 2. Current Architecture & State

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
│  - Holds Single Microphone Ownership                                   │
│  - WakeLock Management                                                 │
│  - Audio Routing & TTS Speech Dispatcher                               │
├───────────────────────────────────┬────────────────────────────────────┤
│     ContinuousVoiceDetector       │         SpeechInputManager         │
│  - AudioRecord PCM stream (16kHz) │  - SpeechRecognizer / Whisper API  │
│  - RMS Energy / VAD Detector      │  - Speech-to-Text Transcription    │
├───────────────────────────────────┴────────────────────────────────────┤
│                           AIEngine.kt                                  │
│  - Dual LLM Router: Gemini 3.6/3.5 Flash & OpenAI GPT-4o / Codex       │
│  - System Prompt with JSON Function Schema                             │
│  - Structured Action Dispatcher (AIAction data class)                  │
├────────────────────────────────────────────────────────────────────────┤
│                       PhoneActionsManager.kt                           │
│  - Phone calls, Accessibility gestures, App launches, Media playback   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Real Device Constraints & Hardware Findings (Realme 7 Pro)

1. **ColorOS / OEM Audio HAL**:
   - Calling `SpeechRecognizer.startListening()` in a tight 5-second loop triggers `OplusAtlasAudioDetectionManager` and `adev_get_parameters:get_audiodet_call` on Qualcomm hardware, causing clicking / buzzer sounds and dropping user speech.
   - **Solution in progress**: Using `AudioRecord` (16kHz PCM) on a background thread for silent continuous monitoring, and only activating recognition on voice activity.

2. **Microphone Exclusivity**:
   - On Android, only ONE component in the app process can hold the microphone at a time. All microphone operations are now centralized in `LockScreenVoiceService`.

3. **Background Battery & Process Killing**:
   - Realme UI requires `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and a persistent Foreground Service Notification with a `PARTIAL_WAKE_LOCK`.

---

## 4. Open Questions & Integration Points for Codex

### Question 1: Wake-Word Engine & Voice Pipeline
- Should we keep our current `ContinuousVoiceDetector` (Energy/VAD + On-Demand Speech Recognition), or would Codex recommend integrating a lightweight on-device hotword library (like ONNX / Porcupine / Vosk / PocketSphinx) or direct streaming to OpenAI Whisper?

### Question 2: OpenAI Realtime / Function Calling Schema
- How should we structure the tool-calling schema in `AIEngine.kt` for OpenAI Codex / GPT-4o to maximize speed, reliability, and token efficiency for device automation?

### Question 3: Lock-Screen & Gesture Triggers
- In addition to voice wake words, what auxiliary triggers does Codex recommend (e.g. Floating overlay bubble, Volume long-press via Accessibility, Shake detection, or Proximity sensor swipe)?

---

## 5. How to Collaborate
- **Codex**: Edit backend logic, prompt schemas in `AIEngine.kt`, voice models, or suggest architectural improvements.
- **Antigravity**: Handles real-time Android hardware debugging, ADB logcat verification, ColorOS HAL workarounds, and GitHub Actions build deployments.
- Leave notes and updates in this file under **Changelog & Updates** below.

---

## 6. Changelog & Updates
- *2026-09-24 (Antigravity)*: Centralized microphone ownership into `LockScreenVoiceService`, fixed resource linking, added 1-click wake word presets, and initialized collaboration doc.
