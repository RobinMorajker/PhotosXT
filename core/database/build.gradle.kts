plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.hilt)
    alias(libs.plugins.room)
    // Settings sync (spec §4.8) writes the user's own tables as JSON.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.photoxt.core.database"
}

// Exported schemas are checked in, so every later schema change can ship a migration test.
room {
    schemaDirectory("$projectDir/schemas")
}

// MigrationTestHelper reads the exported schemas as test assets.
android.sourceSets.getByName("test").assets.directories.add("$projectDir/schemas")

dependencies {
    api(project(":core:model"))
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
