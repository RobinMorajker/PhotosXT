// Drive REST v3 as a thin hand-written client (spec §5.1): files, changes and shared drives over
// OkHttp and Retrofit, signed per account by core/auth's tokens.
plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.photoxt.core.drive"
}

dependencies {
    api(project(":core:auth"))
    implementation(project(":core:common"))
    api(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
