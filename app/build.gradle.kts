import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { stream ->
        localProps.load(stream)
    }
}

android {
    namespace = "com.jarvis.smarthomelauncher"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.jarvis.smarthomelauncher"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Required by Tuya SDK for native C++ libraries
        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }

        // Change 6.1: Map Tuya App SDK credentials from local.properties or gradle.properties to AndroidManifest Placeholders
        val tuyaAppKey = (localProps.getProperty("tuyaAppKey")
            ?: localProps.getProperty("TUYA_APP_KEY")
            ?: project.findProperty("tuyaAppKey") as? String
            ?: "").trim()
        val tuyaAppSecret = (localProps.getProperty("tuyaAppSecret")
            ?: localProps.getProperty("TUYA_APP_SECRET")
            ?: project.findProperty("tuyaAppSecret") as? String
            ?: "").trim()
        manifestPlaceholders["TUYA_APP_KEY"] = tuyaAppKey
        manifestPlaceholders["TUYA_APP_SECRET"] = tuyaAppSecret
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            pickFirsts.add("lib/**/libc++_shared.so")
            useLegacyPackaging = true // Instructs Android to extract Tuya's native libraries
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // DocumentFile for SAF Local Photo Folder & Subfolder Tree Traversal (Fix #4)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Jetpack Room Database for Reliable Single Source of Truth
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // Jetpack Security Crypto for Hardware-Backed EncryptedSharedPreferences Vault (Phase 1.1)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Loads Tuya's custom-built security-algorithm-1.0.0-beta.aar from app/libs
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    implementation("com.thingclips.smart:thingsmart:6.11.0") {
        exclude(group = "com.thingclips.smart", module = "thingsmart-modularCampAnno")
    }
    // Required by Tuya SDK for JSON parsing
    implementation("com.alibaba:fastjson:1.1.71.android")
    // Fix Tuya's outdated SoLoader namespace conflict
    implementation("com.facebook.soloader:soloader:0.10.5")

    // Extended Material Icons for Smart Home Controls, Cameras & Doorbells
    implementation("androidx.compose.material:material-icons-extended")

    // LibVLC Engine for Hardware-Accelerated Imou & Ezviz H.265/H.264 RTSP Streams
    implementation("org.videolan.android:libvlc-all:3.6.3")

    // ExoPlayer / Media3 for Nest HLS, WebRTC-HLS & DASH Cloud Camera Streams
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // OkHttp Pooled HTTP/2, WebSockets (HA, LG webOS WSS 3001, Shelly RPC) & EventSource (F-25)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Google Play Services Home & Thread Network for Local Matter NOC / Commissioning (F-24, F-25, F-28)
    implementation("com.google.android.gms:play-services-home:16.0.0")
    implementation("com.google.android.gms:play-services-threadnetwork:16.2.1")

    // Coroutines & Android Media Router for Nest Mini Cast & Spotify/YouTube Music
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media:media:1.7.0")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}