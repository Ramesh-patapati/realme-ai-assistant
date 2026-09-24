# Proguard rules for AI Assistant
-keep class com.assistant.voiceagent.data.model.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
