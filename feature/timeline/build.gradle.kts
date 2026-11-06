// Pictures (every photo and video) and the grid of one album: both are timelines.
plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.compose)
    alias(libs.plugins.photoxt.android.hilt)
}

android {
    namespace = "com.photoxt.feature.timeline"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:media"))
    implementation(project(":core:sync"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
