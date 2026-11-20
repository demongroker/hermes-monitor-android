# Hermes Monitor ProGuard rules.

# EncryptedSharedPreferences / Tink: keep crypto internals intact under R8.
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# OkHttp platform probes (consumer rules ship with the lib; these silence optionals).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# Keep the WorkManager worker entry points (reflection-instantiated).
-keep class com.demongroker.hermesmonitor.AlertWorker { <init>(...); }
