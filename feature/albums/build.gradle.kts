// The Albums tab: one tile per album. Opening an album shows feature/timeline's album grid.
plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.compose)
    alias(libs.plugins.photoxt.android.hilt)
}

android {
    namespace = "com.photoxt.feature.albums"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:media"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
