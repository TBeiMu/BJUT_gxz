package io.github.tbeimu.gongxiaozhu

import android.content.Intent
import android.content.ContextWrapper
import android.content.ActivityNotFoundException
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.*
import io.github.tbeimu.gongxiaozhu.network.CampusLoginService
import io.github.tbeimu.gongxiaozhu.update.UpdateDownloadService
import io.github.tbeimu.gongxiaozhu.ui.AppViewModel
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuApp
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
        listOf("SHUTTLE","SCHEDULE","ACCOUNTS","NETWORK","APPEARANCE","ABOUT").forEach { key ->
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
}
