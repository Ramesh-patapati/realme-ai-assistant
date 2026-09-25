# Product Requirements & Feature Specifications

## 1. Core Voice Assistant Vision
An always-listening, offline-capable Android voice assistant tailored for the Realme 7 Pro (Qualcomm Snapdragon 720G / ColorOS). It acts as a personal Jarvis that can make calls, open WhatsApp chats with prefilled text, and control device settings hands-free.

## 2. Priority Feature: Direct Call & WhatsApp Handoff
### Requirements:
1. **Calling**:
   - Voice command: `"Hey Jarvis, call [Contact Name]"` or `"Jarvis, call to [Contact Name]"`.
   - Action: Query Android contacts via `CONTENT_FILTER_URI` with case-insensitive fallback. If found and permitted, launch `ACTION_CALL` directly.
   - Offline handling: Must execute locally under 5ms without cloud API calls.
2. **WhatsApp**:
   - Voice command: `"Hey Jarvis, send a WhatsApp message to [Contact Name] saying [Message]"`.
   - Action: Identify contact and preserve exact message case/punctuation. Wake screen and open WhatsApp chat with message prefilled.
   - Clarity: Voice prompt must clearly notify user: *"Opening WhatsApp with message for [Contact Name]"*.

## 3. Device Quirks & OEM Constraints
- Realme ColorOS blocks background activity starts unless **"Display over other apps" (Floating Window)** is enabled.
- Tight audio recording loops crash the audio HAL; continuous audio monitoring must use PCM threshold gating without repeated initialization.
