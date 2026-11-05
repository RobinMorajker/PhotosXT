package com.photoxt.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

const val TARGET_PACKAGE = "com.photoxt.app"

/** Test tag of the Pictures grid (core/ui MEDIA_GRID_TEST_TAG), exposed as a resource id. */
private const val MEDIA_GRID = "media_grid"
private const val WAIT_MS = 10_000L
private const val EDGE_MARGIN_FRACTION = 5

/** Lets PhotoXT read the phone's photos without the permission dialog. */
fun MacrobenchmarkScope.grantMediaAccess() {
    listOf("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO").forEach {
        device.executeShellCommand("pm grant $TARGET_PACKAGE android.permission.$it")
    }
}

/** Waits for the Pictures grid, then flings it down and back up [times] times. */
fun MacrobenchmarkScope.flingPictures(times: Int) {
    check(device.wait(Until.hasObject(By.res(MEDIA_GRID)), WAIT_MS)) { "Pictures grid not shown" }
    val grid = device.findObject(By.res(MEDIA_GRID))
    // Keep flings clear of the system's edge gestures.
    grid.setGestureMargin(device.displayWidth / EDGE_MARGIN_FRACTION)
    repeat(times) { grid.fling(Direction.DOWN) }
    repeat(times) { grid.fling(Direction.UP) }
}
