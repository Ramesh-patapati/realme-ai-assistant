# Project Tasks & Roadmap

See [the staged development roadmap](../docs/DEVELOPMENT_ROADMAP.md) for the build/test process and milestone acceptance checks.

## Current engineering gates
- [ ] Run `testDebugUnitTest` on JDK 17.
- [ ] Run `assembleDebug` and install that exact artifact on the Realme 7 Pro.
- [ ] Record pass/fail results in `docs/DEVELOPMENT_ROADMAP.md` before closing the milestone.

## 📌 High Priority (Current Sprint)
- [x] Integrate case-preserving WhatsApp command parser (`CommandParser.kt`)
- [x] Configure silent audio energy gate to stop OEM HAL clicks (`ContinuousVoiceDetector.kt`)
- [x] Add screen wake-up on speech detection (`LockScreenVoiceService.kt`)
- [x] Voice battery inquiry ("Hey Jarvis, what is my battery level?")
- [x] Volume and media controls ("Turn up volume", "Pause music", "Mute")
- [ ] Test offline voice calling on Realme 7 Pro with multi-word contact names
- [ ] Validate WhatsApp chat pre-fill handoff on lock screen

## 📋 Backlog (Future Features)
- [ ] Flashlight toggle via voice ("Turn on flashlight")
- [ ] Optional automated WhatsApp send via Accessibility Service
