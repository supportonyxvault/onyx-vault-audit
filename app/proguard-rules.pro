# ONYX VAULT Proguard Rules

-keep class com.onyxvault.messenger.crypto.WireMessage { *; }
-keep class com.onyxvault.messenger.crypto.EncryptedMessage { *; }
-keep class com.onyxvault.messenger.model.** { *; }

# Tor Native Library
-keep class org.torproject.jni.** { *; }
