// On-device ML for the editor (spec §4.10, §5.2 core/ml): ML Kit faces, text, plates and subject
// segmentation, and the object eraser's inpainting models on LiteRT, all through Play services.
plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.hilt)
}

android {
    namespace = "com.photoxt.core.ml"
    // The eraser models' check runs on a phone, in Play services' LiteRT (EraserModelsTest).
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litert.play)
    implementation(libs.litert.play.gpu)
    implementation(libs.mlkit.face)
    implementation(libs.mlkit.subject)
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.text.devanagari)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
