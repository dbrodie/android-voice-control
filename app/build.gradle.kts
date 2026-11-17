import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/** Reads the project-root `.env` (KEY=VALUE, optional quotes, `#` comments, optional `export `). */
fun readDotEnv(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    val out = mutableMapOf<String, String>()
    file.readLines().forEach { raw ->
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        if (line.startsWith("export ")) line = line.removePrefix("export ").trim()
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val key = line.substring(0, eq).trim()
        var value = line.substring(eq + 1).trim()
        if (value.length >= 2 &&
            ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))
        ) {
            value = value.substring(1, value.length - 1)
        } else {
            // strip trailing inline comment for unquoted values
            val hash = value.indexOf(" #")
            if (hash >= 0) value = value.substring(0, hash).trim()
        }
        out[key] = value
    }
    return out
}

val dotEnv = readDotEnv(rootProject.file(".env"))
fun envValue(key: String, default: String = ""): String = dotEnv[key] ?: default
fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * API keys from `.env` are embedded into DEBUG builds only (developer convenience), and only while
 * EMBED_KEYS isn't "false" (in `.env` or the environment). Release builds never embed keys: users enter
 * their JEV key in the app (JevKeyActivity) and voice uses ChatGPT sign-in or an OpenAI key from Settings.
 */
val embedKeys: Boolean = (System.getenv("EMBED_KEYS") ?: envValue("EMBED_KEYS", "true")).trim().lowercase() !in
    setOf("false", "0", "no", "off")
fun debugKey(key: String): String = if (embedKeys) envValue(key) else ""

fun boolProperty(name: String): Boolean? =
    (findProperty(name) as String?)?.trim()?.lowercase()?.let { it in setOf("true", "1", "yes", "on") }

/**
 * Per-ABI APKs (`-PabiSplits=true|false`). Default: on when the requested tasks build only release variants
 * (`./gradlew :app:assembleRelease`, scripts/release.sh), off otherwise, so the debug build stays a single
 * universal `app-debug.apk` for the emulator workflow. AGP's `splits` block is per module, not per build
 * type, hence the switch.
 */
val abiSplits: Boolean = boolProperty("abiSplits") ?: gradle.startParameter.taskNames.let { tasks ->
    tasks.any { it.contains("Release", ignoreCase = true) } && tasks.none { it.contains("Debug", ignoreCase = true) }
}

/** ABIs of the split APKs. Index + 1 is the last digit of each split's versionCode (universal = 0). */
val splitAbis = listOf("armeabi-v7a", "x86", "arm64-v8a", "x86_64")

/** `-PminifyDebug=true`: run R8 (same rules as release) on the debug build, to test the shrunk app with all debug hooks. */
val minifyDebug: Boolean = boolProperty("minifyDebug") ?: false

android {
    namespace = "com.brainstormity.voicecontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.brainstormity.voicecontrol"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"

        // Keys: see embedKeys above. defaultConfig = debug; the release build type overrides both with "".
        buildConfigField("String", "JEV_KEY", debugKey("JEV_KEY").asBuildConfigString())
        buildConfigField("String", "OPENAI_API_KEY", debugKey("OPENAI_API_KEY").asBuildConfigString())
        buildConfigField(
            "String", "OPENAI_REALTIME_MODEL",
            envValue("OPENAI_REALTIME_MODEL", "gpt-live-1").asBuildConfigString()
        )
    }

    signingConfigs {
        // Release signing: keystore/voice-control-release.jks + keystore.properties (both gitignored).
        // Generated locally with keytool; keep them safe, updates must be signed with the same key.
        val props = rootProject.file("keystore.properties")
        if (props.exists()) {
            val ks = Properties()
            props.inputStream().use { stream -> ks.load(stream) }
            create("release") {
                storeFile = rootProject.file(ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: shrink, obfuscate and optimize the code, then drop unused resources. Rules: proguard-rules.pro
            // plus the consumer rules shipped by OkHttp/Okio, coroutines, stream-webrtc-android and AndroidX.
            // Keep app/build/outputs/mapping/release/mapping.txt (scripts/release.sh copies it to dist/) to
            // decode stack traces from that release.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // Never ship keys inside a release APK, whatever .env says.
            buildConfigField("String", "JEV_KEY", "\"\"")
            buildConfigField("String", "OPENAI_API_KEY", "\"\"")
        }
        debug {
            if (minifyDebug) {
                // R8 skips optimization and obfuscation for debuggable builds, so this build is not debuggable
                // (BuildConfig.DEBUG = false, no `run-as`) to get exactly the release R8 output. The debug
                // source set (DebugReceiver & co.), debug signing and the embedded .env keys stay.
                isDebuggable = false
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
        }
    }

    splits {
        abi {
            isEnable = abiSplits
            reset()
            include(*splitAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // LiteRT (on-device Laya) runs on 64-bit phones only (LayaModelFiles.SUPPORTED_ABIS; its x86 build doesn't
            // exist anyway): keep its ~6 MB of armeabi-v7a libraries out of the APK.
            excludes += listOf("lib/armeabi-v7a/libLiteRt.so", "lib/armeabi-v7a/libLiteRtClGlAccelerator.so",
                "lib/armeabi-v7a/liblitert_jni.so")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // LiteRT 2.3.x's Kotlin API (litert-api) is compiled with Kotlin metadata 2.4; this project is on 2.1.
        // Same flag as Google's litert-samples (zero_shot_classification); only that small API surface is affected.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}

// Every APK gets versionCode = defaultConfig.versionCode * 10 + ABI digit (universal or unsplit = 0), so the
// per-ABI APKs of one release never share a versionCode and a newer release always updates an older one.
// (AGP's public variant API can't rename outputs: Gradle writes app-<abi>-release.apk and scripts/release.sh
// copies them to dist/ as voice-control-<versionName>-<abi|universal>-release.apk.)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
            val digit = abi?.let { splitAbis.indexOf(it) + 1 } ?: 0
            output.versionCode.set(android.defaultConfig.versionCode!! * 10 + digit)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser) // Custom Tabs for "Sign in with ChatGPT"
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.stream.webrtc.android)
    implementation(libs.litert)     // on-device Laya (agent/laya/LayaEngine): CompiledModel, CPU
    implementation(libs.litert.gpu) // + its GPU accelerator (OpenCL), optional at runtime

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json) // android.jar's org.json is stubs-only on the JVM
}

tasks.withType<Test>().configureEach {
    // LayaTokenizerTest loads the 34 MB tokenizer.json (256k vocab, 580k merges).
    maxHeapSize = "2g"
    // The live JEV smoke test (JevLiveTest) runs only when JEV_KEY is set, e.g. after `source scripts/env.sh`.
    System.getenv("JEV_KEY")?.let { environment("JEV_KEY", it) }
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
