// The editor's GPU pipeline (spec §4.10, §5.2 core/render): the edit recipe rendered through one
// GLES 3 shader for the live preview and, in tiles, for the full-size export and its encoders.
plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.photoxt.core.render"
    // The 50 MP export-against-preview check runs on a phone (spec Phase 7, Done when).
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.heifwriter)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
