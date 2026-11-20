package io.github.tbeimu.gongxiaozhu

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.data.AccountState
import io.github.tbeimu.gongxiaozhu.data.AccountSummary
import io.github.tbeimu.gongxiaozhu.data.AppSettings
import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import io.github.tbeimu.gongxiaozhu.network.LoginUiState
import io.github.tbeimu.gongxiaozhu.ui.AppBackdrop
import io.github.tbeimu.gongxiaozhu.ui.AppUiState
import io.github.tbeimu.gongxiaozhu.ui.CampusNetworkContent
import io.github.tbeimu.gongxiaozhu.ui.CampusNetworkSection
import io.github.tbeimu.gongxiaozhu.ui.GongXiaoZhuTheme
import io.github.tbeimu.gongxiaozhu.ui.WallpaperState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Fictional summaries only: no stored accounts, passwords, HTTP sessions or authentication services. */
class CampusNetworkUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Events {
        var adds = 0; var logins = 0; var cancels = 0; var reloads = 0; var resets = 0
        val edits = mutableListOf<String>()
        val defaults = mutableListOf<String>()
        val deletes = mutableListOf<AccountSummary>()
        val settings = mutableListOf<AppSettings>()
    }
    private fun accounts(count: Int = 2, longNames: Boolean = false) = (0 until count).map { index ->
        AccountSummary("fictional-$index", if (longNames) "第" + (index + 1) + "个用于验证完整换行的虚构长备注账号" else "模拟账号" + (index + 1),
            "fictional-campus-account-$index")
    }
    private fun state(accounts: List<AccountSummary> = accounts()) = AppUiState(loading = false,
        data = AccountState(accounts, accounts.firstOrNull()?.id),
        login = LoginUiState(message = "等待手动检查校园网", wifiConnected = true, wifiSnapshotKnown = true))

    @Composable
    private fun Preview(state: AppUiState, events: Events, section: CampusNetworkSection = CampusNetworkSection.CONNECTION,
        request: Long = 0L, fontScale: Float = 1f, dark: Boolean = false, height: Int = 600,
        onSettings: (AppSettings) -> Unit = {}) {
        val configuration = Configuration(LocalConfiguration.current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        CompositionLocalProvider(LocalConfiguration provides configuration) { GongXiaoZhuTheme { AppBackdrop(WallpaperState()) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.width(360.dp).height(height.dp)) {
                    CampusNetworkContent(state, Modifier.fillMaxSize(), section, request,
                        onAdd = { events.adds++ }, onEdit = { events.edits += it },
                        onDefault = { events.defaults += it }, onDelete = { events.deletes += it },
                        onLogin = { events.logins++ }, onCancelLogin = { events.cancels++ },
                        onSettings = { events.settings += it; onSettings(it) },
                        onReload = { events.reloads++ }, onReset = { events.resets++ })
                }
            }
        } } }
    }
    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("campus_network_list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun accountAction(id: String, text: String) = compose.onNode(
        hasText(text, substring = false) and hasAnyAncestor(hasTestTag("campus_account_$id")))
    private fun assertAccountEntryLocatedAtTop() {
        val list = compose.onNodeWithTag("campus_network_list").fetchSemanticsNode().boundsInRoot
        val accounts = compose.onNodeWithTag("campus_accounts").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("账号入口应定位到列表顶部内边距", accounts.top >= list.top - 1f &&
            accounts.top <= list.top + with(compose.density) { 16.dp.toPx() } + 1f)
        compose.onNodeWithTag("campus_add_account").assertIsDisplayed()
    }
    private fun assertTextFits(text: String, ancestor: String? = null) {
        val matcher = hasText(text, substring = false).let { if (ancestor == null) it else it and hasAnyAncestor(hasTestTag(ancestor)) }
        compose.onNode(matcher, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(get(layouts))
            val layout = layouts.single()
            assertFalse("Text must not be clipped: $text", layout.didOverflowHeight)
            assertFalse("Text must not exceed its allowed line count: $text", layout.multiParagraph.didExceedMaxLines)
            for (line in 0 until layout.lineCount) {
                assertTrue("Text must fit its row: $text", layout.getLineLeft(line) >= -.5f)
                assertTrue("Text must fit its row: $text", layout.getLineRight(line) <= layout.size.width + .5f)
            }
        }
    }
    private fun preview(name: String) {
        val inst = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("gxzSavePreview") != "true") return
        inst.targetContext.getExternalFilesDir(null)?.resolve("campus-network-$name.png")?.outputStream()?.use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun connectionAccountsAndSettingsShareOneListAndRouteActionsToTheSelectedAccount() {
        val entries = accounts(); val events = Events()
        compose.setContent { Preview(state(entries), events) }
        compose.onNodeWithTag("campus_connection").assertIsDisplayed()
        val initialConnection = compose.onNodeWithTag("campus_connection").fetchSemanticsNode().boundsInRoot
        val initialAccounts = compose.onAllNodesWithTag("campus_accounts").fetchSemanticsNodes().firstOrNull()
        if (initialAccounts != null) assertTrue("账号区必须位于连接卡片下方", initialConnection.bottom <= initialAccounts.boundsInRoot.top)
        compose.onNodeWithTag("campus_network_list").performScrollToIndex(1)
        compose.onNodeWithTag("campus_accounts").assertIsDisplayed()
        val remainingConnection = compose.onAllNodesWithTag("campus_connection").fetchSemanticsNodes().firstOrNull()
        if (remainingConnection != null) assertTrue("短列表允许前卡仍可见，但不能与账号区重叠",
            remainingConnection.boundsInRoot.bottom <= compose.onNodeWithTag("campus_accounts").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithTag("campus_network_list").performScrollToIndex(0)
        compose.onNodeWithTag("login_button").performClick()
        assertEquals(1, events.logins)
        scrollTo("campus_add_account").performClick(); assertEquals(1, events.adds)
        val second = entries.last()
        scrollTo("campus_account_" + second.id)
        accountAction(second.id, "编辑").performScrollTo().performClick()
        accountAction(second.id, "设为默认").performScrollTo().performClick()
        accountAction(second.id, "删除").performScrollTo().performClick()
        assertEquals(listOf(second.id), events.edits)
        assertEquals(listOf(second.id), events.defaults)
        assertEquals(listOf(second), events.deletes)
        scrollTo("campus_settings_toggle").assertHasClickAction()
        assertEquals(0, events.reloads + events.resets + events.cancels)
    }

    @Test fun connectionSettingsStartCollapsedAndKeepTheirExpandedStateThroughRestoration() {
        val events = Events(); val current = mutableStateOf(state(accounts(0)))
        val restore = StateRestorationTester(compose)
        restore.setContent { Preview(current.value, events, onSettings = { current.value = current.value.copy(data = current.value.data.copy(settings = it)) }) }
        scrollTo("campus_settings_toggle")
        compose.onNodeWithTag("campus_settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("打开 App 自动登录").assertDoesNotExist()
        compose.onNodeWithTag("campus_settings_toggle").performClick()
        compose.onNodeWithTag("campus_settings").assertExists()
        compose.onNodeWithContentDescription("打开 App 自动登录").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithContentDescription("网络模式：宿舍 WiFi").performScrollTo().performClick()
        assertEquals(AppSettings(autoLogin = false, networkMode = NetworkMode.DORM_WIFI), events.settings.last())
        restore.emulateSavedInstanceStateRestore()
        scrollTo("campus_settings_toggle")
        compose.onNodeWithTag("campus_settings").assertExists()
        compose.onNodeWithContentDescription("打开 App 自动登录").performScrollTo().assertIsOff()
        compose.onNodeWithContentDescription("网络模式：宿舍 WiFi").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("campus_settings_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("campus_settings").assertDoesNotExist()
    }

    @Test fun restoredExpandedSettingsKeepTheirExactPositionAfterATransientLargerViewport() {
        val events = Events()
        var restoredViewportHeight = 420
        var resize: (Int) -> Unit = {}
        val restore = StateRestorationTester(compose)
        restore.setContent {
            val height = remember { mutableIntStateOf(restoredViewportHeight) }
            resize = { height.intValue = it }
            Preview(state(accounts(0)), events, height = height.intValue)
        }
        scrollTo("campus_settings_toggle").performClick()
        compose.onNodeWithContentDescription("网络模式：宿舍 WiFi").performScrollTo().assertIsDisplayed()
        val list = compose.onNodeWithTag("campus_network_list").fetchSemanticsNode()
        assertTrue("The saved position must be scrolled beyond the initial item",
            list.config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        val before = compose.onNodeWithTag("campus_settings_toggle").fetchSemanticsNode().boundsInRoot
        // Change only the next composition's initial viewport; do not resize/clamp the old list before saving.
        restoredViewportHeight = 600
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("campus_settings").assertExists()
        compose.runOnIdle { resize(420) }
        val after = compose.onNodeWithTag("campus_settings_toggle").fetchSemanticsNode().boundsInRoot
        assertEquals("Temporary framework viewport changes must preserve the saved settings top", before.top, after.top, 1f)
        assertEquals("Temporary framework viewport changes must preserve the saved settings bottom", before.bottom, after.bottom, 1f)
        assertEquals(0, events.logins + events.adds + events.settings.size)
    }

    @Test fun accessibilityScrollDuringTransientRestorationOverridesTheOldSavedAnchor() {
        val events = Events()
        var restoredViewportHeight = 420
        var resize: (Int) -> Unit = {}
        val restore = StateRestorationTester(compose)
        restore.setContent {
            val height = remember { mutableIntStateOf(restoredViewportHeight) }
            resize = { height.intValue = it }
            Preview(state(accounts(0)), events, height = height.intValue)
        }
        scrollTo("campus_settings_toggle").performClick()
        compose.onNodeWithContentDescription("网络模式：宿舍 WiFi").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("campus_network_list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        restoredViewportHeight = 600
        restore.emulateSavedInstanceStateRestore()
        // This uses the actual list's accessibility action rather than changing its state directly.
        compose.onNodeWithTag("campus_network_list").performScrollToIndex(0)
        assertConnectionEntryLocatedAtTop()
        compose.runOnIdle { resize(420) }
        assertConnectionEntryLocatedAtTop()
        assertEquals(0, events.logins + events.adds + events.settings.size)
    }

    @Test fun aNewEntryRequestDuringTransientRestorationOverridesTheOldSavedAnchor() {
        val events = Events()
        val section = mutableStateOf(CampusNetworkSection.CONNECTION)
        val request = mutableLongStateOf(1L)
        var restoredViewportHeight = 420
        var resize: (Int) -> Unit = {}
        val restore = StateRestorationTester(compose)
        restore.setContent {
            val height = remember { mutableIntStateOf(restoredViewportHeight) }
            resize = { height.intValue = it }
            Preview(state(accounts(0)), events, section.value, request.longValue, height = height.intValue)
        }
        scrollTo("campus_settings_toggle").performClick()
        compose.onNodeWithContentDescription("网络模式：宿舍 WiFi").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("campus_network_list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        restoredViewportHeight = 600
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle { section.value = CampusNetworkSection.CONNECTION; request.longValue = 2L }
        assertConnectionEntryLocatedAtTop()
        compose.runOnIdle { resize(420) }
        assertConnectionEntryLocatedAtTop()
        assertEquals(0, events.logins + events.adds + events.settings.size)
    }

    private fun assertConnectionEntryLocatedAtTop() {
        val list = compose.onNodeWithTag("campus_network_list").fetchSemanticsNode()
        val connection = compose.onNodeWithTag("campus_connection").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Connection entry must remain at the requested top padding",
            list.boundsInRoot.top + with(compose.density) { 16.dp.toPx() }, connection.top, 1f)
        assertEquals(0f, list.config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
    }

    @Test fun entryRequestsLocateTheRightSectionOnceAndOrdinaryRecompositionKeepsBrowsingPosition() {
        val events = Events(); val current = mutableStateOf(state(accounts(20)))
        val section = mutableStateOf(CampusNetworkSection.ACCOUNTS); val request = mutableLongStateOf(1L)
        compose.setContent { Preview(current.value, events, section.value, request.longValue, height = 420) }
        assertAccountEntryLocatedAtTop()
        scrollTo("campus_account_fictional-19")
        val browsing = compose.onNodeWithTag("campus_account_fictional-19").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { current.value = current.value.copy(login = current.value.login.copy(message = "虚构状态更新")) }
        compose.onNodeWithTag("campus_account_fictional-19").assertIsDisplayed()
        assertEquals(browsing, compose.onNodeWithTag("campus_account_fictional-19").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("campus_connection").assertIsNotDisplayed()
        compose.runOnIdle { section.value = CampusNetworkSection.CONNECTION; request.longValue++ }
        compose.onNodeWithTag("campus_connection").assertIsDisplayed()
        compose.onNodeWithTag("login_status").assertTextEquals("虚构状态更新")
        compose.runOnIdle { section.value = CampusNetworkSection.ACCOUNTS; request.longValue++ }
        assertAccountEntryLocatedAtTop()
        assertEquals(0, events.logins + events.adds + events.cancels)
    }

    @Test fun entryRequestsWaitForStorageReadinessAndOnlyTheLatestRequestIsConsumed() {
        val events = Events(); val current = mutableStateOf(state(accounts(20)).copy(loading = true))
        val section = mutableStateOf(CampusNetworkSection.ACCOUNTS); val request = mutableLongStateOf(1L)
        compose.setContent { Preview(current.value, events, section.value, request.longValue, height = 420) }
        compose.onNodeWithTag("campus_storage_recovery").assertIsDisplayed()
        compose.onNodeWithTag("campus_accounts").assertDoesNotExist()
        compose.onNodeWithTag("campus_connection").assertDoesNotExist()
        compose.runOnIdle { section.value = CampusNetworkSection.CONNECTION; request.longValue = 2L }
        compose.onNodeWithTag("campus_storage_recovery").assertIsDisplayed()
        compose.runOnIdle { section.value = CampusNetworkSection.ACCOUNTS; request.longValue = 3L }
        compose.onNodeWithTag("campus_storage_recovery").assertIsDisplayed()
        compose.onNodeWithTag("campus_add_account").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(loading = false) }
        compose.onNodeWithTag("campus_storage_recovery").assertDoesNotExist()
        assertAccountEntryLocatedAtTop()
        scrollTo("campus_account_fictional-19")
        val browsing = compose.onNodeWithTag("campus_account_fictional-19").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { current.value = current.value.copy(login = current.value.login.copy(message = "资料就绪后的虚构状态更新")) }
        compose.onNodeWithTag("campus_account_fictional-19").assertIsDisplayed()
        assertEquals(browsing, compose.onNodeWithTag("campus_account_fictional-19").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("campus_connection").assertIsNotDisplayed()
        assertEquals(0, events.logins + events.adds + events.cancels + events.reloads + events.resets)
    }

    @Test fun loadingAndBlockedStorageExposeOnlyRecoveryWithoutAccountOrConnectionActions() {
        val events = Events(); val current = mutableStateOf(state().copy(loading = true))
        compose.setContent { Preview(current.value, events) }
        compose.onNodeWithTag("campus_storage_recovery").assertIsDisplayed()
        compose.onNodeWithText("正在读取账号…").assertIsDisplayed()
        compose.onNodeWithTag("login_button").assertDoesNotExist()
        compose.onNodeWithTag("campus_add_account").assertDoesNotExist()
        compose.onNodeWithTag("campus_account_fictional-0").assertDoesNotExist()
        compose.onNodeWithTag("campus_settings_toggle").assertDoesNotExist()
        compose.onNodeWithText("重试读取").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(loading = false, storageBlocked = true) }
        compose.onNodeWithText("账号存储暂不可用").assertIsDisplayed()
        compose.onNodeWithText("重试读取").performClick()
        compose.onNodeWithText("清除本机账号并重新录入").performScrollTo().performClick()
        assertEquals(1, events.reloads); assertEquals(1, events.resets)
        assertEquals(0, events.adds + events.logins + events.cancels + events.edits.size + events.defaults.size + events.deletes.size + events.settings.size)
    }

    @Test fun twentyAccountsRemainReadableAndActionableInSmallLightLargeFontWindow() = longList(dark = false)
    @Test fun twentyAccountsRemainReadableAndActionableInSmallDarkLargeFontWindow() = longList(dark = true)

    private fun longList(dark: Boolean) {
        val entries = accounts(20, longNames = true); val events = Events()
        compose.setContent { Preview(state(entries), events, fontScale = 2f, dark = dark, height = 420) }
        entries.forEach { account ->
            val tag = "campus_account_" + account.id
            scrollTo(tag)
            compose.onNode(hasText(account.label) and hasAnyAncestor(hasTestTag(tag))).performScrollTo().assertIsDisplayed()
            assertTextFits(account.label, tag)
            val edit = accountAction(account.id, "编辑").performScrollTo().assertIsDisplayed().assertIsEnabled()
            assertTrue(edit.fetchSemanticsNode().boundsInRoot.height >= with(compose.density) { 48.dp.toPx() } - 1f)
            edit.performClick()
            if (account.id != entries.first().id) accountAction(account.id, "设为默认").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            val delete = accountAction(account.id, "删除").performScrollTo().assertIsDisplayed().assertIsEnabled()
            assertTrue(delete.fetchSemanticsNode().boundsInRoot.height >= with(compose.density) { 48.dp.toPx() } - 1f)
            delete.performClick()
            listOf("编辑", "设为默认", "删除").forEach { assertTextFits(it, tag) }
        }
        assertEquals(entries.map { it.id }, events.edits)
        assertEquals(entries.drop(1).map { it.id }, events.defaults); assertEquals(entries, events.deletes)
        preview(if (dark) "dark-large-font-accounts" else "light-large-font-accounts")
        scrollTo("campus_settings_toggle").performClick()
        compose.onNodeWithContentDescription("网络模式：宿舍 WiFi").performScrollTo().assertIsDisplayed().performClick()
        assertTextFits("宿舍 WiFi"); assertEquals(NetworkMode.DORM_WIFI, events.settings.single().networkMode)
        scrollTo("campus_add_account").performClick(); assertEquals(1, events.adds)
    }

    @Test fun runningAuthenticationAllowsCancellationButNeverDuplicatesLoginDuringRecomposition() {
        val events = Events(); val current = mutableStateOf(state(accounts(0)).copy(login = LoginUiState(running = true,
            message = "虚构认证处理中", wifiConnected = true, taskId = "fictional-task", wifiSnapshotKnown = true)))
        compose.setContent { Preview(current.value, events) }
        compose.onNodeWithTag("login_button").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithTag("cancel_login").performScrollTo().assertIsEnabled().performClick()
        repeat(3) { index -> compose.runOnIdle { current.value = current.value.copy(login = current.value.login.copy(message = "虚构认证进度$index")) } }
        assertEquals(0, events.logins); assertEquals(1, events.cancels)
        compose.runOnIdle { current.value = current.value.copy(login = current.value.login.copy(running = false)) }
        compose.onNodeWithTag("login_button").performScrollTo().assertIsEnabled().performClick(); assertEquals(1, events.logins)
    }

    @Test fun savingAccountsDisablesCompetingAccountAndConnectionChanges() {
        val entries = accounts(); val events = Events()
        compose.setContent { Preview(state(entries).copy(busy = true), events) }
        compose.onNodeWithTag("login_button").performScrollTo().assertIsNotEnabled()
        scrollTo("campus_add_account").assertIsNotEnabled()
        scrollTo("campus_account_" + entries.last().id)
        listOf("编辑", "设为默认", "删除").forEach { accountAction(entries.last().id, it).performScrollTo().assertIsNotEnabled() }
        scrollTo("campus_settings_toggle").performClick()
        compose.onNodeWithContentDescription("打开 App 自动登录").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("网络模式：公共 WiFi").performScrollTo().assertIsNotEnabled()
        assertEquals(0, events.adds + events.logins + events.cancels + events.edits.size + events.defaults.size + events.deletes.size + events.settings.size)
    }
}
