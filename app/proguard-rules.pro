# The native engine binds these by name (RegisterNatives in jni_bridge.cpp) and ART reads
# @CriticalNative from the dex, so the ink bridge keeps its names, members and annotations.
-keepattributes *Annotation*
-keep class app.noti.ink.** { *; }
