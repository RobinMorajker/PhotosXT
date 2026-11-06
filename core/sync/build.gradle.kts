// Drive sync (spec §5.2, §5.5): the initial scan of tracked folders and the thumbnail fill.
// The Changes API sync and its workers arrive in Phase 4.
plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.hilt)
}

android {
    namespace = "com.photoxt.core.sync"
}

dependencies {
    api(project(":core:media"))
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
}
