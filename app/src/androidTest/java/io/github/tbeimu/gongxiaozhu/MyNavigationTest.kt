package io.github.tbeimu.gongxiaozhu

import android.content.Intent
import android.content.ContextWrapper
import android.content.ActivityNotFoundException
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.*
import io.github.tbeimu.gongxiaozhu.network.CampusLoginService
import io.github.tbeimu.gongxiaozhu.update.UpdateDownloadService
import io.github.tbeimu.gongxiaozhu.ui.AppViewModel
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuApp
import io.github.tbeimu.gongxiaozhu.ui.MyDestination
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Assert.*

class MyNavigationTest {
    private val compose=createAndroidComposeRule<MainActivity>()
    private val fixture=object:ExternalResource(){
        override fun before(){createAccountRepository(InstrumentationRegistry.getInstrumentation().targetContext).apply{reset();load();settings(AppSettings(autoLogin=false))}}
        override fun after(){createAccountRepository(InstrumentationRegistry.getInstrumentation().targetContext).reset()}
    }
    @get:Rule val rules:RuleChain=RuleChain.outerRule(fixture).around(compose)

    private fun interceptProjectLaunch(onLaunch: (Intent) -> Unit) {
        compose.runOnUiThread {
            val model = ViewModelProvider(compose.activity).get(AppViewModel::class.java)
            val context = object : ContextWrapper(compose.activity) {
                override fun startActivity(intent: Intent) = onLaunch(intent)
            }
            compose.activity.setContent {
                CompositionLocalProvider(LocalContext provides context) { GongXiaoZhuApp(model) }
            }
        }
        compose.onNodeWithText("我的", substring = false).performClick()
    }

    @Test fun githubOpensProjectHomepageOnceAndKeepsMineOnReturn() {
        val launches = mutableListOf<Intent>()
        interceptProjectLaunch(launches::add)
        compose.onNodeWithTag("my_github").performClick()
        compose.runOnIdle {
            assertEquals(1, launches.size)
            assertEquals(Intent.ACTION_VIEW, launches.single().action)
            assertEquals("https://github.com/TBeiMu/BJUT_gxz", launches.single().dataString)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("my_github").assertIsDisplayed()
        compose.onNodeWithTag("my_ABOUT").performScrollTo().performClick()
        compose.onNodeWithTag("my_github").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("my_github").assertIsDisplayed()
        val footer = compose.onNodeWithTag("my_github").fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithText("我的", substring = false).fetchSemanticsNode().boundsInRoot
        assertTrue(footer.bottom <= dock.top)
        if (InstrumentationRegistry.getArguments().getString("gxzSavePreview") == "true") {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getExternalFilesDir(null)!!.resolve("my-github-app.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun githubWithoutBrowserShowsFeedbackAndKeepsPage() {
        interceptProjectLaunch { throw ActivityNotFoundException() }
        compose.onNodeWithTag("my_github").performClick()
        compose.onNodeWithText("暂时无法打开 GitHub 项目主页").assertIsDisplayed()
        compose.onNodeWithTag("my_github").assertIsDisplayed()
    }

    @Test fun githubRejectedBySystemShowsFeedbackAndKeepsPage() {
        interceptProjectLaunch { throw SecurityException() }
        compose.onNodeWithTag("my_github").performClick()
        compose.onNodeWithText("暂时无法打开 GitHub 项目主页").assertIsDisplayed()
        compose.onNodeWithTag("my_github").assertIsDisplayed()
    }

    @Test fun destinationsReturnAndBottomTabsStayAvailable(){
        compose.onNodeWithText("我的",substring=false).performClick()
        compose.onNodeWithText("校园服务",substring=false).assertDoesNotExist()
        compose.onNodeWithText("应用",substring=false).assertDoesNotExist()
        compose.onNodeWithText("壁纸与默认背景").assertDoesNotExist()
        compose.onNodeWithText("你的北工大校园助手").assertDoesNotExist()
        compose.onNodeWithText("检查更新").assertExists()
        compose.onNodeWithContentDescription("打开 App 自动登录").assertDoesNotExist()
        compose.onNodeWithTag("my_ACCOUNTS").assertDoesNotExist()
        compose.onAllNodesWithText("校园网", substring = false).assertCountEquals(1)
        listOf("SHUTTLE","SCHEDULE","NETWORK","APPEARANCE","ABOUT").forEach { key ->
            compose.onNodeWithTag("my_$key").performScrollTo().performClick()
            compose.onNodeWithText("资费",substring=false).assertExists()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("my_$key").assertExists()
        }
        compose.onNodeWithTag("my_ABOUT").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("北京工业大学校园助手 · Android 12 及以上").assertExists()
        compose.onNodeWithText("我的",substring=false).performClick()
        compose.onNodeWithTag("my_UPDATES").assertExists()
        compose.onNodeWithTag("my_profile_greeting").assertExists()
    }
    @Test fun shuttleWorksWithoutAccountsAndRestoresBothBackPaths(){
        compose.onNodeWithText("我的",substring=false).performClick()
        assertTrue(compose.onNodeWithTag("my_SHUTTLE").fetchSemanticsNode().boundsInRoot.bottom <=
            compose.onNodeWithTag("my_SCHEDULE").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithTag("my_SHUTTLE").performClick()
        compose.onNodeWithTag("shuttle_timetable").assertExists()
        compose.onNodeWithText("资费",substring=false).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("shuttle_timetable").assertExists()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.getExternalFilesDir(null)!!.resolve("shuttle-app.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("my_SHUTTLE").assertIsDisplayed()
        compose.onNodeWithTag("my_SHUTTLE").performClick()
        compose.onNodeWithTag("shuttle_timetable").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("my_SHUTTLE").assertIsDisplayed()
    }
    @Test fun notificationIntentsOpenTheirOwnDetailsAndReturnToMine(){
        val updateRequests=mutableIntStateOf(0)
        val loginRequests=mutableIntStateOf(0)
        compose.runOnUiThread {
            val model=ViewModelProvider(compose.activity).get(AppViewModel::class.java)
            compose.activity.setContent { GongXiaoZhuApp(model,updateRequests.intValue,loginRequests.intValue) }
        }
        compose.runOnUiThread { updateRequests.intValue++ }
        compose.onNodeWithTag("update_card").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("my_UPDATES").assertExists()
        compose.runOnUiThread { loginRequests.intValue++ }
        compose.onNodeWithTag("login_button").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("my_NETWORK").assertExists()
    }

    @Test fun billingAccountManagementKeepsItsSectionThroughRotationAndBothBackPaths() {
        compose.onNodeWithText("资费", substring = false).performClick()
        openBillingAccounts()
        compose.onNodeWithTag("campus_accounts").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("campus_accounts").assertIsDisplayed()
        compose.onNodeWithTag("campus_add_account").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("metric_已用流量").assertExists()
        compose.onNodeWithTag("campus_network_list").assertDoesNotExist()
        openBillingAccounts()
        compose.onNodeWithTag("campus_accounts").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("metric_已用流量").assertExists()
        compose.onNodeWithTag("campus_network_list").assertDoesNotExist()
    }

    @Test fun mineOpensConnectionAndNotificationReentryResetsOnlyTheRequestedPosition() {
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithTag("my_NETWORK").performScrollTo().performClick()
        compose.onNodeWithTag("campus_connection").assertIsDisplayed()
        compose.onNodeWithTag("login_button").assertIsDisplayed()
        val list = compose.onNodeWithTag("campus_network_list")
        list.performScrollToNode(hasTestTag("campus_settings_toggle"))
        compose.onNodeWithTag("campus_settings_toggle").performScrollTo().performClick()
        list.performScrollToNode(hasContentDescription("网络模式：宿舍 WiFi"))
        compose.onNodeWithContentDescription("网络模式：宿舍 WiFi").performScrollTo().assertIsDisplayed()
        val beforeRotation = compose.onNodeWithTag("campus_settings_toggle").fetchSemanticsNode().boundsInRoot
        val beforeDiagnostic = campusPositionDiagnostic()
        campusPositionPreview("before-rotation")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("campus_settings").assertExists()
        compose.onNodeWithContentDescription("网络模式：宿舍 WiFi").assertIsDisplayed()
        val restored = compose.onNodeWithTag("campus_settings_toggle").fetchSemanticsNode().boundsInRoot
        val afterDiagnostic = campusPositionDiagnostic()
        campusPositionPreview("after-rotation")
        val diagnostic = "Before: $beforeDiagnostic; after: $afterDiagnostic"
        assertEquals(diagnostic, beforeRotation.top, restored.top, 1f)
        assertEquals(diagnostic, beforeRotation.bottom, restored.bottom, 1f)
        openLoginNotification()
        compose.onNodeWithTag("campus_connection").assertIsDisplayed()
        compose.onNodeWithTag("login_button").assertIsDisplayed()
        // A second notification is a new request even while the merged page is already open.
        list.performScrollToNode(hasTestTag("campus_settings_toggle"))
        compose.onNodeWithTag("campus_settings_toggle").performScrollTo().assertIsDisplayed()
        openLoginNotification()
        compose.onNodeWithTag("campus_connection").assertIsDisplayed()
        compose.onNodeWithTag("login_button").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("my_NETWORK").assertExists()
        compose.onNodeWithTag("my_github").assertIsDisplayed()
    }

    @Test fun legacySavedAccountDestinationRestoresIntoTheMergedPageAndRetainsBillingOrigin() {
        val mounted = mutableStateOf(true)
        val registry = mutableStateOf(SaveableStateRegistry(null) { true })
        compose.runOnUiThread {
            val model = ViewModelProvider(compose.activity).get(AppViewModel::class.java)
            compose.activity.setContent {
                if (mounted.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry.value) {
                    GongXiaoZhuApp(model)
                }
            }
        }
        compose.onNodeWithText("资费", substring = false).performClick()
        openBillingAccounts()
        compose.onNodeWithTag("campus_accounts").assertIsDisplayed()
        lateinit var saved: Map<String, List<Any?>>
        var aliases = 0
        fun legacy(value: Any?): Any? = when (value) {
            MyDestination.NETWORK -> { aliases++; MyDestination.ACCOUNTS }
            is MutableState<*> -> {
                val old = value.value
                val replacement = legacy(old)
                if (replacement === old) value else mutableStateOf(replacement)
            }
            is Map<*, *> -> value.mapValues { legacy(it.value) }
            is List<*> -> value.map(::legacy)
            else -> value
        }
        compose.runOnIdle {
            saved = registry.value.performSave().mapValues { (_, values) -> values.map(::legacy) }
            assertEquals("Replace the actual saved destination, not a test-only route", 1, aliases)
            mounted.value = false
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            registry.value = SaveableStateRegistry(saved) { true }
            mounted.value = true
        }
        compose.onNodeWithText("校园网", substring = false).assertIsDisplayed()
        compose.onNodeWithTag("campus_accounts").assertIsDisplayed()
        compose.onNodeWithTag("campus_add_account").assertIsDisplayed()
        compose.onNodeWithTag("my_ACCOUNTS").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("metric_已用流量").assertExists()
        compose.onNodeWithTag("campus_network_list").assertDoesNotExist()
    }

    private fun openBillingAccounts() {
        compose.onNodeWithTag("billing_accounts").performScrollTo().performClick()
        compose.onNodeWithText("校园网", substring = false).assertIsDisplayed()
    }

    private fun openLoginNotification() {
        compose.runOnUiThread {
            // Preserve ActivityScenario's launch identity while exercising the real onNewIntent path.
            compose.activity.startActivity(Intent(compose.activity.intent)
                .putExtra(CampusLoginService.OPEN_LOGIN, true)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        compose.waitUntil(10_000) { compose.onNodeWithTag("login_button").isDisplayed() }
        compose.waitForIdle()
    }

    private fun campusPositionDiagnostic(): String {
        val list = compose.onNodeWithTag("campus_network_list").fetchSemanticsNode()
        val range = list.config[SemanticsProperties.VerticalScrollAxisRange]
        val toggle = compose.onNodeWithTag("campus_settings_toggle").fetchSemanticsNode().boundsInRoot
        val focused = compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().map { node ->
            val config = node.config
            val tag = if (config.contains(SemanticsProperties.TestTag)) config[SemanticsProperties.TestTag] else ""
            val text = if (config.contains(SemanticsProperties.Text)) config[SemanticsProperties.Text].joinToString { it.text } else ""
            "$tag:$text"
        }
        var platform = ""
        compose.runOnIdle {
            val activity = compose.activity
            val decor = activity.window.decorView
            val insets = decor.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())
            val state = ViewModelProvider(activity).get(AppViewModel::class.java).state.value
            platform = "decor=${decor.width}x${decor.height}, systemBars=$insets, " +
                "touchMode=${decor.isInTouchMode}, decorFocused=${decor.isFocused}, focused=$focused, " +
                "notifications=${androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled()}, " +
                "running=${state.login.running}, wifiKnown=${state.login.wifiSnapshotKnown}, login=${state.login.message}"
        }
        return "list=${list.boundsInRoot}, toggle=$toggle, scroll=${range.value()}/${range.maxValue()}, $platform"
    }

    private fun campusPositionPreview(name: String) {
        val inst = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("gxzSavePreview") != "true") return
        // This class deliberately starts with no accounts or credentials.
        assertTrue(ViewModelProvider(compose.activity).get(AppViewModel::class.java).state.value.data.accounts.isEmpty())
        inst.targetContext.getExternalFilesDir(null)?.resolve("campus-position-$name.png")?.outputStream()?.use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
