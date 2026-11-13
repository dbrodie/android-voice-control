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

android {
    namespace = "com.brainstormity.voicecontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.brainstormity.voicecontrol"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "JEV_KEY", envValue("JEV_KEY").asBuildConfigString())
        buildConfigField("String", "OPENAI_API_KEY", envValue("OPENAI_API_KEY").asBuildConfigString())
        buildConfigField(
            "String", "OPENAI_REALTIME_MODEL",
            envValue("OPENAI_REALTIME_MODEL", "gpt-realtime").asBuildConfigString()
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.stream.webrtc.android)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json) // android.jar's org.json is stubs-only on the JVM
}

tasks.withType<Test>().configureEach {
    // The live JEV smoke test (JevLiveTest) runs only when JEV_KEY is set, e.g. after `source scripts/env.sh`.
    System.getenv("JEV_KEY")?.let { environment("JEV_KEY", it) }
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
