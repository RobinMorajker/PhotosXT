import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.Lint
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** `photoxt.android.application`: the app module. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            configureAndroid(this)
            defaultConfig.targetSdk = sdk("targetSdk")
            lint { strict() }
        }
    }
}

/** `photoxt.android.library`: every core and feature module. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureAndroid(this)
            lint.targetSdk = sdk("targetSdk")
            lint { strict() }
            testOptions.targetSdk = sdk("targetSdk")
        }
    }
}

/** `photoxt.android.compose`: apply after the application or library plugin. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.withPlugin("com.android.base") {
            extensions.getByType<CommonExtension>().buildFeatures.compose = true
        }
        dependencies {
            val bom = platform(libs.findLibrary("androidx-compose-bom").get())
            "implementation"(bom)
            "testImplementation"(bom)
            "implementation"(libs.findLibrary("androidx-compose-ui-tooling-preview").get())
            "debugImplementation"(libs.findLibrary("androidx-compose-ui-tooling").get())
        }
    }
}

private fun Project.configureAndroid(android: CommonExtension) {
    android.apply {
        compileSdk = sdk("compileSdk")
        defaultConfig.minSdk = sdk("minSdk")
        compileOptions.sourceCompatibility = JavaVersion.VERSION_17
        compileOptions.targetCompatibility = JavaVersion.VERSION_17
        testOptions.unitTests.isIncludeAndroidResources = true
        // Robolectric's Android 17 sandbox reads FileDescriptor internals through this JDK package.
        testOptions.unitTests.all {
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions.allWarningsAsErrors.set(true)
    }
}

/** Any Lint warning fails the build (spec §7). */
private fun Lint.strict() {
    abortOnError = true
    warningsAsErrors = true
    // These compare against versions published after the build was written, so they would fail
    // an unchanged checkout later. Version bumps are a deliberate chore instead.
    disable +=
        setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
            "OldTargetApi"
        )
    // A library's own lint checks built for an older Lint: Lint skips them either way, and this
    // only reports that it did (seen with androidx.lifecycle 2.11).
    disable += "ObsoleteLintCustomCheck"
}

private val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

private fun Project.sdk(name: String): Int = libs.findVersion(name).get().requiredVersion.toInt()
