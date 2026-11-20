package io.github.tbeimu.gongxiaozhu

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.tbeimu.gongxiaozhu.network.LoginDiagnostic
import io.github.tbeimu.gongxiaozhu.network.LoginStep
import io.github.tbeimu.gongxiaozhu.network.LoginStepResult
import io.github.tbeimu.gongxiaozhu.network.LoginUiState
import io.github.tbeimu.gongxiaozhu.ui.AppUiState
import io.github.tbeimu.gongxiaozhu.ui.CampusNetworkContent
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real production UI with fictional fixed diagnostics; no repositories, credentials or HTTP. */
class CampusNetworkInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun expandingDiagnosticsDuringRestorationMustOverrideTheOldSavedAnchor() {
        val fixture = AppUiState(loading = false, login = LoginUiState(
            message = "虚构认证已完成", wifiConnected = true, wifiSnapshotKnown = true,
            diagnostics = LoginStep.entries.mapIndexed { index, step ->
                LoginDiagnostic(step, 100L + index, LoginStepResult.RESPONSE)
            }))
        var initialHeight = 320
        var resize: (Int) -> Unit = {}
        val restore = StateRestorationTester(compose)
        restore.setContent {
            val height = remember { mutableIntStateOf(initialHeight) }
            resize = { height.intValue = it }
            GongXiaoZhuTheme {
                Box(Modifier.width(360.dp).height(height.intValue.dp)) {
                    CampusNetworkContent(fixture, Modifier.fillMaxSize(),
                        onAdd = { error("No account operation expected") },
                        onEdit = { error("No account operation expected") },
                        onDefault = { error("No account operation expected") },
                        onDelete = { error("No account operation expected") },
                        onLogin = { error("No authentication expected") },
                        onCancelLogin = { error("No authentication expected") },
                        onSettings = { error("No setting write expected") },
                        onReload = { error("No storage operation expected") },
                        onReset = { error("No storage operation expected") })
                }
            }
        }
        fun scrollValue() = compose.onNodeWithTag("campus_network_list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()

        // Only the original composition is scrolled. Diagnostics stay collapsed when saving.
        compose.onNodeWithTag("campus_network_list").performScrollToIndex(1)
        assertTrue("The original 320dp page must save a nonzero anchor", scrollValue() > 0f)
        initialHeight = 700
        restore.emulateSavedInstanceStateRestore()
        // Do not scroll here: that would deliberately retire the pending restored anchor.
        compose.onNodeWithText("查看认证耗时").assertIsDisplayed()
        val restoredViewport = compose.onNodeWithTag("campus_network_list").fetchSemanticsNode().boundsInRoot
        assertTrue("The replacement viewport must actually be taller than the saved page",
            restoredViewport.height > with(compose.density) { 320.dp.toPx() } + 1f)
        compose.onNodeWithText("查看认证耗时").performClick()
        compose.onNodeWithText("收起认证耗时").assertIsDisplayed()
        val userPosition = scrollValue()
        compose.runOnIdle { resize(320) }
        val actual = scrollValue()
        assertEquals("An explicit diagnostics expansion must retire the old restored anchor before viewport resize",
            userPosition, actual, .001f)
        compose.onNodeWithText("收起认证耗时").assertIsDisplayed()
    }
}
