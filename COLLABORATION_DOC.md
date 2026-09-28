# 🤝 Codex & Antigravity Collaboration Protocol & Role Definition
**Project:** Realme AI Assistant (Jarvis)  
**Target Hardware:** Realme 7 Pro (RMX2170, Qualcomm Snapdragon 720G, Android 11/12 with ColorOS / Realme UI)  
**Repository:** `https://github.com/Ramesh-patapati/realme-ai-assistant`

---

## 1. Division of Roles & Responsibilities

To maximize speed and eliminate duplicate effort, the work is divided based on each platform's unique strengths:

```
┌───────────────────────────────────────────────┐     ┌───────────────────────────────────────────────┐
│              OPENAI CODEX                     │     │              ANTIGRAVITY (AGY)                │
│  - Deep Architectural Design                  │     │  - Physical Device ADB Automation             │
│  - Pure Kotlin Logic & Algorithm Optimization │     │  - CI/CD Build & Artifact Management          │
│  - Pure Unit Test Suite Creation              │     │  - Realme 7 Pro Hardware / Audio HAL Testing  │
│  - Complex Parsing & Normalization Logic      │     │  - Live Integration Verification              │
│  - Code Review & Security Analysis            │     │  - App Deployment & Runtime Permissions Grant │
└───────────────────────┬───────────────────────┘     └───────────────────────┬───────────────────────┘
                        │                                                     │
                        └───────────────────► Git Repo ◄──────────────────────┘
                                    (Branch: `codex-dev` / `main`)
```

### 🧠 OpenAI Codex Responsibilities:
1. **Core Architecture & Pure Logic**:
   - Design and refine clean Kotlin classes with zero Android framework dependencies (such as `ContactMatcher.kt`, `WakeWordMatcher.kt`, `ConversationMemory.kt`, `CommandParser.kt`).
2. **Comprehensive Unit Testing**:
   - Author thorough JUnit 4 test suites that validate edge cases, string normalizations, scoring tiers, and memory states without needing a physical phone.
3. **Logic Verification & Code Review**:
   - Review Antigravity's Android integration changes, identifying potential concurrency issues, memory leaks, or missing edge cases.
4. **Git Branch Workflow**:
   - Commit and push changes to dedicated feature branches (e.g., `codex-dev` or `codex/feature-name`) on GitHub.

---

### ⚡ Antigravity (AGY) Responsibilities:
1. **Hardware & OS Integration**:
   - Manage real hardware quirks on Realme 7 Pro (Qualcomm Snapdragon 720G, ColorOS / Android 12), including AudioRecord HAL (*Hardware Abstraction Layer — software connecting Android to the physical microphone*), WakeLocks, and TelecomManager.
2. **Physical Device Automation via ADB**:
   - Run automated ADB (*Android Debug Bridge — the tool communicating with the connected phone*) test scripts (`scripts/test_device.ps1`), checking live background services, notification listeners, and audio permissions.
3. **CI/CD & Deployment**:
   - Trigger, monitor, and manage GitHub Actions CI (*Continuous Integration — automated cloud building of APKs*), download compiled APKs, and install them onto the phone via ADB.
4. **Branch Merging & Integration Testing**:
   - Fetch Codex's branches, merge them into `main`, verify the full test suite in CI, deploy to the physical device, and record live device logs.

---

## 2. Git Branching & Synchronization Protocol

To ensure no uncommitted local work is overwritten and changes are easily compared:

### Step 1: Codex Working on a Dedicated Branch
- Codex commits changes to a branch named `codex-dev` or `codex/<feature-topic>`:
  ```bash
  git checkout -b codex-dev
  # Make changes
  git add .
  git commit -m "Description of changes"
  git push origin codex-dev
  ```

### Step 2: Antigravity Reviews, Merges, and Tests on Device
- Antigravity fetches the branch from GitHub:
  ```bash
  git fetch origin codex-dev
  git merge origin/codex-dev
  ```
- Antigravity runs unit tests and pushes to `main` to trigger the cloud CI build.
- Antigravity installs the resulting APK on the Realme 7 Pro and runs the automated test harness (`test_device.ps1`).

### Step 3: Feedback Loop
- Antigravity updates `DEVELOPMENT_ROADMAP.md` and reports hardware test outcomes back in the repository for Codex to inspect on the next sync.

---

## 3. High-Priority Engineering Roadmap

### Milestone 1: Local Phone Commands Dependability (IN PROGRESS)
- [x] Extract `ContactMatcher.kt` with pure Kotlin scoring (exact, subset, prefix) and intelligent multi-number grouping.
- [x] Add WhatsApp country code normalizer (`ensureCountryCode`) for local 10-digit Indian numbers.
- [x] Unit test suite with 18 tests (`ContactMatcherTest.kt`) — all passing in CI.
- [ ] Expand `CommandParser.kt` natural WhatsApp syntax (e.g., "whatsapp Mom I am on my way" without requiring "saying/that").
- [ ] Automated end-to-end intent validation on Realme 7 Pro via ADB.

### Milestone 2: Microphone & Audio Capture Reliability
- [ ] Continuous voice energy detection tuning (RMS thresholds on 20ms frames).
- [ ] Zero audio HAL collision between `AudioRecord` background listener and `Google SpeechRecognizer`.
- [ ] Instant spoken wake confirmation ("Yes, I'm listening!").

### Milestone 3: Bounded Conversational Context
- [ ] Contextual pronoun follow-ups ("call him" / "message her") using `ConversationMemory.kt`.
- [ ] 10-minute idle memory expiration.

### Milestone 4: Setup, Permissions & Release
- [ ] Battery optimization exemption and background overlay permissions guide.
- [ ] Final release APK signing and automated test verification.
