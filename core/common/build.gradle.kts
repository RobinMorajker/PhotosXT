plugins {
    alias(libs.plugins.photoxt.android.library)
}

android {
    namespace = "com.photoxt.core.common"
    buildFeatures.buildConfig = true
}

dependencies {
    testImplementation(libs.junit)
}
