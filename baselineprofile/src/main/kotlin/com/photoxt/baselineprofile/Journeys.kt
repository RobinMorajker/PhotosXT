package com.photoxt.baselineprofile

import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
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

/** Opens the newest photo of [album] (from the Albums tab), or of Pictures, in the viewer. */
fun MacrobenchmarkScope.openNewestPhoto(album: String? = null) {
    if (album != null) {
        check(device.wait(Until.hasObject(By.text("Albums")), WAIT_MS)) { "Tabs not shown" }
        // The tab, not a header that may show the same word.
        device.findObjects(By.text("Albums")).maxBy { it.visibleBounds.top }.click()
        // An album tile reads "<name>, <count> items".
        UiScrollable(UiSelector().scrollable(true))
            .scrollIntoView(UiSelector().descriptionStartsWith("$album,"))
        device.findObject(By.descStartsWith("$album,")).click()
    }
    check(device.wait(Until.hasObject(By.descStartsWith("Photo")), WAIT_MS)) { "No photo" }
    device.findObjects(By.descStartsWith("Photo"))
        .minWith(compareBy({ it.visibleBounds.top }, { it.visibleBounds.left }))
        .click()
    check(device.wait(Until.hasObject(By.desc("Zoom lock")), WAIT_MS)) { "Viewer not shown" }
    SystemClock.sleep(SETTLE_MS)
}

/**
 * Double-taps the photo in the viewer to Fill and then to 1:1 pixels, pans across it both ways,
 * double-taps back to the start and closes the viewer.
 */
fun MacrobenchmarkScope.zoomToPixelsAndPan() {
    val x = device.displayWidth / 2
    val y = device.displayHeight / 2
    repeat(2) {
        doubleTap(x, y)
        SystemClock.sleep(SETTLE_MS)
    }
    val dx = device.displayWidth / PAN_FRACTION
    val dy = device.displayHeight / PAN_FRACTION
    device.swipe(x + dx, y, x - dx, y, PAN_STEPS)
    device.swipe(x - dx, y, x + dx, y, PAN_STEPS)
    device.swipe(x, y + dy, x, y - dy, PAN_STEPS)
    SystemClock.sleep(SETTLE_MS)
    doubleTap(x, y)
    SystemClock.sleep(SETTLE_MS)
    device.pressBack()
}

/** A finger's double tap: two short taps [TAP_GAP_MS] apart, which a click pair is not. */
private fun doubleTap(x: Int, y: Int) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    repeat(2) {
        val down = SystemClock.uptimeMillis()
        automation.touch(down, down, MotionEvent.ACTION_DOWN, x, y)
        SystemClock.sleep(TAP_MS)
        automation.touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
        SystemClock.sleep(TAP_GAP_MS)
    }
}

private fun UiAutomation.touch(down: Long, at: Long, action: Int, x: Int, y: Int) {
    val event = MotionEvent.obtain(down, at, action, x.toFloat(), y.toFloat(), 0)
    event.source = InputDevice.SOURCE_TOUCHSCREEN
    injectInputEvent(event, true)
    event.recycle()
}

private const val SETTLE_MS = 1_500L
private const val PAN_STEPS = 40
private const val PAN_FRACTION = 4
private const val TAP_MS = 50L
private const val TAP_GAP_MS = 120L
