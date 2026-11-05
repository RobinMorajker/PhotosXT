// Plain Kotlin domain types shared by data and UI modules. No Android APIs.
plugins {
    alias(libs.plugins.photoxt.android.library)
}

android {
    namespace = "com.photoxt.core.model"
}

dependencies {
    testImplementation(libs.junit)
}
