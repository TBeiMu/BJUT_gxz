package io.github.tbeimu.gongxiaozhu.ui

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ReducedMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var originalScale = 1f

    @Before fun rememberSetting() {
        originalScale = Settings.Global.getFloat(compose.activity.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @After fun restoreSetting() {
        changeScale(originalScale)
    }

    private fun changeScale(scale: Float) {
        compose.runOnUiThread {
            val resolver = compose.activity.contentResolver
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
            // Deliver the SettingsProvider notification explicitly in the platform shadow.
            resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        }
    }

    private fun show() {
        compose.setContent { Text(if (rememberReducedMotion()) "静止" else "动态") }
    }

    @Test fun animationPreferenceChangesUpdateWhileVisible() {
        changeScale(1f)
        show()
        compose.onNodeWithText("动态").assertExists()
        changeScale(0f)
        compose.onNodeWithText("静止").assertExists()
        changeScale(1f)
        compose.onNodeWithText("动态").assertExists()
    }

    @Test fun returningToForegroundRechecksAnimationPreference() {
        changeScale(1f)
        show()
        compose.onNodeWithText("动态").assertExists()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        changeScale(0f)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("静止").assertExists()
    }
}
