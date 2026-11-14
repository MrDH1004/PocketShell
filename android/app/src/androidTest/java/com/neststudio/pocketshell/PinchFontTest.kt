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
 * first PC in the list), pinches the terminal in and out with real two-finger gestures and logs the grid the
 * status reports after each ("PinchFontTest" tag). Skipped without the argument or when the session is not listed.
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
        val row = device.wait(Until.findObject(By.text(name!!)), 10_000)
        assumeTrue("session $name not listed", row != null)
        row.click()
        val terminal = device.wait(Until.findObject(By.res(PKG, "terminal")), 10_000)
        assumeTrue("terminal not shown", terminal != null)
        Thread.sleep(3000)
        device.pressBack().also { Thread.sleep(500) } // hide the keyboard if it came up
        val status = { device.findObject(By.res(PKG, "connDot"))?.contentDescription }
        Log.i(TAG, "before: ${status()}")
        device.findObject(By.res(PKG, "terminal")).pinchClose(0.7f, 1200)
        Thread.sleep(2000)
        Log.i(TAG, "after pinch in: ${status()}")
        device.findObject(By.res(PKG, "terminal")).pinchOpen(0.9f, 1200)
        Thread.sleep(2000)
        Log.i(TAG, "after pinch out: ${status()}")
    }

    private companion object {
        const val TAG = "PinchFontTest"
        const val PKG = "com.neststudio.pocketshell"
    }
}
