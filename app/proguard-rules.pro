# AEGIS uses no reflection-based serialization; Room, Compose and AndroidX ship their own
# consumer rules. Keep line numbers so local stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
