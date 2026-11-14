# JNI: the Rust core looks these up by name.
-keep class com.v1svaldis.openspeak.core.NativeCore { *; }
-keep interface com.v1svaldis.openspeak.core.NativeCore$Listener { *; }
-keep class * implements com.v1svaldis.openspeak.core.NativeCore$Listener { *; }

# libwebrtc calls back into Java through JNI.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
