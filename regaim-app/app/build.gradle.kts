import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.regaim.app"
    // The GitHub build runs Gradle 8.9, whose newest Android plugin (8.7) builds against Android 15 (API 35).
    compileSdk = 35

    defaultConfig {
        applicationId = "com.regaim.app"
        minSdk = 26
        // Google Play requires Android 16 (API 36) for new apps and updates from 31.8.2026. The app behaves as an
        // Android 16 app (screen edges, back button) while being built against Android 15's tools.
        targetSdk = 36
        // Every GitHub build gets a higher number on its own, matching "גרסה N" on the Releases page.
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        // The version's number grows with time (minutes), not with GitHub's run counter: that counter starts again
        // from 1 when the build file is renamed, and a lower number can't install over the app. Fixed once per
        // build (kept in a file), so the tests, the APK and the protected APK all get the same number.
        versionCode = if (runNumber == 0) 100 else {
            val kept = rootProject.file("app/build/version-code.txt")
            kept.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
                ?: (System.currentTimeMillis() / 60_000L - 29_000_000L).toInt().also { kept.parentFile.mkdirs(); kept.writeText(it.toString()) }
        }
        // The build's number people see ("גרסה 121"): GitHub's counter started again from 1 when the build file was
        // renamed (after build 116), so 116 is added to keep counting up from where it was.
        val shown = if (runNumber == 0) 0 else runNumber + 116
        versionName = "0.42 · build $shown"
        if (runNumber != 0) rootProject.file("app/build/version-name.txt").also { it.parentFile.mkdirs(); it.writeText(versionName!!) }
        // Modern phones only (64-bit ARM): leaves out the extra copies of the AI libraries, about half the download.
        ndk { abiFilters += listOf("arm64-v8a") }
        // The QA build (automatic tests) turns this on: no paid AI calls, no updates, no crash reports (TestMode.kt).
        buildConfigField("boolean", "TEST_MODE", "false")
    }

    // A fixed prototype key, so each new build installs over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("regaim-debug.keystore")
            storePassword = "regaim123"
            keyAlias = "regaim"
            keyPassword = "regaim123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            // The APK families install: not debuggable (a debuggable app's private files can be copied off the phone
            // over a USB cable). The real signature is added after the build (scripts/sign_release.py).
            isDebuggable = false
        }
        // The protected build: the code shrunk and its names scrambled (R8), signed with the same key so it installs
        // over the test build. The test build (debug) stays as it is until the protected one is tried on a phone.
        // The QA build for the automatic tests on an emulator (Maestro): its own app next to the real one, and the
        // emulator's processors (x86_64) besides the phones' (arm64).
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = " QA"
            isDebuggable = true
            buildConfigField("boolean", "TEST_MODE", "true")
            ndk { abiFilters += listOf("x86_64") }
            matchingFallbacks += listOf("debug")
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // ContextWrapper is supplied by the Android mockable JAR. Profile
        // tests override preferences with a real in-memory implementation.
        unitTests.isReturnDefaultValues = true
    }

    androidResources {
        noCompress.add("tflite")
    }

    // A newer build toolchain brings new lint checks: they're reported (in the build's reports), not a failed build.
    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // Lets Android finish finding faces in the background even after it closed the app (official, by Google).
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("org.tensorflow:tensorflow-lite-task-audio:0.4.4")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("androidx.media3:media3-transformer:1.3.1")
    implementation("androidx.media3:media3-effect:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
    // Paying for books through Google Play (off until the server turns payments on).
    implementation("com.android.billingclient:billing:8.0.0")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
}

// The app's font (SIL Open Font License, from Google Fonts): Heebo, for all text and headings.
// Downloaded once at build time if they aren't in the project yet. If the download fails the build still works,
// and the app falls back to the phone's own fonts.
val fetchFonts by tasks.registering {
    val dir = file("src/main/assets/fonts")
    val files = mapOf(
        "heebo.ttf" to "https://github.com/google/fonts/raw/main/ofl/heebo/Heebo%5Bwght%5D.ttf",
        "heebo-OFL.txt" to "https://github.com/google/fonts/raw/main/ofl/heebo/OFL.txt",
        // Gelasio (SIL Open Font License): an elegant serif with Georgia's proportions, for the "Moments" wordmark.
        "gelasio.ttf" to "https://github.com/google/fonts/raw/main/ofl/gelasio/Gelasio%5Bwght%5D.ttf",
        "gelasio-OFL.txt" to "https://github.com/google/fonts/raw/main/ofl/gelasio/OFL.txt",
    )
    doLast {
        dir.mkdirs()
        for ((name, url) in files) {
            val out = File(dir, name)
            if (out.exists() && out.length() > 1000) continue
            try {
                (URL(url).openConnection().apply { connectTimeout = 15000; readTimeout = 30000 }).getInputStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                if (out.length() < 1000) { out.delete(); logger.warn("Font file too small, skipped: $name") }
            } catch (e: Exception) {
                out.delete()
                logger.warn("Font download failed for $name: ${e.message}")
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchFonts) }
