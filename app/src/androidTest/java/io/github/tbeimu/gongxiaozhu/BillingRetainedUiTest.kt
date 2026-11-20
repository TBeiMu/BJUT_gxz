package io.github.tbeimu.gongxiaozhu

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.billing.BillingMetric
import io.github.tbeimu.gongxiaozhu.billing.BillingProblem
import io.github.tbeimu.gongxiaozhu.billing.BillingSnapshot
import io.github.tbeimu.gongxiaozhu.billing.BillingUiState
import io.github.tbeimu.gongxiaozhu.billing.OnlineDevice
import io.github.tbeimu.gongxiaozhu.data.AccountState
import io.github.tbeimu.gongxiaozhu.data.AccountSummary
import io.github.tbeimu.gongxiaozhu.ui.AppBackdrop
import io.github.tbeimu.gongxiaozhu.ui.AppUiState
import io.github.tbeimu.gongxiaozhu.ui.BillingScreen
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import io.github.tbeimu.gongxiaozhu.ui.WallpaperState
import java.text.DateFormat
import java.util.Date
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Retained values are fictional summaries; these checks open no account store or school session. */
class BillingRetainedUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = AccountSummary("fictional-retained-account", "测试校园网", "fictional-retained-student")
    private val data = AccountState(listOf(account), account.id)
    private val loadedAt = 1_795_147_200_000L
    private val metrics = linkedMapOf(
        "已用流量" to BillingMetric("1234", "MB"),
        "可用流量" to BillingMetric("5678", "MB"),
        "消费保护" to BillingMetric("未设置", ""),
        "账户余额" to BillingMetric("12.3456", "元"))
    private val device = OnlineDevice("fictional-retained-session", "192.0.2.10", "", "00:11:22:33:44:55",
        "2026-11-20 08:00", 42, BillingMetric("1.500", "MB"))
    private val snapshot = BillingSnapshot(metrics, listOf(device))
    private fun timestamp(value: Long = loadedAt) = "更新于 " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
    private fun app(billing: BillingUiState) = AppUiState(data = data, loading = false, billing = billing)

    @Test fun retainedValuesAndFailureStatusKeepTheLightSmallScreenLayoutStill() = retainedLayout(dark = false)
    @Test fun retainedValuesAndFailureStatusKeepTheDarkSmallScreenLayoutStill() = retainedLayout(dark = true)

    private fun retainedLayout(dark: Boolean) {
        val billing = mutableStateOf(BillingUiState(snapshot = snapshot, loadedAt = loadedAt, message = "查询完成"))
        val fontScale = mutableStateOf(1f)
        var expectedGap = 0f
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) { GongXiaoZhuTheme {
                AppBackdrop(WallpaperState()) {
                    val density = LocalDensity.current
                    expectedGap = with(density) { 4.dp.toPx() }
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value),
                        LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                        Box(Modifier.width(360.dp).height(680.dp)) {
                            BillingScreen(app(billing.value), Modifier, {}, {}, {})
                        }
                    }
                }
            } }
        }
        val fixed = listOf("metric_已用流量", "metric_可用流量", "metric_消费保护", "metric_账户余额",
            "billing_login", "billing_accounts", "billing_offline")
        fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().let { it.positionInRoot to it.size }
        fun assertValuesAndTime() {
            listOf("1234", "5678", "未设置", "12.3456", "1.500 MB", device.ip, device.loginTime, timestamp()).forEach {
                compose.onNodeWithText(it, substring = false).assertExists()
            }
            compose.onNodeWithTag("online_device_0").assertExists()
        }
        for (scale in listOf(1f, 1.8f)) {
            compose.runOnIdle {
                fontScale.value = scale
                billing.value = BillingUiState(snapshot = snapshot, loadedAt = loadedAt, message = "查询完成")
            }
            val original = fixed.associateWith(::bounds)
            compose.runOnIdle { billing.value = billing.value.copy(running = true, message = "正在查询", devicesActionable = false) }
            assertValuesAndTime()
            fixed.forEach { assertEquals("Refreshing retained data must not move $it at font $scale", original[it], bounds(it)) }
            compose.onNodeWithTag("billing_login").assertIsNotEnabled()
            val loader = compose.onNodeWithTag("billing_status_loader").fetchSemanticsNode().boundsInRoot
            val text = compose.onNodeWithTag("billing_status").fetchSemanticsNode().boundsInRoot
            assertEquals(expectedGap, text.left - loader.right, 1f)
            compose.runOnIdle { billing.value = billing.value.copy(running = false, queryFailed = true, message = BillingProblem.CONNECTION.hint) }
            compose.onNodeWithTag("billing_status").assertTextEquals("查询失败").assertHasNoClickAction()
            assertValuesAndTime()
            fixed.forEach { assertEquals("A failed refresh must not move $it at font $scale", original[it], bounds(it)) }
            val statusLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("billing_status").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
                assertTrue(get(statusLayouts))
            }
            val layout = statusLayouts.single()
            // Content-sized Text may retain a wider cached paragraph while every painted line fits.
            // Check the actual glyph-line extents, matching the existing BillingUiTest contract.
            val invalidPaintedLine = (0 until layout.lineCount).any { line ->
                layout.isLineEllipsized(line) || layout.getLineLeft(line) < -1f ||
                    layout.getLineRight(line) > layout.size.width + 1f || layout.getLineTop(line) < -1f ||
                    layout.getLineBottom(line) > layout.size.height + 1f
            }
            val actualOverflow = layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines || invalidPaintedLine
            if (actualOverflow) {
                // Failure evidence is always safe here: the component contains only fictional summaries.
                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
                    ?.resolve("billing-retained-overflow-" + (if (dark) "dark" else "light") + "-$scale.png")?.outputStream()?.use {
                        compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
            }
            val paintedLines = (0 until layout.lineCount).joinToString { line ->
                "$line:[" + layout.getLineLeft(line) + "," + layout.getLineRight(line) + "," +
                    layout.getLineBottom(line) + "],ellipsis=" + layout.isLineEllipsized(line)
            }
            assertFalse("Status overflow: dark=$dark, scale=$scale, text=" + layout.layoutInput.text +
                ", font=" + layout.layoutInput.style.fontSize + ", lineHeight=" + layout.layoutInput.style.lineHeight +
                ", size=" + layout.size + ", paragraphWidth=" + layout.multiParagraph.width +
                ", lines=" + layout.lineCount + ", widthOverflow=" + layout.didOverflowWidth +
                ", heightOverflow=" + layout.didOverflowHeight + ", maxLinesOverflow=" + layout.multiParagraph.didExceedMaxLines +
                ", constraints=" + layout.layoutInput.constraints + ", painted=" + paintedLines, actualOverflow)
            compose.onNodeWithTag("billing_login").assertIsEnabled()
            compose.onNodeWithTag("billing_offline").assertIsNotEnabled()
            if (InstrumentationRegistry.getArguments().getString("gxzSavePreview") == "true") {
                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
                    ?.resolve("billing-retained-" + (if (dark) "dark" else "light") + "-$scale.png")?.outputStream()?.use {
                        compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
            }
            compose.runOnIdle { billing.value = billing.value.copy(queryFailed = false, message = "查询完成", loadedAt = loadedAt + 60_000,
                snapshotFromCache = false, devicesActionable = true) }
            compose.onNodeWithTag("billing_status").assertTextEquals("查询完成")
            compose.onNodeWithText(timestamp(loadedAt + 60_000), substring = false).assertExists()
            fixed.forEach { assertEquals("A successful refresh must not move $it at font $scale", original[it], bounds(it)) }
        }
    }

    @Test fun restoredHistoryKeepsItsValuesDevicesAndTimestampButRequiresRefreshBeforeLogout() {
        val billing = mutableStateOf(BillingUiState(snapshot = snapshot, loadedAt = loadedAt,
            snapshotFromCache = true, devicesActionable = false))
        val requests = mutableListOf<String?>(); val selected = mutableListOf<String>()
        compose.setContent { GongXiaoZhuTheme {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                BillingScreen(app(billing.value), Modifier, {}, requests::add, selected::add)
            }
        } }
        compose.onNodeWithText("1234", substring = false).assertExists()
        compose.onNodeWithText("12.3456", substring = false).assertExists()
        compose.onNodeWithTag("billing_offline").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithText("选择要注销的在线设备").assertDoesNotExist()
        compose.onNodeWithText(timestamp(), substring = false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("device_traffic_0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1.500 MB", substring = false).assertExists()
        compose.onNodeWithTag("offline_device_0").performScrollTo().assertIsNotEnabled().performClick()
        assertTrue(selected.isEmpty())
        compose.onNodeWithTag("billing_login").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf<String?>(null), requests)
        compose.onNodeWithText("刷新资费", substring = false).assertExists()
        compose.runOnIdle { billing.value = billing.value.copy(snapshotFromCache = false, devicesActionable = true) }
        compose.onNodeWithTag("offline_device_0").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(device.sessionId), selected)
    }

    @Test fun losingTheLiveSessionClosesAnOpenDeviceChooserAndDisablesEveryLogoutEntry() {
        val other = device.copy(sessionId = "fictional-other-session", ip = "192.0.2.11")
        val billing = mutableStateOf(BillingUiState(snapshot = snapshot.copy(devices = listOf(device, other)), loadedAt = loadedAt))
        val selected = mutableListOf<String>(); val visible = mutableListOf<Boolean>()
        compose.setContent { GongXiaoZhuTheme {
            BillingScreen(app(billing.value), Modifier, {}, {}, selected::add, visible::add)
        } }
        compose.onNodeWithTag("billing_offline").performScrollTo().performClick()
        compose.onNodeWithTag("choose_offline_0").assertIsEnabled()
        compose.onNodeWithTag("choose_offline_1").assertIsEnabled()
        assertTrue(visible.last())
        compose.runOnIdle { billing.value = billing.value.copy(devicesActionable = false, queryFailed = true, message = BillingProblem.RESPONSE.hint) }
        compose.onNodeWithText("选择要注销的在线设备").assertDoesNotExist()
        assertFalse(visible.last())
        compose.onNodeWithTag("billing_offline").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithTag("offline_device_0").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithTag("offline_device_1").performScrollTo().assertIsNotEnabled().performClick()
        assertTrue(selected.isEmpty())
    }

    @Test fun requiredUserActionHintsRemainSpecificWhenHistoryIsRetained() {
        val billing = mutableStateOf(BillingUiState(snapshot = snapshot, loadedAt = loadedAt, devicesActionable = false))
        compose.setContent { GongXiaoZhuTheme { BillingScreen(app(billing.value), Modifier, {}, {}, {}) } }
        for (problem in listOf(BillingProblem.CAPTCHA, BillingProblem.CAPTCHA_REJECTED, BillingProblem.UNCONFIRMED, BillingProblem.EXPIRED, BillingProblem.ACCOUNT_MISMATCH)) {
            compose.runOnIdle { billing.value = billing.value.copy(message = problem.hint, queryFailed = false,
                captcha = if (problem == BillingProblem.CAPTCHA || problem == BillingProblem.CAPTCHA_REJECTED) byteArrayOf(1) else null) }
            compose.onNodeWithTag("billing_status").assertTextEquals(problem.hint)
            compose.onNodeWithText("查询失败", substring = false).assertDoesNotExist()
            compose.onNodeWithText(timestamp(), substring = false).assertExists()
        }
    }
}
