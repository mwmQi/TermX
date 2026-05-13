# Add project specific ProGuard rules here.
# You can control the set of applied configuration rules using the
# proguardFiles setting in build.gradle.

# Keep terminal-related classes
-keep class com.termx.app.terminal.** { *; }
-keep class com.termx.app.session.** { *; }

# Keep Kotlin metadata
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Don't warn about unsupported classes
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
