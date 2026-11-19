package io.github.tbeimu.gongxiaozhu.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.AccountRepository
import io.github.tbeimu.gongxiaozhu.data.AccountState
import io.github.tbeimu.gongxiaozhu.data.AccountValidationException
import io.github.tbeimu.gongxiaozhu.data.AppSettings
import io.github.tbeimu.gongxiaozhu.data.VaultUnavailableException
import io.github.tbeimu.gongxiaozhu.network.CampusLoginRuntime
import io.github.tbeimu.gongxiaozhu.network.LoginUiState
import io.github.tbeimu.gongxiaozhu.network.WifiObservation
import io.github.tbeimu.gongxiaozhu.billing.BillingController
import io.github.tbeimu.gongxiaozhu.billing.BillingUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleUiState
import io.github.tbeimu.gongxiaozhu.schedule.CourseEditContext
import io.github.tbeimu.gongxiaozhu.schedule.CourseDraft
import io.github.tbeimu.gongxiaozhu.schedule.CourseValidationException
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleException
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleCourse
import io.github.tbeimu.gongxiaozhu.schedule.courseEditor

data class AppUiState(
    val data: AccountState = AccountState(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val storageBlocked: Boolean = false,
    val message: String? = null,
    val editor: AccountDraft? = null,
    val editorDraft: AccountDraft? = null,
    val discardAccountChanges: Boolean = false,
    val editorError: String? = null,
    val login: LoginUiState = LoginUiState(),
    val billing: BillingUiState = BillingUiState(),
    val schedule: ScheduleUiState = ScheduleUiState(),
    val courseEditor: CourseEditContext? = null,
    val courseSaving: Boolean = false,
    val courseError: String? = null,
) {
    override fun toString() = "AppUiState(loading=$loading, busy=$busy, storageBlocked=$storageBlocked)"
}

class AppViewModel(private val runtime: CampusLoginRuntime) : ViewModel() {
    private val repository: AccountRepository = runtime.accounts
    private val mutable = MutableStateFlow(AppUiState())
    val state = mutable.asStateFlow()

    private val loginController = runtime.controller
    val connectionNotice = runtime.connectionNotice
    val nameWriteFailure = runtime.accountNames.writeFailure
    fun consumeNameWriteFailure() = runtime.accountNames.consumeWriteFailure()
    val scheduleController = runtime.scheduleImport.controller
    private val courseUndoTimer = CourseUndoTimer()

    fun editCourse(course: ScheduleCourse? = null, week: Int = 1, weekday: Int = java.time.LocalDate.now(
        java.time.ZoneId.of("Asia/Shanghai")).dayOfWeek.value, localTermId: String? = null, deleteOnly: Boolean = false,
        newLocalTerm: Boolean = false) {
        val schedule = scheduleController.state.value
        if (schedule.loading || schedule.busy || schedule.blocked || schedule.page != null || state.value.courseSaving) return
        scheduleController.dismissCourseUndo()
        mutable.update { it.copy(courseEditor = schedule.data.courseEditor(course, week, weekday, localTermId, deleteOnly, newLocalTerm), courseError = null) }
    }
    fun closeCourseEditor() { if (!state.value.courseSaving) mutable.update { it.copy(courseEditor = null, courseError = null) } }
    fun saveCourse(draft: CourseDraft) {
        val editor = state.value.courseEditor ?: return
        if (state.value.courseSaving) return
        mutable.update { it.copy(courseSaving = true, courseError = null) }
        viewModelScope.launch {
            try {
                scheduleController.editCourse(editor, draft)
                mutable.update { it.copy(courseEditor = null, courseSaving = false, courseError = null) }
            } catch (error: CancellationException) { throw error }
            catch (error: CourseValidationException) { mutable.update { it.copy(courseSaving = false, courseError = error.message) } }
            catch (_: ScheduleException) { mutable.update { it.copy(courseSaving = false, courseError = "保存未完成，原课表保留，请重试") } }
            finally { mutable.update { it.copy(courseSaving = false) } }
        }
    }
    fun selectLocalTerm(id: String?) {
        viewModelScope.launch {
            try { scheduleController.selectLocalTerm(id) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutable.update { it.copy(message = "课表暂时无法切换，请重试") } }
        }
    }
    internal fun courseUndoRemaining(token: String, timeout: Long): Long? =
        if (scheduleController.state.value.courseUndo?.token == token) courseUndoTimer.remaining(token, timeout) else null
    fun dismissCourseUndo(token: String) = scheduleController.dismissCourseUndo(token)
    fun undoCourse(token: String) {
        if (state.value.courseSaving || scheduleController.state.value.courseUndo?.token != token) return
        mutable.update { it.copy(courseSaving = true) }
        viewModelScope.launch {
            try {
                scheduleController.undoCourse(token)
                mutable.update { it.copy(message = "已撤销课程修改") }
            } catch (error: CancellationException) { throw error }
            catch (error: CourseValidationException) { mutable.update { it.copy(message = error.message) } }
            catch (_: ScheduleException) { mutable.update { it.copy(message = "撤销未完成，请检查当前课表") } }
            finally {
                scheduleController.dismissCourseUndo(token)
                mutable.update { it.copy(courseSaving = false) }
            }
        }
    }

    private val billingController = BillingController(viewModelScope,
        credentials = { id -> withContext(Dispatchers.IO) { repository.credentialsForLogin(id) } },
        publish = { billing -> mutable.update { it.copy(billing = billing) } },
        storageFailure = {
            runtime.storageUnavailable()
            mutable.update { it.copy(storageBlocked = true, editor = null, editorDraft = null, discardAccountChanges = false, editorError = null) }
        },
        beforeOffline = loginController::deviceOfflineRequested,
        awaitLoginStopped = loginController::awaitStopped)

    init {
        loginController.awaitAccountReadiness()
        reload()
        viewModelScope.launch { repository.state.collect { data ->
            if (data != null) mutable.update { it.copy(data = repository.state.value ?: data) }
        } }
        viewModelScope.launch { scheduleController.state.collect { schedule ->
            if (schedule.courseUndo == null) courseUndoTimer.clear()
            mutable.update { it.copy(schedule = schedule) }
        } }
        viewModelScope.launch { loginController.state.collect { login -> mutable.update { it.copy(login = login) } } }
        viewModelScope.launch { runtime.storageBlocked.collect { blocked ->
            if (blocked) mutable.update { it.copy(storageBlocked = true, editor = null, editorDraft = null, discardAccountChanges = false, editorError = null) }
        } }
        viewModelScope.launch { runtime.connections.collect(::billingWifiObserved) }
        viewModelScope.launch {
            state.collect {
                billingController.accountChanged(if (it.loading || it.storageBlocked) null else it.data.defaultAccountId)
                if (!it.loading) loginController.configure(ready = !it.storageBlocked,
                    automatic = it.data.settings.autoLogin && it.data.defaultAccount != null,
                    mode = it.data.settings.networkMode, accountId = it.data.defaultAccountId)
            }
        }
    }

    private fun billingWifiObserved(observation: WifiObservation) {
        when (observation) {
            WifiObservation.Suspended -> billingController.wifiObservationPaused()
            is WifiObservation.Current -> billingController.wifiChanged(observation.connection)
        }
    }

    fun enterForeground() {
        runtime.enterForeground()
        billingWifiObserved(runtime.connections.value)
        billingController.enterForeground()
        runtime.scheduleImport.enterForeground()
    }
    fun leaveForeground() {
        runtime.scheduleImport.leaveForeground()
        runtime.leaveForeground(); billingController.leaveForeground()
    }
    fun login() { if (!state.value.busy && state.value.editor == null) loginController.login() }
    fun cancelLogin() = loginController.cancel()
    fun billingVisible(visible: Boolean) = billingController.setVisible(visible)
    fun queryBilling(code: String? = null) {
        if (!state.value.busy && !state.value.storageBlocked && state.value.editor == null) billingController.query(code)
    }
    fun requestOffline(id: String) { if (!state.value.busy) billingController.requestOffline(id) }
    fun confirmOffline() { if (!state.value.busy) billingController.confirmOffline() }
    fun dismissOffline() = billingController.dismissOffline()
    fun dismissWebOffer() = billingController.dismissWebOffer()
    fun openBillingWeb() { if (!state.value.busy && !state.value.storageBlocked) billingController.openWeb() }
    fun submitBillingWeb(token: String, password: String, code: String) = billingController.submitWeb(token, password, code)
    fun refreshBillingWebCaptcha(token: String, display: (ByteArray) -> Unit) = billingController.refreshWebCaptcha(token, display)
    fun closeBillingWeb() = billingController.closeWeb()
    fun unsupportedBillingWeb() = billingController.unsupportedWeb()

    fun reload() {
        if (state.value.busy) return
        runtime.accountNames.storageAvailable(false)
        runtime.accountNames.editingAccount(null)
        mutable.update { it.copy(loading = true, busy = true, editor = null, editorDraft = null, discardAccountChanges = false, editorError = null) }
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { repository.load() }
                runtime.storageReloaded()
                mutable.update { it.copy(data = repository.state.value ?: data, loading = false, busy = false, storageBlocked = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                runtime.storageUnavailable()
                mutable.update { it.copy(loading = false, busy = false, storageBlocked = true) }
            }
        }
    }

    fun addAccount() {
        if (!state.value.busy && !state.value.storageBlocked) {
            val draft = AccountDraft(label = "", username = "", password = "")
            mutable.update { it.copy(editor = draft, editorDraft = draft, discardAccountChanges = false, editorError = null) }
        }
    }

    fun editAccount(id: String) {
        if (state.value.busy) return
        loginController.accountWillChange(id)
        runtime.accountNames.editingAccount(id)
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val draft = withContext(Dispatchers.IO) { repository.edit(id) }
                mutable.update { it.copy(busy = false, editor = draft, editorDraft = draft, discardAccountChanges = false, editorError = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: VaultUnavailableException) {
                runtime.storageUnavailable()
                runtime.accountNames.editingAccount(null)
                mutable.update { it.copy(busy = false, storageBlocked = true, editor = null, editorDraft = null, discardAccountChanges = false, editorError = null, message = null) }
            } catch (_: Exception) {
                runtime.accountNames.editingAccount(null)
                mutable.update { it.copy(busy = false, message = "账号暂时无法读取，请重试") }
            }
        }
    }

    fun updateAccountDraft(draft: AccountDraft) {
        if (state.value.busy || state.value.editor?.id != draft.id || state.value.editor == null) return
        mutable.update { it.copy(editorDraft = draft, editorError = null) }
    }
    fun requestCloseEditor() {
        if (state.value.busy) return
        val original = state.value.editor ?: return
        if (accountDraftChanged(original, state.value.editorDraft ?: original))
            mutable.update { it.copy(discardAccountChanges = true) }
        else closeEditor()
    }
    fun continueAccountEditing() = mutable.update { it.copy(discardAccountChanges = false) }
    fun closeEditor() { if (!state.value.busy) {
        mutable.update { it.copy(editor = null, editorDraft = null, discardAccountChanges = false, editorError = null) }
        runtime.accountNames.editingAccount(null)
    } }
    fun clearMessage() = mutable.update { it.copy(message = null) }

    fun saveAccount(draft: AccountDraft) {
        val original = state.value.editor ?: return
        if (state.value.busy || state.value.storageBlocked || state.value.discardAccountChanges ||
            draft != state.value.editorDraft || !accountDraftCanSave(original, draft)) return
        draft.id?.let(loginController::accountWillChange)
        draft.id?.let(runtime.accountNames::accountWillChange)
        write("账号已加密保存", closeEditor = true, accountChange = true) { repository.save(draft) }
    }
    fun deleteAccount(id: String) {
        loginController.accountWillChange(id)
        runtime.accountNames.accountWillChange(id)
        write("账号已删除", accountChange = true) { repository.delete(id) }
    }
    fun setDefault(id: String) = write("默认账号已更新，将用于后续登录", accountChange = true) { repository.setDefault(id) }
    fun updateSettings(settings: AppSettings) = write("设置已保存") { repository.settings(settings) }
    fun resetStorage() {
        loginController.accountWillChange(null)
        runtime.accountNames.accountWillChange(null)
        write("本机账号已清除，请重新添加", recovery = true, accountChange = true) { repository.reset() }
    }

    private fun write(
        success: String,
        closeEditor: Boolean = false,
        recovery: Boolean = false,
        accountChange: Boolean = false,
        action: () -> AccountState,
    ) {
        if (state.value.busy || (state.value.storageBlocked && !recovery)) return
        if (accountChange) billingController.accountChanged(state.value.data.defaultAccountId, force = true)
        mutable.update { it.copy(busy = true, editorError = null) }
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { action() }
                runtime.storageReloaded()
                mutable.update { it.copy(data = repository.state.value ?: data, busy = false, storageBlocked = false,
                    editor = if (closeEditor) null else it.editor,
                    editorDraft = if (closeEditor) null else it.editorDraft,
                    discardAccountChanges = if (closeEditor) false else it.discardAccountChanges, editorError = null, message = success) }
                if (closeEditor) runtime.accountNames.editingAccount(null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: AccountValidationException) {
                mutable.update { if (it.editor != null) it.copy(busy = false, editorError = error.message)
                    else it.copy(busy = false, message = error.message) }
            } catch (_: VaultUnavailableException) {
                runtime.storageUnavailable()
                runtime.accountNames.editingAccount(null)
                mutable.update { it.copy(busy = false, storageBlocked = true, editor = null, editorDraft = null, discardAccountChanges = false, editorError = null, message = null) }
            } catch (_: Exception) {
                val message = if (recovery) "清除未完成，请重试读取并检查存储状态" else "保存失败，请重试；未显示保存成功"
                mutable.update { if (it.editor != null) it.copy(busy = false, editorError = message)
                    else it.copy(busy = false, message = message) }
            } finally {
                runtime.accountNames.accountChangeFinished()
            }
        }
    }
}
