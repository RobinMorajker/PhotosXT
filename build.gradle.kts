plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.hilt) apply false
    // Also puts this Kotlin version on the classpath for AGP's built-in Kotlin.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
}

// Formatting and static analysis run once, from the root, over every module and build-logic.
spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    val editorConfig = rootProject.file(".editorconfig")
    kotlin {
        target("**/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("config/detekt/detekt.yml")
    source.setFrom(
        fileTree(rootDir) {
            include("**/src/**/*.kt")
            exclude("**/build/**", "**/.gradle/**")
        }
    )
}

// detekt 1.23 runs on its own Kotlin compiler: stop Gradle upgrading it to the project's Kotlin.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
        }
    }
}
