# Realme 7 Pro AI Voice Assistant (SiriPulse)

A custom, high-speed Siri-like voice agent built natively for Android (Realme 7 Pro). 
It works seamlessly even when your phone is **locked and asleep in your pocket**.

---

## 🌟 Key Features

1. 💬 **WhatsApp Auto-Announce & Voice Reply:**
   * When a WhatsApp message arrives while your phone is locked, the assistant speaks aloud:  
     *"New WhatsApp message from Rahul: 'Are you coming?' Would you like to reply?"*
   * Your phone opens the mic for 5 seconds.
   * If you say: *"Yes, I am on my way"*, it sends the reply silently through Android's notification pipeline without unlocking your phone.
   * If you say *"No"* or stay silent, it ignores the prompt.

2. 📞 **Voice Calls & Contacts:**
   * Say: *"Call Mom"* or *"Call Alex"* $\rightarrow$ Looks up your contacts and dials immediately.

3. 🎵 **YouTube Auto-Play:**
   * Say: *"Play Believer on YouTube"* $\rightarrow$ Opens YouTube and starts playing the track.

4. 🍔 **Food Ordering (Zomato):**
   * Say: *"Order Chicken Biryani on Zomato"* $\rightarrow$ Searches the item and opens your cart ready for payment.

5. 🧠 **Multi-AI Brain (Gemini + ChatGPT):**
   * Ask any general question: *"Where is Hyderabad?"* or *"Who won the match?"*
   * Sub-second voice response powered by Google Gemini 2.0 Flash (Free) or OpenAI ChatGPT.

6. 🎧 **Earphone & Bluetooth Button Wake-Up:**
   * Click the button on your wired earphones or Bluetooth earbuds to talk to your assistant when locked.

---

## 🚀 How to Build the APK (2 Options)

### Option 1: Cloud Build via GitHub (Easiest - No Android Studio Needed!)
1. Create a new repository on [GitHub](https://github.com/new).
2. Upload this `AI AGENT` project folder to GitHub.
3. GitHub Actions will automatically start building your `.apk` in the **Actions** tab.
4. When finished (approx. 2 minutes), download `Realme-AI-Assistant-Debug-APK` directly onto your phone!

### Option 2: Android Studio (Local Build)
1. Download and install [Android Studio](https://developer.android.com/studio).
2. Open this folder: `Desktop/AI AGENT`.
3. Click **Build** > **Build Bundle(s) / APK(s)** > **Build APK(s)**.
4. Copy `app-debug.apk` to your phone via USB or WhatsApp/Telegram saved messages.

---

## 📱 Realme 7 Pro Setup Instructions (Crucial!)

Once you install the APK on your Realme 7 Pro:

1. **Open the App:**
   * Paste your **Google Gemini API Key** into the box and tap **Save API Configuration**.
2. **Grant Permissions:**
   * Tap **1. Allow WhatsApp/Gmail Access** $\rightarrow$ Enable "AI Assistant Notification Reader".
   * Tap **2. Allow Microphone & Calls** $\rightarrow$ Tap "Allow" on the system dialogs.
3. **Disable Realme Battery Saver:**
   * Tap **3. Disable Realme Sleep (Battery)**.
   * Find this app and set it to **"Don't optimize"** or **"Allow background activity"**.
   * *(Optional: In your recent apps screen, pull down on the app card and tap the Lock icon so Realme UI never closes it).*
4. **Start the Service:**
   * Tap **Start Background Assistant**.
   * You will see a small microphone icon in your notification bar.

---

## 🧪 How to Test It

* **Test Voice Commands:** Tap the blue floating microphone button in the app and say *"Where is Hyderabad?"* or *"Call [Friend's Name]"*.
* **Test Lock-Screen WhatsApp:**
  * Lock your Realme 7 Pro.
  * Ask a friend to send you a WhatsApp message.
  * Your phone will speak the message aloud and listen for your voice reply!
