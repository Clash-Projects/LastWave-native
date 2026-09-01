import java.io.File
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.lastwave.app"
    compileSdk = 35

    val localProps = Properties().apply {
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { load(it) }
        }
        val envFile = rootProject.file(".env")
        if (envFile.exists()) {
            envFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                    val parts = trimmed.split("=", limit = 2)
                    setProperty(parts[0].trim(), parts[1].trim())
                }
            }
        }
    }

    fun resolveSecret(vararg keys: String): String {
        for (key in keys) {
            val fromEnv = System.getenv(key)
            if (!fromEnv.isNullOrBlank()) return fromEnv.trim().replace("\r", "").replace("\n", "").replace("\"", "").replace("\\", "")
            val fromGradle = project.findProperty(key) as? String
            if (!fromGradle.isNullOrBlank()) return fromGradle.trim().replace("\r", "").replace("\n", "").replace("\"", "").replace("\\", "")
            val fromLocal = localProps.getProperty(key)
            if (!fromLocal.isNullOrBlank()) return fromLocal.trim().replace("\r", "").replace("\n", "").replace("\"", "").replace("\\", "")
        }
        return ""
    }

    defaultConfig {
        applicationId = "com.lastwave.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 14
        versionName = "3.4.0"

        val secretMask = listOf(0x5A, 0x3F, 0x7E, 0x1B, 0x92, 0x4C, 0xA1, 0x6D)
        fun obfuscateSecret(plainText: String): String {
            if (plainText.isEmpty()) return "new byte[] {}"
            val bytes = plainText.toByteArray(Charsets.UTF_8)
            val obfuscated = bytes.mapIndexed { idx, b -> (b.toInt() xor secretMask[idx % secretMask.size]).toByte() }
            return "new byte[] { " + obfuscated.joinToString(", ") { "(byte) $it" } + " }"
        }
        val maskLiteral = "new byte[] { " + secretMask.joinToString(", ") { "(byte) $it" } + " }"

        val qobuzBackendUrl = resolveSecret("QOBUZ_BACKEND_URL", "QOBUZ_BASE_URL", "BACKEND_BASE_URL")
        buildConfigField("byte[]", "QOBUZ_BACKEND_URL_BYTES", obfuscateSecret(qobuzBackendUrl))

        val qobuzApiKey = resolveSecret("QOBUZ_API_KEY", "QOBUZ_AUTH_KEY", "API_AUTH_KEY")
        buildConfigField("byte[]", "QOBUZ_API_KEY_BYTES", obfuscateSecret(qobuzApiKey))

        val lyricsApiKey = resolveSecret("LYRICS_API_KEY", "API_KEY", "LYRICS_AUTH_TOKEN")
        buildConfigField("byte[]", "LYRICS_API_KEY_BYTES", obfuscateSecret(lyricsApiKey))

        val lastfmApiKey = resolveSecret("LASTFM_API_KEY").ifBlank { "2e00eb783c677abeab81e99c99be74e1" }
        buildConfigField("byte[]", "LASTFM_API_KEY_BYTES", obfuscateSecret(lastfmApiKey))

        val lastfmApiSecret = resolveSecret("LASTFM_API_SECRET").ifBlank { "b7e562de696f17fdfde7c448f02b599f" }
        buildConfigField("byte[]", "LASTFM_API_SECRET_BYTES", obfuscateSecret(lastfmApiSecret))

        buildConfigField("byte[]", "SECRET_MASK_BYTES", maskLiteral)

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    // Android 15+ can boot with 16 KB memory pages; all native
                    // libraries must be built/aligned accordingly. Ignored
                    // harmlessly by NDK toolchains that predate the flag.
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
                cFlags += "-Wl,-z,max-page-size=16384"
                cppFlags += "-Wl,-z,max-page-size=16384"
            }
        }
    }

    signingConfigs {
        create("release") {
            val base64Key = resolveSecret("SIGNING_KEY")
            val storeFilePath = resolveSecret("RELEASE_STORE_FILE")
            val storePasswordProp = resolveSecret("RELEASE_STORE_PASSWORD", "KEY_STORE_PASSWORD")
            val keyAliasProp = resolveSecret("RELEASE_KEY_ALIAS", "ALIAS").ifBlank { "release_key" }
            val keyPasswordProp = resolveSecret("RELEASE_KEY_PASSWORD", "KEY_PASSWORD").ifBlank { storePasswordProp }

            val keystoreFile: File? = when {
                base64Key.isNotBlank() -> {
                    try {
                        val decodedBytes = Base64.getDecoder().decode(base64Key.trim())
                        val tempKeystore = layout.buildDirectory.file("signing/release.keystore").get().asFile
                        tempKeystore.parentFile.mkdirs()
                        tempKeystore.writeBytes(decodedBytes)
                        tempKeystore
                    } catch (_: Exception) {
                        null
                    }
                }
                storeFilePath.isNotBlank() -> file(storeFilePath)
                else -> null
            }

            if (keystoreFile != null && keystoreFile.exists() && storePasswordProp.isNotBlank()) {
                storeFile = keystoreFile
                storePassword = storePasswordProp
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("rawRelease") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            // Raw variant — no code/resource shrinking, no ProGuard/R8
            signingConfig = signingConfigs.getByName("release")
            // proguardFiles from initWith are ignored when minify is off
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by org.jellyfin.media3:media3-ffmpeg-decoder AAR metadata.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.palette)

    // Home-screen "Now Playing" widget (Glance — Compose-style APIs over
    // RemoteViews), driven by the same MediaController access the local
    // scrobbler (MediaScrobbleListenerService) already holds.
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // Required even in a Compose-only app: Theme.Material3.DayNight.NoActionBar
    // (used as the AndroidManifest/splash theme parent in themes.xml) is an XML
    // style resource shipped by this artifact. androidx.compose.material3 is
    // Compose-only Kotlin and contributes no AAPT-resolvable style/ resources,
    // so without this dependency that parent can never be found by the linker.
    implementation(libs.material)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    // Installs the baseline profiles bundled inside Compose (and other
    // androidx) AARs so hot UI paths are AOT-compiled on device instead of
    // running through JIT on first use — a large, zero-code smoothness win
    // for scrolling and animations in release builds.
    implementation(libs.androidx.profileinstaller)

    // Native in-app audio playback, background service, system media
    // controls, Bluetooth/headset controls and a MediaController-backed UI.
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    // MediaBrowserServiceCompat/MediaSessionCompat bridge used by Android
    // Auto to browse the LastWave library and control the same player.
    implementation("androidx.media:media:1.7.0")

    // GPLv3 Media3-matched FFmpeg software decoder (distribution must comply).
    // The renderer factory prefers FFmpeg for every codec it supports so all
    // devices decode through one deterministic, OEM-bug-free path; platform
    // decoders remain as automatic fallbacks.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.2.1+1")

    // Core library desugaring required by the FFmpeg decoder AAR metadata.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Low-latency native output. Version 1.10 remains API-compatible with the
    // requested Oboe 1.8+ baseline and exposes its CMake target through Prefab.
    implementation("com.google.oboe:oboe:1.10.0")

    // Metadata/search remains local InnerTube/NewPipe functionality; playback
    // resolves through InnerTubeX first and retains NewPipe as a fallback.
    implementation(libs.newpipe.extractor)

    // InnerTubeX is invoked through the compatibility adapter because its
    // current release is built with a newer Kotlin metadata version than the
    // app. Runtime-only keeps the app compiler on its existing Kotlin line.
    runtimeOnly(libs.innertubex)
    runtimeOnly("io.ktor:ktor-client-cio:3.5.2")

    // Unit Testing dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

configurations.all {
    resolutionStrategy {
        force("io.github.dokar3:quickjs-kt-android:1.0.12")
    }
}

tasks.withType<Test> {
    maxHeapSize = "2048m"
}
