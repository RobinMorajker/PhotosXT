plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.compose)
}

android {
    namespace = "com.photoxt.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material3)
}
