import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.audiotranscriber"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.audiotranscriber"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.4-word-context"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources { noCompress += listOf("onnx", "model", "json", "gguf") }
    defaultConfig { ndk { abiFilters += "arm64-v8a" } }
    // The static sherpa release contains a legacy shared ORT only for x86.
    // This phone build packages arm64 exclusively; never pick an arbitrary ORT.
    packaging { jniLibs { excludes += listOf("**/x86/**", "**/x86_64/**", "**/armeabi-v7a/**") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("com.github.wendykierp:JTransforms:3.1")
    implementation("com.k2fsa.sherpa:sherpa-onnx-static-link-onnxruntime:1.13.8@aar")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}


// ECAPA-TDNN includes SpeechBrain's feature extraction and accepts 16 kHz PCM.
// Download on the build machine only, not on the Android device.
val ecapaAssets = layout.buildDirectory.dir("generated/ecapaAssets")
val prepareEcapaAssets by tasks.registering {
    outputs.dir(ecapaAssets)
    doLast {
        val target = ecapaAssets.get().asFile.resolve("ecapa/ecapa-any-length.onnx")
        if (target.isFile && target.length() > 50L * 1024 * 1024) return@doLast
        target.parentFile.mkdirs()
        val temporary = File(target.parentFile, "ecapa-downloading.tmp")
        try {
            val url = URI("https://huggingface.co/kenrunio/transcriber-speaker-onnx/resolve/main/ecapa-any-length.onnx").toURL()
            val connection = url.openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 180_000
            }
            connection.getInputStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.length() > 50L * 1024 * 1024) { "Downloaded ECAPA model is incomplete" }
            check(temporary.renameTo(target)) { "Unable to move downloaded ECAPA model" }
        } finally { temporary.delete() }
    }
}
android.sourceSets.getByName("main").assets.srcDir(ecapaAssets)
tasks.named("preBuild") { dependsOn(prepareEcapaAssets) }

// On-device Qwen3 0.6B GGUF: downloaded by Gradle, SHA-256 pinned.
// Adds ~397MB to the APK; model is never fetched by the phone.
val qwenAssets = layout.buildDirectory.dir("generated/qwenAssets")
val prepareQwenAssets by tasks.registering {
    outputs.dir(qwenAssets)
    doLast {
        val target = qwenAssets.get().asFile.resolve("llm/qwen3-0.6b-q4km.gguf")
        val expectedSha = "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a"
        fun verify(file: File): Boolean {
            if (!file.isFile || file.length() < 300L * 1024 * 1024) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") {
                "%02x".format(it.toInt() and 255)
            } == expectedSha
        }
        if (verify(target)) return@doLast
        target.parentFile.mkdirs()
        val temporary = File(target.parentFile, "qwen-download.tmp")
        try {
            val url = URI("https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf").toURL()
            val connection = url.openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 180_000
            }
            connection.getInputStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(verify(temporary)) { "Qwen GGUF download failed SHA-256 verification" }
            check(temporary.renameTo(target)) { "Could not put Qwen model in build assets" }
        } finally { temporary.delete() }
    }
}
android.sourceSets.getByName("main").assets.srcDir(qwenAssets)
tasks.named("preBuild") { dependsOn(prepareQwenAssets) }
