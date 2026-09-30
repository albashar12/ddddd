SETUP
1. Android Studio > New Project > Empty Views Activity (Kotlin), package com.example.assistant
2. Copy the .kt files into app/src/main/java/com/example/assistant/
3. Replace AndroidManifest.xml; put accessibility_config.xml in app/src/main/res/xml/
4. app/build.gradle: add  implementation("com.squareup.okhttp3:okhttp:4.12.0")
   and in defaultConfig:  buildConfigField("String", "ANTHROPIC_API_KEY", "\"YOUR_KEY\"")
   and in android {}:     buildFeatures { buildConfig = true }
5. Run on a real phone. Tap "Enable screen control" and turn the service on.

JARVIS UPGRADE
- Add Persona.kt and JarvisService.kt to the same package. Brain.kt now uses JARVIS_PROMPT.
- Also add to build.gradle:  implementation("androidx.core:core-ktx:1.13.1")
- Open the app > Start Jarvis. Say "Jarvis, open YouTube", "Jarvis, turn on the flashlight".
- "Pick up" / "hang up" work instantly with no wake word.
- Android 14+: grant mic permission BEFORE starting the service, or it will crash.
- Turn off battery optimisation for the app so Android doesn't kill it.

DAILY-LIFE UPGRADE
- Replace PhoneActions.kt; add JarvisNotificationListener.kt. Manifest, Persona, Brain, MainActivity were patched.
- To let Jarvis read notifications: say "Jarvis, open notification access settings" (or Settings > Notification access) and switch it on.
- Contacts permission is requested at start so "call Mom" works through find_contact.

LIFE + LANGUAGES UPGRADE
- New files: Speaker.kt, LifeTools.kt.  Replaced: Brain.kt, JarvisService.kt, Persona.kt.
- Patched: PhoneActions.kt, AssistantAccessibilityService.kt, accessibility_config.xml, AndroidManifest.xml, MainActivity.kt.
- Turn the accessibility service OFF and ON once after updating (it gained screenshot permission).
- Web search is an Anthropic server tool: it must be enabled for your API key's organisation (Console > Privacy/Tools settings).
- Change language by voice: "Jarvis, speak Bangla / Hindi / Spanish ...". Install that language's speech pack in
  Android Settings > System > Languages > Google voice typing (offline) and Text-to-speech voice data.
