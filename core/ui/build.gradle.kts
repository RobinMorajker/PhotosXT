plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.compose)
}

android {
    namespace = "com.photoxt.core.ui"
}

dependencies {
    implementation(libs.androidx.compose.material3)
}
