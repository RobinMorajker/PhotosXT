// The Library tab (spec §4.1): its list of places, Storage with the cache settings (spec §4.4),
// Trash and Transfers (spec §4.7), Bookmarks, History, Hidden and the vault (spec §4.8, §4.9),
// and Settings (spec §4.21). Later phases add their entries.
plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.compose)
    alias(libs.plugins.photoxt.android.hilt)
}

android {
    namespace = "com.photoxt.feature.library"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:organize"))
    implementation(project(":core:sync"))
    implementation(project(":core:transfer"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.coil.compose)

    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
