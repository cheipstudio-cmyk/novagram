plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    // Firebase FCM plugin — DECLARED but `apply false` at the root.
    // The :app module conditionally calls apply(plugin = "...") only
    // when google-services.json exists on disk. With apply=false the
    // plugin is just put on the classpath; not applying it means CI
    // builds without google-services.json pass cleanly. When Eugenio
    // drops the JSON in app/, the app-module conditional kicks in
    // and Firebase auto-initialization activates.
    id("com.google.gms.google-services") version "4.4.2" apply false
}
