package io.github.tbeimu.gongxiaozhu.ui

import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScheduleModeOpacityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalRoborazziApi::class)
    private fun verify(theme: AppThemeMode) {
        val backdrop = Color(0xFF245B93)
        var appearance by mutableStateOf(WallpaperPreferences(themeMode = theme))
        var week by mutableStateOf(false)
        compose.setContent { GongXiaoZhuTheme(appearance) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Box(Modifier.width(240.dp).height(120.dp).background(backdrop).testTag("mode_opacity_host")) {
                    ModeSwitch(week, Modifier.align(Alignment.Center)) { week = !week }
                }
            }
        } }
        val original = compose.onNodeWithTag("schedule_mode").fetchSemanticsNode().boundsInRoot
        for (opacity in listOf(100, 96, 80, 66, 50, 0, 96)) {
            compose.runOnIdle { appearance = appearance.copy(cardOpacityPercent = opacity) }
            for (wholeWeek in listOf(false, true)) {
                // Compare resting fills independently of the existing press ripple.
                compose.runOnIdle { week = wholeWeek }
                val button = compose.onNodeWithTag("schedule_mode").assertIsDisplayed().assertHasClickAction()
                button.assertContentDescriptionEquals(if (wholeWeek) "切换到今日" else "查看整周课表")
                assertEquals("Transparency and mode must preserve the button bounds", original, button.fetchSemanticsNode().boundsInRoot)
                val file = File(System.getProperty("gxz.preview.dir"), "schedule-mode-$theme-$opacity-$wholeWeek.png")
                file.parentFile?.mkdirs()
                captureScreenRoboImage(file.absolutePath)
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                val node = button.fetchSemanticsNode()
                val origin = node.positionInWindow
                val actual = Color(bitmap.getPixel((origin.x + with(compose.density) { 8.dp.toPx() }).roundToInt(),
                    (origin.y + node.boundsInRoot.height / 2).roundToInt()))
                bitmap.recycle()
                val expected = appearancePalette(appearance, false).glass.compositeOver(backdrop)
                val context = "$theme opacity=$opacity wholeWeek=$wholeWeek"
                assertEquals(context, expected.red, actual.red, 2f / 255)
                assertEquals(context, expected.green, actual.green, 2f / 255)
                assertEquals(context, expected.blue, actual.blue, 2f / 255)
                compose.onNode(hasText(if (wholeWeek) "今日" else "整周") and
                    hasAnyAncestor(hasTestTag("schedule_mode")), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
                        val results = mutableListOf<TextLayoutResult>()
                        assertTrue(get(results))
                        assertEquals(1f, results.single().layoutInput.style.color.alpha, .001f)
                    }
            }
        }
        compose.onNodeWithTag("schedule_mode").performClick()
        compose.runOnIdle { assertFalse(week) }
        compose.onNodeWithTag("schedule_mode").performClick()
        compose.runOnIdle { assertTrue(week) }
    }

    @Test fun lightModesFollowTransparencyAndKeepTextAndAnchors() = verify(AppThemeMode.LIGHT)
    @Test fun darkModesFollowTransparencyAndKeepTextAndAnchors() = verify(AppThemeMode.DARK)
}
