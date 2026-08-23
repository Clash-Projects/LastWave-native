# Retrofit/OkHttp ship their own consumer rules; these silence R8 warnings
# on optional dependencies they reference reflectively but this app doesn't use.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# kotlinx.serialization: keep generated serializers and Serializable models
-keepclassmembers class com.lastwave.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.lastwave.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lastwave.app.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses @kotlinx.serialization.Serializable class * { *; }
-keep,includedescriptorclasses class * implements kotlinx.serialization.KSerializer { *; }

# Room: entities/DAOs are referenced by generated code via reflection-free
# codegen already, but keep annotations so schema export keeps working.
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class *

# Hilt/Dagger generated components
-keep class dagger.hilt.internal.aggregatedroot.codegen.** { *; }
-keep class hilt_aggregated_deps.** { *; }

# Glance app widgets and action callbacks
-keep class * implements androidx.glance.appwidget.action.ActionCallback { *; }
-keep class * extends androidx.glance.appwidget.action.ActionCallback { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class com.lastwave.app.widget.** { *; }

# NewPipe's YouTube extractor loads service implementations and its
# JavaScript deobfuscation engine dynamically.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-dontwarn java.beans.**
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
