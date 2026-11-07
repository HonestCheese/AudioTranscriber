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
        versionCode = 3
        versionName = "1.2-stage01-reverb"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources { noCompress += listOf("onnx", "model", "json") }
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


// Package the exact Stage 01 FP32 Reverb model. Download runs on the build
// machine only, never on the phone. The pinned SHA prevents silent model changes.
val reverbAssets = layout.buildDirectory.dir("generated/reverbAssets")
val prepareReverbAssets by tasks.registering {
    outputs.dir(reverbAssets)
    doLast {
        val archive = temporaryDir.resolve("reverb.tar.bz2")
        val url = URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-reverb-diarization-v1.tar.bz2").toURL()
        val connection = url.openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 120_000
        }
        connection.getInputStream().use { input ->
            archive.outputStream().use { output -> input.copyTo(output) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(archive.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(digest == "615761e980be1688da0ef81618c056134d63aa55ea0a5f1494c47393b9398eab") {
            "Reverb archive SHA-256 mismatch"
        }
        val extracted = temporaryDir.resolve("extracted").apply { mkdirs() }
        ant.withGroovyBuilder {
            "untar"("src" to archive, "dest" to extracted, "compression" to "bzip2")
        }
        val source = extracted.resolve("sherpa-onnx-reverb-diarization-v1")
        val destination = reverbAssets.get().asFile.resolve("reverb").apply { mkdirs() }
        source.resolve("model.onnx").copyTo(destination.resolve("model.onnx"), overwrite = true)
        source.resolve("LICENSE").copyTo(destination.resolve("LICENSE"), overwrite = true)
    }
}
android.sourceSets.getByName("main").assets.srcDir(reverbAssets)
tasks.named("preBuild") { dependsOn(prepareReverbAssets) }
