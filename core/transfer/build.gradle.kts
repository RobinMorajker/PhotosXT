// File operations (spec §4.7, §5.2 core/transfer): the clipboard, the persistent transfer queue
// and its engine (copy, move, trash, restore, rename, convert across the phone and Drive), the
// unified Trash, batch tools, and the job that runs the queue.
plugins {
    alias(libs.plugins.photoxt.android.library)
    alias(libs.plugins.photoxt.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.photoxt.core.transfer"
}

dependencies {
    api(project(":core:sync"))
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.coil.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
}
