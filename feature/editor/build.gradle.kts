// The image editor (spec §4.10): tools over a non-destructive recipe, a live GPU preview from
// core/render, ML from core/ml, and saving through the transfer queue (core/transfer).
plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.compose)
    alias(libs.plugins.photoxt.android.hilt)
}

android {
    namespace = "com.photoxt.feature.editor"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
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
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
