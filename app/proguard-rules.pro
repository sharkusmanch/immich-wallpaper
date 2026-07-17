# --- kotlinx-serialization keep rules ---
# Keep `Companion` object fields of serializable classes so that
# serializer lookup via companion works after shrinking.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <2>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` on serializable objects (data object SourceSpec variants).
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep generated $$serializer classes.
-keepclassmembers class **$$serializer {
    *** INSTANCE;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Serialization core relies on these annotations at runtime.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Don't warn on optional serialization internals.
-dontwarn kotlinx.serialization.**

# OkHttp platform hooks (optional on Android).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
