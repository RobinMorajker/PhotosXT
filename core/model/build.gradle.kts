// Plain Kotlin domain types shared by data and UI modules. No Android APIs.
plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.photoxt.core.model"
}

dependencies {
    // Sorts and smart-album rules are stored as JSON (settings, album rows, settings sync).
    api(libs.kotlinx.serialization.core)

    testImplementation(libs.junit)
}
