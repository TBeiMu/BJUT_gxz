package io.github.tbeimu.gongxiaozhu

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import io.github.tbeimu.gongxiaozhu.data.AccountState
import io.github.tbeimu.gongxiaozhu.data.createAccountRepository
import io.github.tbeimu.gongxiaozhu.ui.AppViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

@RunWith(AndroidJUnit4::class)
class AccountUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val cleanStorage = object : ExternalResource() {
        override fun before() { createAccountRepository(InstrumentationRegistry.getInstrumentation().targetContext).reset() }
        override fun after() { createAccountRepository(InstrumentationRegistry.getInstrumentation().targetContext).reset() }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(cleanStorage).around(compose)

    @Test fun addingEditingAndRestoringDefaultAccount() {
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithTag("my_NETWORK").assertExists()
        openManager()
        scrollToText("添加账号").performClick()
        compose.onNodeWithTag("account_label").performTextInput("模拟测试账号")
        compose.onNodeWithTag("account_username").performTextInput("ui-demo-only")
        compose.onNodeWithTag("account_password").performTextInput("demo-only-password")
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        waitForEditorClosed()
        assertSavedAccount("模拟测试账号")
        compose.activityRule.scenario.recreate()
        assertSavedAccount("模拟测试账号")
        openManager()
        scrollToText("默认").assertExists()
        scrollToText("编辑").performClick()
        compose.onNodeWithTag("account_username").assertTextContains("ui-demo-only")
        compose.onNodeWithText("取消").performClick()
        scrollToText("删除").performClick()
        compose.onNodeWithText("确认删除", substring = false).performClick()
        compose.waitUntil(10_000) { !model().state.value.busy && model().state.value.data.accounts.isEmpty() }
        scrollToText("还没有账号").assertExists()
    }

    @Test fun selectingAnotherDefaultAndPersistingSettings() {
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithTag("my_NETWORK").assertExists()
        add("第一测试账号", "ui-first-demo")
        openManager()
        scrollToText("添加账号").performClick()
        fillAndSave("第二测试账号", "ui-second-demo")
        assertSavedAccount("第二测试账号")
        compose.onNodeWithTag("campus_network_list").performScrollToNode(hasText("设为默认", substring = false) and isEnabled())
        compose.onNode(hasText("设为默认", substring = false) and isEnabled()).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            storedStateWhenIdle()?.defaultAccount?.label == "第二测试账号"
        }
        waitForAccountFeedbackToClear()
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithTag("my_NETWORK").performScrollTo().performClick()
        openSettings()
        waitForEnabled("打开 App 自动登录")
        waitForAccountFeedbackToClear()
        compose.onNodeWithContentDescription("打开 App 自动登录").assertIsOn().performScrollTo().performClick()
        compose.waitUntil(10_000) { storedStateWhenIdle()?.settings?.autoLogin == false }
        waitForEnabled("网络模式：宿舍 WiFi")
        waitForAccountFeedbackToClear()
        compose.onNodeWithContentDescription("网络模式：宿舍 WiFi").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            storedStateWhenIdle()?.settings?.networkMode == io.github.tbeimu.gongxiaozhu.data.NetworkMode.DORM_WIFI
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("campus_network_list").performScrollToNode(hasContentDescription("打开 App 自动登录"))
        compose.onNodeWithContentDescription("打开 App 自动登录").assertIsOff()
        compose.onNodeWithTag("campus_network_list").performScrollToNode(hasContentDescription("网络模式：宿舍 WiFi"))
        compose.onNodeWithContentDescription("网络模式：宿舍 WiFi").assertIsSelected()
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithTag("my_NETWORK").assertExists()
    }

    private fun waitFor(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun storedStateWhenIdle(): AccountState? {
        // A second AtomicFile reader must not remove an in-progress .new file.
        // Wait for the app's shared repository write to finish before checking persistence.
        val state = ViewModelProvider(compose.activity).get(AppViewModel::class.java).state.value
        return if (state.busy || state.loading) null else createAccountRepository(compose.activity).load()
    }

    // An encrypted file write can finish before its UI state update. Wait for the controls,
    // not only for the repository value, before attempting the next settings operation.
    private fun waitForEnabled(description: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasContentDescription(description) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForAccountFeedbackToClear() {
        val messages = listOf("账号已加密保存", "默认账号已更新，将用于后续登录", "设置已保存", "账号已删除")
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            val current = model().state.value
            !current.busy && !current.loading && current.message !in messages && messages.all { message ->
                compose.onAllNodes(hasText(message, substring = false)).fetchSemanticsNodes().isEmpty()
            }
        }
    }

    private fun openManager() {
        waitForAccountFeedbackToClear()
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithTag("my_NETWORK").performScrollTo().performClick()
        waitFor("校园网")
    }

    private fun openSettings() {
        waitForAccountFeedbackToClear()
        compose.onNodeWithTag("campus_network_list").performScrollToNode(hasTestTag("campus_settings_toggle"))
        compose.onNodeWithTag("campus_settings_toggle").performScrollTo().performClick()
    }

    private fun assertSavedAccount(label: String) {
        compose.waitUntil(10_000) { !model().state.value.busy && !model().state.value.loading &&
            model().state.value.data.accounts.any { it.label == label } }
        waitForAccountFeedbackToClear()
        val id = model().state.value.data.accounts.single { it.label == label }.id
        val card = hasTestTag("campus_account_$id")
        compose.onNodeWithTag("campus_network_list").performScrollToNode(card)
        compose.onNode(hasText(label, substring = false) and hasAnyAncestor(card)).assertIsDisplayed()
    }

    private fun scrollToText(text: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        // Saving can finish before its Snackbar leaves this same list's lower touch targets.
        waitForAccountFeedbackToClear()
        compose.onNodeWithTag("campus_network_list").performScrollToNode(hasText(text, substring = false))
        return compose.onNodeWithText(text, substring = false).performScrollTo()
    }

    @Test fun billingReplacesAccountTabAndOffersManagementFromFooter() {
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithTag("my_NETWORK").assertExists()
        compose.onNodeWithText("资费", substring = false).performClick()
        listOf("已用流量", "可用流量", "消费保护", "账户余额").forEach {
            compose.onNodeWithTag("metric_$it").assertExists()
        }
        compose.onNodeWithText("管理账号", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("校园网", substring = false).assertExists()
        scrollToText("还没有账号").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("metric_已用流量").assertExists()
    }

    @Test fun unsavedAccountDraftAndDiscardConfirmationSurviveRecreation() {
        openManager()
        scrollToText("添加账号").performClick()
        compose.onNodeWithTag("account_label").performTextInput("未保存的模拟备注")
        compose.onNodeWithTag("account_username").performTextInput("ui-unsaved-demo")
        compose.onNodeWithTag("account_password").performTextInput("unsaved-demo-password")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("account_label").assertTextContains("未保存的模拟备注")
        compose.onNodeWithTag("account_username").assertTextContains("ui-unsaved-demo")
        compose.runOnIdle { assertEquals("unsaved-demo-password", model().state.value.editorDraft?.password) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("discard_changes_dialog").assertExists()
        compose.onNodeWithTag("account_password").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("discard_changes_dialog").assertExists()
        compose.onNodeWithTag("continue_editing").performClick()
        compose.onNodeWithTag("account_label").assertTextContains("未保存的模拟备注")
        compose.onNodeWithTag("account_username").assertTextContains("ui-unsaved-demo")
        compose.runOnIdle { assertEquals("unsaved-demo-password", model().state.value.editorDraft?.password) }
        compose.onNodeWithText("保存").assertIsEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("discard_changes").performClick()
        waitForEditorClosed()
        compose.runOnIdle {
            assertNull(model().state.value.editor)
            assertNull(model().state.value.editorDraft)
            assertFalse(model().state.value.discardAccountChanges)
        }
        scrollToText("添加账号").performClick()
        compose.runOnIdle {
            val fresh = checkNotNull(model().state.value.editorDraft)
            assertEquals("", fresh.label)
            assertEquals("", fresh.username)
            assertEquals("", fresh.password)
        }
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        waitForEditorClosed()
        compose.onNodeWithTag("discard_changes_dialog").assertDoesNotExist()
    }

    @Test fun unchangedRevertedAndTrimOnlyAccountEditsCannotSave() {
        add("原有模拟备注", "ui-normalized-demo")
        scrollToText("编辑").performClick()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("account_label").performTextReplacement("修改后的备注")
        compose.onNodeWithText("保存").assertIsEnabled()
        compose.onNodeWithTag("account_label").performTextReplacement("原有模拟备注")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("account_label").performTextReplacement("  原有模拟备注  ")
        compose.onNodeWithTag("account_username").performTextReplacement("  ui-normalized-demo  ")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        waitForEditorClosed()
        compose.onNodeWithTag("discard_changes_dialog").assertDoesNotExist()
        val saved = checkNotNull(storedStateWhenIdle()).defaultAccount!!
        assertEquals("原有模拟备注", saved.label)
        assertEquals("ui-normalized-demo", saved.username)
    }

    @Test fun clearingAccountLabelAndChangingPasswordSpacesRequireDiscardConfirmation() {
        add("保留的模拟备注", "ui-significant-edit-demo")
        scrollToText("编辑").performClick()
        compose.onNodeWithTag("account_label").performTextReplacement("")
        compose.onNodeWithText("保存").assertIsEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("discard_changes_dialog").assertExists()
        compose.onNodeWithTag("continue_editing").performClick()
        compose.runOnIdle { assertEquals("", model().state.value.editorDraft?.label) }
        compose.onNodeWithTag("account_label").performTextReplacement("保留的模拟备注")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("account_password").performTextReplacement("demo-only-password ")
        compose.onNodeWithText("保存").assertIsEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("discard_changes_dialog").assertExists()
        compose.onNodeWithTag("discard_changes").performClick()
        waitForEditorClosed()
        scrollToText("编辑").performClick()
        compose.onNodeWithTag("account_label").assertTextContains("保留的模拟备注")
        compose.runOnIdle { assertEquals("demo-only-password", model().state.value.editorDraft?.password) }
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        waitForEditorClosed()
    }

    @Test fun viewModelIgnoresUnchangedAccountSaveWithoutWritingOrClosingEditor() {
        add("原样保存模拟账号", "ui-no-op-save-demo")
        scrollToText("编辑").performClick()
        compose.onNodeWithTag("account_username").assertTextContains("ui-no-op-save-demo")
        val vault = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "accounts.vault")
        val originalBytes = vault.readBytes()
        compose.runOnIdle {
            val currentModel = model()
            val before = currentModel.state.value
            currentModel.saveAccount(checkNotNull(before.editorDraft))
            assertSame(before, currentModel.state.value)
        }
        compose.waitForIdle()
        assertArrayEquals(originalBytes, vault.readBytes())
        compose.onNodeWithTag("account_username").assertTextContains("ui-no-op-save-demo")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        waitForEditorClosed()
    }

    private fun model() = ViewModelProvider(compose.activity).get(AppViewModel::class.java)

    private fun add(label: String, username: String) {
        openManager()
        scrollToText("添加账号").performClick()
        fillAndSave(label, username)
        assertSavedAccount(label)
    }

    private fun fillAndSave(label: String, username: String) {
        compose.onNodeWithTag("account_label").performTextInput(label)
        compose.onNodeWithTag("account_username").performTextInput(username)
        compose.onNodeWithTag("account_password").performTextInput("demo-only-password")
        compose.onNodeWithText("保存").performClick()
        waitForEditorClosed()
    }

    private fun waitForEditorClosed() = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag("account_password")).fetchSemanticsNodes().isEmpty()
    }
}
