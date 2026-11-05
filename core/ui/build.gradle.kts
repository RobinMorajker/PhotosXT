plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.compose)
}

android {
    namespace = "com.photoxt.core.ui"
}

dependencies {
    api(project(":core:model"))
    api(libs.androidx.paging.compose)
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
