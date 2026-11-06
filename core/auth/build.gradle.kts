// Google accounts (spec §5.2): Credential Manager sign-in, AuthorizationClient scopes and the
// token provider for Drive. Tokens stay in Play services and memory (spec §5.7).
plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.hilt)
}

android {
    namespace = "com.photoxt.core.auth"
    buildFeatures.buildConfig = true
    defaultConfig {
        // The Web OAuth client id Credential Manager asks Google for ID tokens with. It stays out
        // of git like the Maps key: set PHOTOXT_GOOGLE_WEB_CLIENT_ID in ~/.gradle/gradle.properties
        // or the environment. Without it, adding a Google account says sign-in is not set up.
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"" + providers.gradleProperty("PHOTOXT_GOOGLE_WEB_CLIENT_ID")
                .orElse(providers.environmentVariable("PHOTOXT_GOOGLE_WEB_CLIENT_ID"))
                .getOrElse("") + "\""
        )
    }
}

dependencies {
    api(project(":core:database"))
    implementation(project(":core:common"))
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    api(libs.play.services.auth)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
