
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
        versionCode = 6
        versionName = "1.6-playback-stream"

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
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.k2fsa.sherpa:sherpa-onnx-static-link-onnxruntime:2.0.1@aar")
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

