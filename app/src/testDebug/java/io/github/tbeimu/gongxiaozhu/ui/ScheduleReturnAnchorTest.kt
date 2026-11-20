package io.github.tbeimu.gongxiaozhu.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.tbeimu.gongxiaozhu.schedule.*
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScheduleReturnAnchorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val monday = LocalDate.of(2026, 11, 2)

    private fun verify(fontScale: Float, weekCount: Int, withStatus: Boolean = false) {
        val identity = ScheduleIdentity("fictional-shared-return-anchor")
        val term = ScheduleTerm("2026-2027", "1", weekCount, 1)
        val weeks = (1..weekCount).map { n -> ScheduleWeek(n,
            (0L..6L).map { monday.plusWeeks(n - 1L).plusDays(it).toString() }, emptyList()) }
        val data = ScheduleData(identity = identity, complete = SemesterSchedule(identity, term, weeks, 1),
            pending = if (withStatus) PendingSchedule(identity, term, listOf(weeks.first())) else null)
        val state = ScheduleUiState(loading = false, data = data,
            notice = if (withStatus) ScheduleNotice.PAUSED else ScheduleNotice.IDLE,
            message = if (withStatus) "课表导入已暂停" else "")
        compose.setContent { GongXiaoZhuTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.width(360.dp).height(680.dp)) {
                    ScheduleHome(state, Modifier, {}, {}, {}, {}, todayProvider = { monday }, nowProvider = { LocalTime.NOON })
                }
            }
        } }
        compose.onNodeWithTag("agenda_day_3").performClick()
        compose.onNodeWithTag("back_current_day").assertIsDisplayed()
        val today = bounds("back_current_day")
        val todaySurface = bounds("back_current_day_surface", unmerged = true)
        compose.onNodeWithTag("schedule_mode").performClick()
        compose.onNodeWithContentDescription("选择周次").performClick()
        compose.onNodeWithTag("week_choice_2").performClick()
        compose.onNodeWithTag("back_current_week").assertIsDisplayed()
        assertSameBounds("touch target", today, bounds("back_current_week"))
        assertSameBounds("visible pill", todaySurface, bounds("back_current_week_surface", unmerged = true))
    }

    private fun bounds(tag: String, unmerged: Boolean = false): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot

    private fun assertSameBounds(label: String, day: Rect, week: Rect) {
        assertEquals("$label left", week.left, day.left, 1f)
        assertEquals("$label top", week.top, day.top, 1f)
        assertEquals("$label right", week.right, day.right, 1f)
        assertEquals("$label bottom", week.bottom, day.bottom, 1f)
    }

    @Test fun returnsUseTheSameAbsoluteAnchor() = verify(1f, 2)
    @Test fun returnsUseTheSameAbsoluteAnchorWithLargeFont() = verify(1.8f, 2)
    @Test fun longSemesterUsesTheSameAbsoluteAnchor() = verify(1f, 24)
    @Test fun pausedImportAndLargeFontUseTheSameAbsoluteAnchor() = verify(1.8f, 24, withStatus = true)
}
