// Settings (spec §4.21, §5.2): one typed DataStore file, written as JSON by kotlinx.serialization
// instead of protobuf, so the build needs no protoc plugin. Settings sync (Phase 6) reads it too.
plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.photoxt.core.datastore"
}

dependencies {
    api(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
