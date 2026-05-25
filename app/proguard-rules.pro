# TDLib JNI classes - keep all to avoid breaking native bindings
-keep class org.drinkless.tdlib.** { *; }
-keep class org.drinkless.td.** { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class com.secondream.turbogram.**$$serializer { *; }
-keepclassmembers class com.secondream.turbogram.** {
    *** Companion;
}
-keepclasseswithmembers class com.secondream.turbogram.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Compose
-dontwarn androidx.compose.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
