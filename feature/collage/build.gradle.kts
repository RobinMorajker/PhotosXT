// The collage studio (spec §4.11): layouts, canvas and cells over a collage project drawn by
// core/render, drafts in core/database, and the export through the transfer queue (core/transfer).
plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.compose)
    alias(libs.plugins.photoxt.android.hilt)
}

android {
    namespace = "com.photoxt.feature.collage"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:media"))
    implementation(project(":core:ml"))
    implementation(project(":core:render"))
    implementation(project(":core:transfer"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.paging.compose)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
