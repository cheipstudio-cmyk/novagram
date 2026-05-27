import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.secondream.cheipgram"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.secondream.cheipgram"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "0.5.8"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        vectorDrawables {
            useSupportLibrary = true
        }

        // Baked-in Telegram API credentials. Default values are the ones the
        // app ships with; they can be overridden at build time via either
        // gradle.properties (TG_API_ID, TG_API_HASH) or environment variables
        // (ORG_GRADLE_PROJECT_TG_API_ID, ORG_GRADLE_PROJECT_TG_API_HASH) from
        // CI. If you ever rotate the credentials, set GitHub Secrets and the
        // workflow injects them without touching source.
        val tgApiId = providers.gradleProperty("TG_API_ID").orNull
            ?: System.getenv("TG_API_ID") ?: "15141440"
        val tgApiHash = providers.gradleProperty("TG_API_HASH").orNull
            ?: System.getenv("TG_API_HASH") ?: "3f1300e0ccb2c1e07e6e9f4ac5ddc387"
        buildConfigField("int", "TG_API_ID", tgApiId)
        buildConfigField("String", "TG_API_HASH", "\"$tgApiHash\"")
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
            java.srcDirs("src/main/java")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String?
                keyPassword = keystoreProperties["keyPassword"] as String?
                storeFile = keystoreProperties["storeFile"]?.let { file(it.toString()) }
                storePassword = keystoreProperties["storePassword"] as String?
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePropertiesFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        getByName("debug") {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/*.kotlin_module"
        )
    }
}

dependencies {
    implementation(fileTree("libs") { include("*.aar") })

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-text-google-fonts:1.7.2")

    implementation("io.coil-kt:coil-compose:2.7.0")

    // Lottie for animated stickers in .tgs format (gzipped JSON). We gunzip
    // the file in-memory and feed the resulting JSON string to Lottie via
    // LottieCompositionSpec.JsonString — Telegram's TGS is just Lottie JSON
    // wrapped in gzip with a custom file extension.
    implementation("com.airbnb.android:lottie-compose:6.5.2")

    // Media3 / ExoPlayer for WebM stickers (video-format animated stickers).
    // We use the raw exoplayer module plus the minimal ui module just for
    // PlayerView; the sticker view embeds PlayerView via AndroidView without
    // any controls (useController = false, autoplay+loop+muted).
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    implementation("androidx.documentfile:documentfile:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
