package com.neststudio.pocketshell

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device check against a paired PC: opens the session named by the instrumentation argument `session` (from the
 * first PC in the list), pinches the terminal in and out with real two-finger gestures (a gallery zoom: the grid
 * should stay the same) and logs the grid the status reports after each ("PinchFontTest" tag). Skipped without the argument or when the session is not listed.
 */
@RunWith(AndroidJUnit4::class)
class PinchFontTest {
    @Test
    fun pinchRefitsTheGrid() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val name = InstrumentationRegistry.getArguments().getString("session")
        assumeTrue("pass -e session <title>", !name.isNullOrBlank())
        val device = UiDevice.getInstance(inst)
        val ctx = inst.targetContext
        ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(PKG)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        device.wait(Until.findObject(By.res(PKG, "name")), 10_000)?.click()
        // A session card's title (not the PC list's "PocketShell" heading).
        val row = device.wait(Until.findObject(By.res(PKG, "title").text(name!!)), 15_000)
        assumeTrue("session $name not listed", row != null)
        row.click()
        val terminal = device.wait(Until.findObject(By.res(PKG, "terminal")), 10_000)
        assumeTrue("terminal not shown", terminal != null)
        Thread.sleep(3000)
        device.pressBack().also { Thread.sleep(500) } // hide the keyboard if it came up
        val status = { device.findObject(By.res(PKG, "connDot"))?.contentDescription }
        val shots = InstrumentationRegistry.getArguments().getString("shots") // directory on the device, optional
        fun shot(n: String) { if (shots != null) device.takeScreenshot(java.io.File(shots, "$n.png")) }
        Log.i(TAG, "before: ${status()}")
        shot("pinch0")
        val pinchesIn = InstrumentationRegistry.getArguments().getString("in")?.toIntOrNull() ?: 1
        repeat(pinchesIn) {
            device.findObject(By.res(PKG, "terminal")).pinchClose(0.4f, 1200)
            Thread.sleep(2000)
        }
        Log.i(TAG, "after pinch in: ${status()}")
        shot("pinch1")
        device.findObject(By.res(PKG, "terminal")).pinchOpen(0.5f, 1200)
        Thread.sleep(2000)
        Log.i(TAG, "after pinch out: ${status()}")
        shot("pinch2")
    }

    private companion object {
        const val TAG = "PinchFontTest"
        const val PKG = "com.neststudio.pocketshell"
    }
}
