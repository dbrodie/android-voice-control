# R8 rules for the app. Used by release builds and by `-PminifyDebug=true` debug builds.
# On top of these: proguard-android-optimize.txt (AGP default), the rules aapt2 generates for every class named
# in the manifest/layouts (activities, services, receivers, the debug ContentProvider installers, ...), and the
# consumer rules shipped inside the dependencies:
#   - stream-webrtc-android: `-keep class org.webrtc.** { *; }` (JNI calls into and out of libjingle_peerconnection_so)
#   - OkHttp / Okio (META-INF/proguard/okhttp3.pro, okio.pro), kotlinx-coroutines, AndroidX.

# Readable stack traces: keep line numbers, hide the original file names (mapping.txt restores them).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# libwebrtc's native code looks up Java classes, fields and methods by name (FindClass/GetMethodID) and calls
# our observer implementations through the org.webrtc interfaces. The AAR's consumer rule already keeps all of
# org.webrtc; repeated here so a dependency update that drops or narrows it can't silently break voice.
# Debug FakeMic also reads WebRtcAudioRecord's private `audioRecord` field by reflection.
-keep class org.webrtc.** { *; }

# Keep our own class names (members are still renamed, unused code still removed). Logs print states and
# transports by class name (`voice -> VoiceState$Connecting@...`, `opening GPT-Live call (CodexLiveTransport)`)
# and error messages fall back to `e.javaClass.simpleName`; without this they read `s0.m@d4414e7` / `(h)`.
# Costs ~50 KB (3%) of dex.
-keepnames class com.brainstormity.voicecontrol.**
