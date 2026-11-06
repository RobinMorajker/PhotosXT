plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.hilt)
}

android {
    namespace = "com.photoxt.core.media"
}

dependencies {
    api(project(":core:database"))
    api(project(":core:drive"))
    implementation(project(":core:common"))
    implementation(libs.androidx.media3.database)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.coil.core)

    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
}
