package io.github.tbeimu.gongxiaozhu

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.IntentFilter
import android.os.Handler
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.ui.*
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ShuttleTimetableUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val clock = AtomicReference(LocalDateTime.of(2026, 11, 27, 7, 21))
    private val reads = AtomicInteger()
    private lateinit var clockContext: ClockTestContext
    private val appearance = androidx.compose.runtime.mutableStateOf(WallpaperPreferences())

    private fun show(fontScale: Float = 1f, restoration: StateRestorationTester? = null,
        width: Int = 320, height: Int = 540, wallpaper: Boolean = false) {
        clockContext = ClockTestContext(compose.activity)
        val content: @Composable () -> Unit = {
            GongXiaoZhuTheme(appearance.value) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalContext provides clockContext) {
                    val background = if (wallpaper) androidx.compose.ui.graphics.Brush.verticalGradient(
                        if (gxz.dark) listOf(androidx.compose.ui.graphics.Color(0xFF172D3C), androidx.compose.ui.graphics.Color(0xFF344238))
                        else listOf(androidx.compose.ui.graphics.Color(0xFFD3EAF4), androidx.compose.ui.graphics.Color(0xFFE4ECCD)))
                        else androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.background)
                    Box(Modifier.width(width.dp).height(height.dp).background(background)
                        .testTag("shuttle_test_host")) {
                        ShuttleTimetablePage(onBack = {}, nowProvider = { reads.incrementAndGet(); clock.get() })
                    }
                }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.waitForIdle()
    }

    private fun broadcast(action: String) {
        compose.runOnUiThread { clockContext.dispatch(action) }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getExternalFilesDir(null)!!.resolve("shuttle-$name.png").outputStream().use {
            compose.onNodeWithTag("shuttle_test_host").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun suppliedTimesRoutesAndFinalBusesAreComplete() {
        show()
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班预计 7:30 · 中蓝校区发车")
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "下一班")).assertCountEquals(1)
        capture("normal")
        shuttleRoutes(ShuttleDayType.WORKDAY).forEach { route ->
            route.departures.forEachIndexed { index, time ->
                compose.onNodeWithTag("shuttle_time_${route.id}_$index", useUnmergedTree = true)
                    .performScrollTo().assertIsDisplayed().assertTextEquals(shuttleTimeLabel(time))
            }
            compose.onNodeWithTag("shuttle_badge_${route.id}_${route.departures.lastIndex}", useUnmergedTree = true)
                .performScrollTo().assertTextContains("末车")
            compose.onNodeWithText(route.stops.joinToString(" → ")).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("shuttle_departure_evening_15").assertDoesNotExist()
        capture("routes")
    }

    @Test fun clockBroadcastsMoveOneHighlightAndResetAfterMidnight() {
        clock.set(LocalDateTime.of(2026, 11, 27, 7, 20, 59)); show()
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班预计 7:20 · 中蓝校区发车")
        clock.set(LocalDateTime.of(2026, 11, 27, 7, 21)); broadcast(Intent.ACTION_TIME_TICK)
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班预计 7:30 · 中蓝校区发车")
        clock.set(LocalDateTime.of(2026, 11, 27, 10, 16)); broadcast(Intent.ACTION_TIME_CHANGED)
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班预计 19:45 · 月亮湖西发车")
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "下一班")).assertCountEquals(1)
        clock.set(LocalDateTime.of(2026, 11, 27, 22, 31)); broadcast(Intent.ACTION_TIMEZONE_CHANGED)
        compose.onNodeWithTag("shuttle_status").assertTextEquals("今日班次已结束")
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "下一班")).assertCountEquals(0)
        clock.set(LocalDateTime.of(2026, 11, 30, 0, 0)); broadcast(Intent.ACTION_DATE_CHANGED)
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班预计 7:20 · 中蓝校区发车")
    }

    @Test fun stoppedPageUnregistersClockAndReturnOrRestorationReadsAgain() {
        val restoration = StateRestorationTester(compose)
        show(restoration = restoration)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(clockContext.receivers.isEmpty())
        val stoppedReads = reads.get()
        clock.set(LocalDateTime.of(2026, 11, 27, 22, 25))
        broadcast(Intent.ACTION_TIME_CHANGED)
        assertEquals(stoppedReads, reads.get())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(1, clockContext.receivers.size)
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班预计 22:30 · 月亮湖西发车")
        clock.set(LocalDateTime.of(2026, 11, 30, 0, 0))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班预计 7:20 · 中蓝校区发车")
    }

    @Test fun smallScreenLargeFontKeepsBothColumnsAndLongRouteReadable() {
        clock.set(LocalDateTime.of(2026, 11, 27, 10, 1)); show(1.8f)
        capture("large-font")
        shuttleRoutes(ShuttleDayType.WORKDAY).forEach { route ->
            route.departures.forEachIndexed { index, _ ->
                val tag = "shuttle_time_${route.id}_$index"
                val node = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue(tag, layouts.isNotEmpty())
                val layout = layouts.single()
                assertFalse("$tag 被裁切: width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, " +
                    "size=${layout.size}, constraints=${layout.layoutInput.constraints}, style=${layout.layoutInput.style}, " +
                    "lines=${layout.lineCount}, paragraphHeight=${layout.multiParagraph.height}", layout.hasVisualOverflow)
            }
            compose.onNodeWithText(route.stops.joinToString(" → ")).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("shuttle_badge_morning_16", useUnmergedTree = true).performScrollTo()
            .assertTextEquals("末车")
        val left = compose.onNodeWithTag("shuttle_departure_morning_8").performScrollTo().fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("shuttle_departure_evening_8").fetchSemanticsNode().boundsInRoot
        assertEquals(left.top, right.top, 0f)
        assertEquals(left.height, right.height, 0f)
    }

    @Test fun weekendTableUsesItsOwnTimesRoutesAndNextBus() {
        clock.set(LocalDateTime.of(2026, 11, 28, 7, 59)); show()
        compose.onNodeWithTag("shuttle_title").assertTextEquals("校车时刻表（周末）")
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班预计 8:00 · 中蓝校区发车")
        capture("weekend-compact")
        shuttleRoutes(ShuttleDayType.WEEKEND).forEach { route ->
            route.departures.forEachIndexed { index, time ->
                compose.onNodeWithTag("shuttle_time_" + route.id + "_" + index, useUnmergedTree = true)
                    .performScrollTo().assertIsDisplayed().assertTextEquals(shuttleTimeLabel(time))
            }
            compose.onNodeWithTag("shuttle_badge_" + route.id + "_4", useUnmergedTree = true)
                .performScrollTo().assertTextEquals("末车")
            compose.onNodeWithText(route.stops.joinToString(" → ")).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("shuttle_departure_morning_5").assertDoesNotExist()
        clock.set(LocalDateTime.of(2026, 11, 28, 22, 0, 59)); broadcast(Intent.ACTION_TIME_TICK)
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班预计 22:00 · 月亮湖发车")
        clock.set(LocalDateTime.of(2026, 11, 28, 22, 1)); broadcast(Intent.ACTION_TIME_TICK)
        compose.onNodeWithTag("shuttle_status").assertTextEquals("今日班次已结束")
    }

    @Test fun topRightSwitchResetsScrollAndOtherDayHasNoHighlight() {
        show()
        compose.onNodeWithTag("shuttle_title").assertTextEquals("校车时刻表（工作）")
        compose.onNodeWithText(shuttleRoutes(ShuttleDayType.WORKDAY)[1].stops.joinToString(" → ")).performScrollTo()
        compose.onNodeWithTag("shuttle_switch").performClick()
        compose.onNodeWithTag("shuttle_title").assertTextEquals("校车时刻表（周末）")
        compose.onNodeWithTag("shuttle_status").assertIsDisplayed().assertTextEquals("非今日时刻表")
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "下一班")).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已过计划发车时间")).assertCountEquals(0)
        capture("weekend-preview")
        repeat(5) { compose.onNodeWithTag("shuttle_switch").performClick() }
        compose.onNodeWithTag("shuttle_title").assertTextEquals("校车时刻表（工作）")
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班预计 7:30 · 中蓝校区发车")
    }

    @Test fun manualTypeRestoresAndShanghaiDateChangeResetsIt() {
        val restoration = StateRestorationTester(compose)
        show(restoration = restoration)
        compose.onNodeWithTag("shuttle_switch").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("shuttle_title").assertTextEquals("校车时刻表（周末）")
        compose.onNodeWithTag("shuttle_status").assertTextEquals("非今日时刻表")
        clock.set(LocalDateTime.of(2026, 11, 28, 0, 0)); broadcast(Intent.ACTION_DATE_CHANGED)
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班预计 8:00 · 中蓝校区发车")
        clock.set(LocalDateTime.of(2026, 11, 30, 0, 0)); broadcast(Intent.ACTION_DATE_CHANGED)
        compose.onNodeWithTag("shuttle_title").assertTextEquals("校车时刻表（工作）")
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班预计 7:20 · 中蓝校区发车")
    }

    @Test fun compactRowsShowSevenCompleteRowsWithoutVisibleNextBadges() {
        show()
        val host = compose.onNodeWithTag("shuttle_test_host").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("shuttle_departure_morning_0").fetchSemanticsNode().boundsInRoot
        val seventh = compose.onNodeWithTag("shuttle_departure_morning_6").fetchSemanticsNode().boundsInRoot
        assertTrue("320×540 视口完整显示至少七行班次", seventh.bottom <= host.bottom)
        compose.runOnIdle { with(compose.density) { assertEquals(40.dp.toPx(), first.height, 1f) } }
        compose.onAllNodesWithText("下一班", substring = false, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("中蓝校区—北工大专线").assertDoesNotExist()
        compose.onNodeWithText("计划发车时间", substring = false).assertDoesNotExist()
        compose.onNodeWithText("校内运营").assertDoesNotExist()
        compose.onNodeWithText("校外运营").assertDoesNotExist()
    }

    @Test fun darkAndTransparentCardsKeepOneHighlight() {
        show(wallpaper = true)
        listOf(AppThemeMode.LIGHT, AppThemeMode.DARK).forEach { theme ->
            listOf(96, 50, 0).forEach { opacity ->
                compose.runOnIdle { appearance.value = WallpaperPreferences(themeMode = theme, cardOpacityPercent = opacity) }
                compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "下一班")).assertCountEquals(1)
                fun sample(tag: String): androidx.compose.ui.graphics.Color {
                    val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
                    return pixels[pixels.width / 8, pixels.height / 4]
                }
                val plain = sample("shuttle_departure_morning_0")
                val striped = sample("shuttle_departure_morning_3")
                val matching = sample("shuttle_departure_evening_3")
                assertTrue("相邻行底色必须不同", kotlin.math.abs(plain.red - striped.red) > .01f)
                assertEquals("同一行两列底色一致", striped.red, matching.red, .005f)
                assertEquals("同一行两列底色一致", striped.blue, matching.blue, .005f)
                capture("appearance-" + theme.name + "-" + opacity)
            }
        }
    }

    @Test fun shortViewportDoubleFontKeepsWeekendTimesAndFinalBadgesReadable() {
        clock.set(LocalDateTime.of(2026, 11, 28, 9, 31)); show(2f, width = 540, height = 280)
        capture("weekend-landscape-double-font")
        shuttleRoutes(ShuttleDayType.WEEKEND).forEach { route ->
            route.departures.forEachIndexed { index, _ ->
                val tag = "shuttle_time_" + route.id + "_" + index
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse(tag, layouts.single().hasVisualOverflow)
            }
            compose.onNodeWithTag("shuttle_badge_" + route.id + "_4", useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed().assertTextEquals("末车")
            compose.onNodeWithText(route.stops.joinToString(" → ")).performScrollTo().assertIsDisplayed()
        }
    }

    /** Drive the registered clock receiver without changing device time or sending protected broadcasts. */
    private class ClockTestContext(base: Context) : ContextWrapper(base) {
        val receivers = mutableMapOf<BroadcastReceiver, IntentFilter>()
        private fun clockFilter(filter: IntentFilter) = filter.hasAction(Intent.ACTION_TIME_TICK)
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? =
            if (receiver != null && clockFilter(filter)) { receivers[receiver] = filter; null }
            else ContextCompat.registerReceiver(baseContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? =
            if (receiver != null && clockFilter(filter)) { receivers[receiver] = filter; null }
            else super.registerReceiver(receiver, filter, flags)
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, permission: String?, scheduler: Handler?): Intent? =
            if (receiver != null && clockFilter(filter)) { receivers[receiver] = filter; null }
            else ContextCompat.registerReceiver(baseContext, receiver, filter, permission, scheduler, ContextCompat.RECEIVER_NOT_EXPORTED)
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, permission: String?, scheduler: Handler?, flags: Int): Intent? =
            if (receiver != null && clockFilter(filter)) { receivers[receiver] = filter; null }
            else super.registerReceiver(receiver, filter, permission, scheduler, flags)
        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            if (receivers.remove(receiver) == null) super.unregisterReceiver(receiver)
        }
        fun dispatch(action: String) {
            receivers.toMap().forEach { (receiver, filter) -> if (filter.hasAction(action)) receiver.onReceive(this, Intent(action)) }
        }
    }
}
