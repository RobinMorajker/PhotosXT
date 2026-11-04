plugins {
    alias(libs.plugins.photoxt.android.application)
    alias(libs.plugins.photoxt.android.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.photoxt.app"
    defaultConfig {
        // Placeholder until open decision 1 (spec §9) is settled, before Phase 11.
        applicationId = "com.photoxt.app"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures.buildConfig = true
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    // The baselineprofile plugin adds these release-like types. They only ever run on a test
    // device, so the debug key is enough. Release stays unsigned until Play upload (Phase 15).
    buildTypes.matching {
        it.name in setOf("benchmarkRelease", "nonMinifiedRelease")
    }.configureEach {
        signingConfig = signingConfigs.getByName("debug")
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3.navigationSuite)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.serialization.core)
    ksp(libs.hilt.compiler)
    ksp(libs.kotlin.metadata)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
