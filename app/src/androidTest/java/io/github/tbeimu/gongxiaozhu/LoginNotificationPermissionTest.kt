package io.github.tbeimu.gongxiaozhu

import android.os.Build
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import io.github.tbeimu.gongxiaozhu.ui.LoginNotificationPermission
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Revoke notification permission before instrumentation; use user-fixed to exercise permanent denial. */
@RunWith(AndroidJUnit4::class)
class LoginNotificationPermissionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun settingsRecoveryWorksAfterDenialAndRefreshesUiWhenReturning() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        assumeFalse(NotificationManagerCompat.from(context).areNotificationsEnabled())
        val interactions = AtomicInteger()
        val launches = AtomicInteger()
        val observedContext = object : ContextWrapper(compose.activity) {
            override fun startActivity(intent: Intent) {
                assertEquals("The interaction must retire restoration before opening system settings", 1, interactions.get())
                launches.incrementAndGet()
                super.startActivity(intent)
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides observedContext) {
                GongXiaoZhuTheme { Column { LoginNotificationPermission(onInteraction = { interactions.incrementAndGet() }) } }
            }
        }
        // Permanent denial must not remove the alternate route to settings.
        compose.onNodeWithText("允许通知以查看后台认证进度（可选）").assertExists()
        compose.onNodeWithText("打开系统通知设置").performClick()
        try {
            waitFor {
                shell("dumpsys activity activities").lineSequence().any {
                    (it.contains("mResumedActivity") || it.contains("topResumedActivity")) && it.contains("com.android.settings")
                }
            }
            shell("pm clear-permission-flags ${context.packageName} android.permission.POST_NOTIFICATIONS user-set user-fixed")
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        } finally { shell("input keyevent 4") }
        waitFor { compose.activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(1, interactions.get())
        assertEquals(1, launches.get())
        assertTrue(NotificationManagerCompat.from(context).areNotificationsEnabled())
        compose.onNodeWithText("打开系统通知设置").assertDoesNotExist()
        compose.onNodeWithText("允许通知以查看后台认证进度（可选）").assertDoesNotExist()
    }

    @Test fun requestingNotificationPermissionReportsInteractionBeforeThePermissionLauncher() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        assumeFalse(NotificationManagerCompat.from(context).areNotificationsEnabled())
        val events = mutableListOf<String>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                assertTrue(contract is ActivityResultContracts.RequestPermission)
                assertEquals(android.Manifest.permission.POST_NOTIFICATIONS, input)
                assertEquals(listOf("interaction"), events)
                events += "permission-launch"
                dispatchResult(requestCode, false) // A virtual denial keeps the real system permission untouched.
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                GongXiaoZhuTheme { Column { LoginNotificationPermission(onInteraction = { events += "interaction" }) } }
            }
        }
        val requestBounds = compose.onNodeWithText("允许通知以查看后台认证进度（可选）").fetchSemanticsNode().boundsInRoot
        val settingsBounds = compose.onNodeWithText("打开系统通知设置").fetchSemanticsNode().boundsInRoot
        assertTrue("Permission and settings buttons must have separate touch targets as in the real panel", requestBounds.bottom <= settingsBounds.top)
        compose.onNodeWithText("允许通知以查看后台认证进度（可选）").performClick()
        compose.runOnIdle { assertEquals(listOf("interaction", "permission-launch"), events) }
        assertFalse(NotificationManagerCompat.from(context).areNotificationsEnabled())
        compose.onNodeWithText("打开系统通知设置").assertExists()
    }

    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8) }
    private fun waitFor(predicate: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000
        while (!predicate() && System.nanoTime() < end) Thread.sleep(50)
        assertTrue("等待通知设置状态超时", predicate())
    }
}
