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
    private val clock = AtomicReference(LocalDateTime.of(2026, 11, 17, 7, 21))
    private val reads = AtomicInteger()
    private lateinit var clockContext: ClockTestContext

    private fun show(fontScale: Float = 1f, restoration: StateRestorationTester? = null) {
        clockContext = ClockTestContext(compose.activity)
        val content: @Composable () -> Unit = {
            GongXiaoZhuTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalContext provides clockContext) {
                    Column(Modifier.width(320.dp).height(540.dp).background(MaterialTheme.colorScheme.background)
                        .testTag("shuttle_test_host").verticalScroll(rememberScrollState()).padding(16.dp)) {
                        ShuttleTimetableContent { reads.incrementAndGet(); clock.get() }
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
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班 7:30 · 中蓝公寓发车")
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "下一班")).assertCountEquals(1)
        capture("normal")
        shuttleRoutes.forEach { route ->
            route.departures.forEachIndexed { index, time ->
                compose.onNodeWithTag("shuttle_time_${route.id}_$index", useUnmergedTree = true)
                    .performScrollTo().assertIsDisplayed().assertTextEquals(shuttleTimeLabel(time))
            }
            compose.onNodeWithTag("shuttle_badge_${route.id}_${route.departures.lastIndex}", useUnmergedTree = true)
                .performScrollTo().assertTextContains("末车")
            compose.onNodeWithText(route.stops.joinToString(" → ")).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("shuttle_departure_evening_9").assertDoesNotExist()
        capture("routes")
    }

    @Test fun clockBroadcastsMoveOneHighlightAndResetAfterMidnight() {
        clock.set(LocalDateTime.of(2026, 11, 17, 7, 20, 59)); show()
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班 7:20 · 中蓝公寓发车")
        clock.set(LocalDateTime.of(2026, 11, 17, 7, 21)); broadcast(Intent.ACTION_TIME_TICK)
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班 7:30 · 中蓝公寓发车")
        clock.set(LocalDateTime.of(2026, 11, 17, 10, 1)); broadcast(Intent.ACTION_TIME_CHANGED)
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班 20:30 · 月亮湖西站发车")
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "下一班")).assertCountEquals(1)
        clock.set(LocalDateTime.of(2026, 11, 17, 22, 1)); broadcast(Intent.ACTION_TIMEZONE_CHANGED)
        compose.onNodeWithTag("shuttle_status").assertTextEquals("今日所列班次已结束")
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "下一班")).assertCountEquals(0)
        clock.set(LocalDateTime.of(2026, 11, 18, 0, 0)); broadcast(Intent.ACTION_DATE_CHANGED)
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班 7:20 · 中蓝公寓发车")
    }

    @Test fun stoppedPageUnregistersClockAndReturnOrRestorationReadsAgain() {
        val restoration = StateRestorationTester(compose)
        show(restoration = restoration)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(clockContext.receivers.isEmpty())
        val stoppedReads = reads.get()
        clock.set(LocalDateTime.of(2026, 11, 17, 21, 55))
        broadcast(Intent.ACTION_TIME_CHANGED)
        assertEquals(stoppedReads, reads.get())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(1, clockContext.receivers.size)
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班 22:00 · 月亮湖西站发车")
        clock.set(LocalDateTime.of(2026, 11, 18, 0, 0))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("shuttle_status").assertTextEquals("下一班 7:20 · 中蓝公寓发车")
    }

    @Test fun smallScreenLargeFontKeepsBothColumnsAndLongRouteReadable() {
        clock.set(LocalDateTime.of(2026, 11, 17, 9, 46)); show(1.8f)
        capture("large-font")
        shuttleRoutes.forEach { route ->
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
        compose.onNodeWithTag("shuttle_badge_morning_11", useUnmergedTree = true).performScrollTo()
            .assertTextEquals("下一班 · 末车")
        val left = compose.onNodeWithTag("shuttle_departure_morning_8").performScrollTo().fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("shuttle_departure_evening_8").fetchSemanticsNode().boundsInRoot
        assertEquals(left.top, right.top, 0f)
        assertEquals(left.height, right.height, 0f)
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
