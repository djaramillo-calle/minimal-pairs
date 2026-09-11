# --- kotlinx.serialization (standard keep rules) ---
# Keep `Companion` object fields of serializable classes so the plugin-generated
# serializer lookup (Companion.serializer()) works after shrinking.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Don't warn about missing optional dependencies of the serialization runtime.
-dontwarn kotlinx.serialization.**

# --- the app's own @Serializable models ---
# The domain models are only accessed through generated serializers; keep them
# whole so field names (used by @SerialName / property names) survive R8.
-keep,includedescriptorclasses class com.djaramillo.minimalpairs.domain.model.**$$serializer { *; }
-keepclassmembers class com.djaramillo.minimalpairs.domain.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.djaramillo.minimalpairs.domain.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
