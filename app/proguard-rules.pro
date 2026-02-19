# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# --- Preserve line numbers for crash reports ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.camera.**$$serializer { *; }
-keepclassmembers class com.example.camera.** {
    *** Companion;
}

# --- Hilt ---
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# --- Strip debug logs in release ---
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
    public static int w(...);
}

# --- Network data classes ---
-keep class com.example.camera.data.network.HandshakeMessage { *; }
-keep class com.example.camera.data.network.ConnectedPeer { *; }
-keep class com.example.camera.data.network.DiscoveredPeer { *; }
-keep class com.example.camera.data.network.CameraStreamState { *; }
-keep class com.example.camera.data.network.PeerConnectionState { *; }

# --- SRT (srtdroid native) ---
-keep class io.github.thibaultbee.srtdroid.** { *; }
