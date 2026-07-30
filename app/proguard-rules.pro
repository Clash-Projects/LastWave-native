# Retrofit/OkHttp ship their own consumer rules; these silence R8 warnings
# on optional dependencies they reference reflectively but this app doesn't use.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# kotlinx.serialization: keep the generated $serializer companions and the
# @Serializable models themselves so reflection-free (de)serialization still
# resolves correctly after minification.
-keepclassmembers class com.lastwave.app.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.lastwave.app.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lastwave.app.data.model.**$$serializer { *; }

# Room: entities/DAOs are referenced by generated code via reflection-free
# codegen already, but keep annotations so schema export keeps working.
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class *

# Hilt/Dagger generated components — handled by Hilt's own consumer rules,
# kept explicitly here too since this file is the project's own source of truth.
-keep class dagger.hilt.internal.aggregatedroot.codegen.** { *; }
-keep class hilt_aggregated_deps.** { *; }
