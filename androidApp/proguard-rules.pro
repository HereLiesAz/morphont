# kotlinx.serialization: keep the generated serializers of the project model
# (Glyph, GlyphCorner, ContourData, Pt, Guide, GhostData, FontMetrics). The library ships
# consumer rules for the runtime; these cover the app's own @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.hereliesaz.morphont.** {
    *** Companion;
    static ** $serializer;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.hereliesaz.morphont.**$$serializer { *; }

# Compose Multiplatform resources are looked up by generated accessor names.
-keep class com.hereliesaz.morphont.resources.** { *; }
