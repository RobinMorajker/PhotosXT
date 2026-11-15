// The vault (spec §4.8, §4.19): items encrypted on the phone with Tink, its key sealed by an
// Android Keystore key, readable only through PhotoXT after unlocking.
plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.photoxt.core.vault"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.androidx.media3.datasource)
    implementation(libs.coil.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tink.android)

    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
