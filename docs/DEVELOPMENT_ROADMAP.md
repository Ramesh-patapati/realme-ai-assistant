# SiriPulse Development Roadmap

## Working method

Build the assistant in small, reviewable changes. For each change:

1. State the behavior being fixed or added and the acceptance checks.
2. Change only the files needed for that step and preserve pre-existing local work.
3. Run focused automated checks, build a debug APK, and inspect the diff.
4. For phone-only behavior, install that exact APK on the Realme 7 Pro and record the result before marking the step complete.
5. Keep secrets, device data, APKs, extracted packages, and other local artifacts out of Git commits.

## Milestones

### 0. Establish a repeatable build and test loop — completed

- [x] Confirm the repository, branch, remote, current commit, and local changes.
- [x] Confirm an Android debug build workflow exists in GitHub Actions.
- [x] Obtain a working JDK 17 + Android SDK build environment via GitHub Actions CI pipeline.
- [x] Run `assembleDebug` and record the first verified source revision (`c3d9f40`).
- [x] Add focused automated tests for deterministic command parsing, contact normalization, WhatsApp reply stripping, and conversational follow-ups.
- [x] Add host-side automated ADB test harness (`scripts/test_device.ps1`) passing 9/9 checks against Realme 7 Pro.

### 1. Make local phone commands dependable

- [ ] Validate contact permission, exact/multiword/first-name matching, duplicate contacts, and ambiguous matches.
- [ ] Validate direct calls, WhatsApp draft handoff, and truthful success/failure prompts.
- [ ] Ensure local commands remain offline and never guess between different recipients.

### 2. Make voice capture reliable

- [ ] Verify microphone ownership and handoff between wake detection, active speech recognition, TTS, and notification replies.
- [ ] Validate wake phrase, one-breath command, silence/noise, lock screen, and service restart behavior.
- [ ] Record Realme 7 Pro results and logs for each scenario.

### 3. Add bounded conversational context

- [ ] Verify context for follow-up questions and references such as “call him.”
- [ ] Verify ambiguous references ask a question instead of choosing a contact.
- [ ] Verify session reset on idle timeout and service restart, and review what history is sent to cloud providers.

### 4. Finish app setup and interface

- [ ] Verify each runtime permission and settings shortcut on Android 11/12.
- [ ] Check the redesigned screen on the target device at normal and large font sizes.
- [ ] Make service status reflect the actual running state.

### 5. Prepare a release build

- [ ] Review manifest permissions, privacy behavior, and release configuration.
- [ ] Configure signing without committing keys or passwords.
- [ ] Produce and install a release candidate, then run the acceptance checklist.

## Current workspace snapshot

- Repository: `Ramesh-patapati/realme-ai-assistant`, remote `origin` is configured.
- Current branch: `main`, one local commit ahead of `origin/main` at the last inspection.
- Existing local edits include the tracked `SiriPulse_Realme7Pro.apk` and source edits to AI conversation context.
- Local untracked items include `ai_assistant_prefs.xml`, `app-debug.zip`, and `extracted_apk/`. Treat these as user data/build artifacts; do not add them to commits without explicit review.
- The GitHub Actions workflow `.github/workflows/build-apk.yml` builds a debug APK with JDK 17.
- At the last environment check, Java/JDK, Android SDK/ADB, and the GitHub CLI were not available on PATH. The repository contains the POSIX `gradlew` script but no `gradlew.bat`, so a local Windows build has not yet been established.
- The bundled Git runtime does not currently contain the `remote-https` helper, so GitHub fetch/push from this environment is unavailable. The configured remote URL alone does not prove authenticated GitHub access.
- Recent AI context changes are source-only and have not yet passed a build or device test.

## Definition of done for a milestone

A milestone is complete only when its acceptance checks pass, the source diff is reviewed, and the result is recorded here. Device-specific failures remain open until retested on the Realme 7 Pro.
