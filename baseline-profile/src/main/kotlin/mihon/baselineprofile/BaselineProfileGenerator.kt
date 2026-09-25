package mihon.baselineprofile

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.MacrobenchmarkScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val LAUNCH_TIMEOUT_MS = 10_000L
private const val IDLE_TIMEOUT_MS = 5_000L

@RequiresApi(Build.VERSION_CODES.P)
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = TARGET_PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        profileSetup()
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private fun MacrobenchmarkScope.profileSetup() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    // Wait for the app to be fully idle after cold launch
    device.waitForIdle(IDLE_TIMEOUT_MS)

    // Wait for the Home shell to appear — detected by a scrollable container
    // (does not rely on network content or any text that requires data to load)
    device.wait(Until.findObject(By.scrollable(true)), LAUNCH_TIMEOUT_MS)
    device.waitForIdle(IDLE_TIMEOUT_MS)

    // Scroll down on Home to exercise the Home feed rendering path
    device.scrollDown()
    device.waitForIdle(IDLE_TIMEOUT_MS)
    device.scrollDown()
    device.waitForIdle(IDLE_TIMEOUT_MS)

    // Navigate to Library tab — Arabic content description
    device.waitAndClick(By.desc("المكتبة"))
    device.waitForIdle(IDLE_TIMEOUT_MS)

    // Navigate to Browse (Explore) tab — Arabic content description
    device.waitAndClick(By.desc("الاستكشاف"))
    device.waitForIdle(IDLE_TIMEOUT_MS)

    // Navigate to More tab — Arabic content description
    device.waitAndClick(By.desc("المزيد"))
    device.waitForIdle(IDLE_TIMEOUT_MS)

    // Return to Home tab — Arabic content description
    device.waitAndClick(By.desc("الرئيسية"))
    device.waitForIdle(IDLE_TIMEOUT_MS)
}

/**
 * Waits up to [LAUNCH_TIMEOUT_MS] for a matching element and clicks it.
 * No-ops silently if the element never appears (keeps the profile run deterministic).
 */
private fun UiDevice.waitAndClick(by: BySelector) {
    val obj = wait(Until.findObject(by), LAUNCH_TIMEOUT_MS) ?: throw AssertionError("Required automation element not found: $by")
    obj.click()
}

/**
 * Performs a short downward swipe in the centre of the screen to scroll content.
 */
private fun UiDevice.scrollDown() {
    val startX = displayWidth / 2
    val startY = (displayHeight * 0.7).toInt()
    val endY = (displayHeight * 0.3).toInt()
    swipe(startX, startY, startX, endY, 20)
}
