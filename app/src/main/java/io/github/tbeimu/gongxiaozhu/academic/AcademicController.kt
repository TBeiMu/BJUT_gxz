package io.github.tbeimu.gongxiaozhu.academic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private const val ACADEMIC_QUERY_TIMEOUT_MS = 90_000L

data class AcademicUndo(val token: String)
data class AcademicUiState(
    val data: AcademicData = AcademicData(), val loading: Boolean = true, val blocked: Boolean = false,
    val busy: Boolean = false, val saving: Boolean = false, val login: AcademicLoginPage? = null,
    val message: String? = null, val examMessage: String? = null, val gradeMessage: String? = null,
    val networkAvailable: Boolean = false, val networkLabel: String? = null,
    val undo: AcademicUndo? = null, val editCompleted: Long = 0,
    val request: AcademicRequest? = null, val loginDraft: AcademicLoginDraft? = null,
    val pendingIdentity: AcademicIdentity? = null, val credentialBusy: Boolean = false,
    val rememberedAccount: String? = null, val credentialMessage: String? = null,
    val committing: Boolean = false,
) {
    override fun toString() = "AcademicUiState(loading=$loading, busy=$busy, blocked=$blocked)"
}

class AcademicConnection(val identity: String, val title: String, val session: () -> AcademicSession) {
    override fun toString() = "AcademicConnection(<bound>)"
}

/** Foreground-only session. Cancellation invalidates writes before awaiting transport cleanup. */
class AcademicController(
    private val scope: CoroutineScope, private val repository: AcademicRepository,
    private val networks: Flow<AcademicConnection?>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val credentials: AcademicCredentialsRepository? = null,
    private val defaultUsername: () -> String = { "" },
) {
    private val mutable = MutableStateFlow(AcademicUiState())
    val state = mutable.asStateFlow()
    @Volatile private var foreground = false
    private var monitor: Job? = null
    private var reloadJob: Job? = null
    @Volatile private var connection: AcademicConnection? = null
    @Volatile private var task: Task? = null
    private var undoState: UndoState? = null
    @Volatile private var credentialEpoch = 0L
    private enum class QueryStage { CANCELLABLE, COMMITTING, CANCELLED }
    private class Task(val connection: String, val session: AcademicSession, val request: AcademicRequest, val revision: Long) {
        val stage = AtomicReference(QueryStage.CANCELLABLE)
        var job: Job? = null
        val cleanup = Mutex()
        var cleaned = false
        var remembered: AcademicCredentials? = null
        var submitted: AcademicLoginDraft? = null
        var identity: AcademicIdentity? = null
    }
    private class UndoState(val token: String, val before: AcademicData, val after: AcademicData, var deadline: Long? = null)

    init {
        scope.launch {
            repository.state.filterNotNull().collect {
                mutable.update { ui ->
                    val latest = repository.state.value
                    if (ui.committing || latest == null) ui else ui.copy(data = latest)
                }
            }
        }
        reload()
    }

    fun reload() {
        if (task != null || state.value.busy || state.value.saving || state.value.credentialBusy || reloadJob?.isActive == true) return
        dismissUndo()
        mutable.update { it.copy(loading = true, message = null) }
        reloadJob = scope.launch {
            val data = try {
                withContext(io) { repository.load() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            try {
                val account = withContext(io) { credentials?.load()?.username }
                mutable.update { it.copy(rememberedAccount = account, credentialMessage = null) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.update { it.copy(rememberedAccount = null, credentialMessage = "记住的密码暂不可用，可手动登录或清除后重试") }
            }
            mutable.update { it.copy(data = data ?: it.data, loading = false, blocked = data == null,
                message = if (data == null) AcademicProblem.STORAGE.hint else null) }
        }
    }

    fun enterForeground() {
        foreground = true
        if (monitor != null) return
        monitor = scope.launch {
            try { networks.collect(::networkChanged) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { networkChanged(null) }
        }
    }
    fun leaveForeground() {
        foreground = false
        monitor?.cancel(); monitor = null
        connection = null
        mutable.update { it.copy(networkAvailable = false, networkLabel = null) }
        dismissUndo()
        cancel("已暂停查询，返回后请手动重新登录")
    }
    private fun networkChanged(next: AcademicConnection?) {
        if (!foreground) return
        if (connection?.identity != next?.identity && task != null) cancel("网络已变化，请手动重新登录")
        connection = next
        mutable.update { it.copy(networkAvailable = next != null, networkLabel = next?.title) }
    }
    private fun live(t: Task) = task === t && t.stage.get() == QueryStage.CANCELLABLE && foreground && connection?.identity == t.connection
    private fun requireLive(t: Task) { if (!live(t)) throw CancellationException("教务任务已取消") }

    fun start(target: AcademicQueryTarget = AcademicQueryTarget.EXAMS, intent: AcademicQueryIntent = AcademicQueryIntent.IMPORT) {
        if (task != null || state.value.loading || state.value.blocked || state.value.saving || state.value.credentialBusy || !foreground) return
        if (intent == AcademicQueryIntent.REFRESH && when (target) {
            AcademicQueryTarget.EXAMS -> state.value.data.examUpdatedAt == null
            AcademicQueryTarget.GRADES -> state.value.data.grades == null
        }) return
        val route = connection ?: run { mutable.update { it.copy(message = "请先连接可上网的 WiFi 或移动数据") }; return }
        dismissUndo()
        val request = AcademicRequest(target, intent)
        val t = try { Task(route.identity, route.session(), request, state.value.data.revision) } catch (_: Exception) {
            mutable.update { it.copy(message = AcademicProblem.CONNECTION.hint) }; return
        }
        task = t
        val username = runCatching(defaultUsername).getOrDefault("").trim().take(128)
        mutable.update { it.copy(busy = true, request = request, message = null, credentialMessage = null,
            loginDraft = AcademicLoginDraft(username)) }
        phase(t, keepSession = true) {
            try { t.remembered = withContext(io) { credentials?.load() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (live(t)) mutable.update { it.copy(credentialMessage = "无法读取记住的密码，请手动填写") } }
            requireLive(t)
            val remembered = t.remembered?.takeIf { it.matches(username) }
            mutable.update { it.copy(loginDraft = AcademicLoginDraft(username, remembered?.password.orEmpty(),
                rememberPassword = remembered != null), rememberedAccount = t.remembered?.username) }
            val page = withTimeout(ACADEMIC_QUERY_TIMEOUT_MS) { t.session.open() }
            requireLive(t)
            mutable.update { it.copy(login = page, busy = false) }
        }
    }
    fun updateLoginDraft(value: AcademicLoginDraft) {
        val t = task ?: return
        val previous = state.value.loginDraft ?: return
        if (!live(t) || state.value.login == null || state.value.busy || state.value.credentialBusy) return
        val next = value.copy(username = value.username.take(128), password = value.password.take(512), captcha = value.captcha.take(32))
        if (previous.username != next.username) {
            val remembered = t.remembered?.takeIf { it.matches(next.username) }
            mutable.update { it.copy(loginDraft = next.copy(password = remembered?.password.orEmpty(), rememberPassword = remembered != null)) }
        } else {
            mutable.update { it.copy(loginDraft = next) }
            if (previous.rememberPassword && !next.rememberPassword && t.remembered?.matches(next.username) == true) forgetPassword()
        }
    }

    fun forgetPassword() {
        if (state.value.loading || state.value.busy || state.value.saving || state.value.credentialBusy) return
        val epoch = ++credentialEpoch
        val t = task
        val remembered = t?.remembered
        t?.remembered = null
        mutable.update { it.copy(credentialBusy = true, credentialMessage = null,
            loginDraft = it.loginDraft?.copy(rememberPassword = false)) }
        scope.launch {
            try {
                withContext(io) { credentials?.clear { credentialEpoch == epoch } }
                if (credentialEpoch == epoch) mutable.update { it.copy(rememberedAccount = null, credentialMessage = null,
                    message = if (it.blocked) "已清除记住的教务密码" else it.message) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (t != null && live(t)) t.remembered = remembered
                mutable.update { it.copy(credentialMessage = "密码未能清除，请重试",
                    loginDraft = it.loginDraft?.let { draft -> draft.copy(rememberPassword = remembered?.matches(draft.username) == true) }) }
            } finally { if (credentialEpoch == epoch) mutable.update { it.copy(credentialBusy = false) } }
        }
    }

    fun submit(token: String, username: String, password: String, captcha: String) {
        val t = task ?: return
        if (!live(t) || state.value.busy || state.value.credentialBusy || state.value.login?.token != token ||
            username.trim().isEmpty() || username.trim().length > 128 || password.length !in 1..512 || captcha.trim().length !in 1..32) return
        t.submitted = AcademicLoginDraft(username.trim(), password, captcha.trim(), state.value.loginDraft?.rememberPassword == true)
        mutable.update { it.copy(busy = true, message = null) }
        phase(t, keepSession = true) {
            val result = withTimeout(ACADEMIC_QUERY_TIMEOUT_MS) {
                val identity = t.session.submit(token, username.trim(), password, captcha.trim())
                requireLive(t)
                t.identity = identity
                if (state.value.data.identity?.studentId?.let { it != identity.studentId } == true) {
                    mutable.update { it.copy(login = null, loginDraft = null, pendingIdentity = identity, busy = false) }
                    null
                } else {
                    fetchQueryResult(t)
                }
            }
            if (result != null) commitQuery(t, result, allowAccountChange = false)
        }
    }

    fun confirmAccountChange() {
        val t = task ?: return
        if (!live(t) || state.value.busy || state.value.credentialBusy || state.value.pendingIdentity == null) return
        mutable.update { it.copy(busy = true, pendingIdentity = null) }
        phase(t) {
            val result = withTimeout(ACADEMIC_QUERY_TIMEOUT_MS) { fetchQueryResult(t) }
            commitQuery(t, result, allowAccountChange = true)
        }
    }

    private suspend fun fetchQueryResult(t: Task): AcademicQueryResult {
        requireLive(t)
        mutable.update { it.copy(busy = true, login = null, loginDraft = null, pendingIdentity = null,
            examMessage = if (t.request.target == AcademicQueryTarget.EXAMS) "正在查询" else it.examMessage,
            gradeMessage = if (t.request.target == AcademicQueryTarget.GRADES) "正在查询" else it.gradeMessage) }
        return when (t.request.target) {
            AcademicQueryTarget.EXAMS -> AcademicQueryResult.Exams(t.session.exams(), now())
            AcademicQueryTarget.GRADES -> AcademicQueryResult.Grades(t.session.grades(now()))
        }
    }

    private suspend fun commitQuery(t: Task, result: AcademicQueryResult, allowAccountChange: Boolean) {
        requireLive(t)
        val identity = t.identity ?: throw AcademicException(AcademicProblem.IDENTITY)
        val draft = t.submitted ?: throw AcademicException(AcademicProblem.LOGIN)
        val after = withContext(io) {
            repository.commitQuery(identity, result, t.revision, allowAccountChange, beginCommit = {
                // Cancellation and publication of the encrypted snapshot share this decision.
                if (!t.stage.compareAndSet(QueryStage.CANCELLABLE, QueryStage.COMMITTING)) {
                    throw CancellationException("教务任务已取消")
                }
                mutable.update { it.copy(committing = true) }
            }) { live(t) }
        }
        mutable.update { it.copy(data = after,
            message = "${t.request.target.title}${if (t.request.intent == AcademicQueryIntent.IMPORT) "导入" else "刷新"}成功",
            examMessage = if (t.request.target == AcademicQueryTarget.EXAMS) "更新成功" else if (allowAccountChange) null else it.examMessage,
            gradeMessage = if (t.request.target == AcademicQueryTarget.GRADES) "更新成功" else if (allowAccountChange) null else it.gradeMessage) }
        try {
            if (draft.rememberPassword && credentials != null) {
                withContext(io) { credentials.save(AcademicCredentials(identity.studentId, draft.username, draft.password)) }
                mutable.update { it.copy(rememberedAccount = draft.username) }
            } else if (allowAccountChange) {
                withContext(io) { credentials?.clear() }
                mutable.update { it.copy(rememberedAccount = null) }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            val hint = if (draft.rememberPassword) "教务密码未能保存，请重试"
                else "原教务密码未能清除，请在教务账号中重试"
            mutable.update { it.copy(credentialMessage = hint) }
        }
    }
    fun refreshCaptcha(token: String) {
        val t = task ?: return
        if (!live(t) || state.value.busy || state.value.credentialBusy || state.value.login?.token != token) return
        mutable.update { it.copy(busy = true, message = null) }
        phase(t, keepSession = true) {
            val page = withTimeout(ACADEMIC_QUERY_TIMEOUT_MS) { t.session.refreshCaptcha(token) }
            requireLive(t)
            mutable.update { it.copy(login = page, busy = false, loginDraft = it.loginDraft?.copy(captcha = "")) }
        }
    }
    private fun phase(t: Task, keepSession: Boolean = false, block: suspend () -> Unit) {
        t.job = scope.launch {
            var keep = false
            try {
                block()
                keep = keepSession && live(t) && (state.value.login != null || state.value.pendingIdentity != null)
            } catch (_: TimeoutCancellationException) {
                fail(t, "查询超时，原有资料保留，请重试")
            } catch (e: CancellationException) { throw e }
            catch (e: AcademicException) {
                fail(t, e.problem.hint, e.problem == AcademicProblem.STORAGE)
            } catch (e: AcademicValidationException) { fail(t, e.message ?: "资料已变化，请重新操作") }
            catch (_: Exception) { fail(t, AcademicProblem.CONNECTION.hint) }
            finally { if (!keep) finish(t) }
        }
    }
    private fun fail(t: Task, hint: String, blocked: Boolean = false) {
        if (live(t) || (task === t && t.stage.get() == QueryStage.COMMITTING)) {
            mutable.update { it.copy(message = hint, blocked = it.blocked || blocked,
                examMessage = if (t.request.target == AcademicQueryTarget.EXAMS) "查询失败" else it.examMessage,
                gradeMessage = if (t.request.target == AcademicQueryTarget.GRADES) "查询失败" else it.gradeMessage) }
        }
    }
    fun cancel() = cancel(null)
    private fun cancel(message: String?) {
        val t = task ?: return
        if (!t.stage.compareAndSet(QueryStage.CANCELLABLE, QueryStage.CANCELLED)) {
            if (t.stage.get() == QueryStage.CANCELLED) {
                val job = t.job
                scope.launch { job?.join(); finish(t) }
            }
            return
        }
        val job = t.job
        t.submitted = null; t.remembered = null
        mutable.update { it.copy(login = null, loginDraft = null, pendingIdentity = null, busy = true, message = message ?: it.message,
            examMessage = it.examMessage.takeUnless { m -> m == "正在查询" },
            gradeMessage = it.gradeMessage.takeUnless { m -> m == "正在查询" || m == "等待查询" }) }
        // Main.immediate can synchronously finish a cancelled phase; publish cancellation first.
        t.session.close()
        job?.cancel()
        scope.launch { job?.join(); finish(t) }
    }
    private suspend fun finish(t: Task) = withContext(NonCancellable) {
        t.cleanup.withLock {
            if (!t.cleaned) {
                t.session.close()
                try { t.session.awaitClosed() } catch (_: Exception) {
                    // Unknown cleanup status cannot authorize another session. Cancel can retry cleanup.
                    t.stage.set(QueryStage.CANCELLED)
                    if (task === t) mutable.update { it.copy(login = null, loginDraft = null, pendingIdentity = null,
                        busy = true, committing = false, message = "请求清理尚未完成，请稍后取消后重试") }
                    return@withLock
                }
                t.cleaned = true
                t.remembered = null; t.submitted = null; t.identity = null
                if (task === t) {
                    task = null
                    mutable.update { it.copy(login = null, loginDraft = null, pendingIdentity = null,
                        request = null, busy = false, committing = false) }
                }
            }
        }
    }

    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun beginEdit() = dismissUndo()
    private fun canWrite() = task == null && !state.value.loading && !state.value.blocked && !state.value.busy && !state.value.saving && !state.value.credentialBusy
    private fun mutate(withUndo: Boolean, edit: Boolean = false, operation: suspend () -> AcademicData) {
        if (!canWrite()) return
        val before = state.value.data
        dismissUndo()
        mutable.update { it.copy(saving = true, message = null) }
        scope.launch {
            try {
                val after = withContext(io) { operation() }
                if (withUndo && before != after && foreground) {
                    val token = UUID.randomUUID().toString()
                    undoState = UndoState(token, before, after)
                    mutable.update { it.copy(undo = AcademicUndo(token)) }
                }
                mutable.update { it.copy(data = after, editCompleted = it.editCompleted + if (edit) 1 else 0) }
            } catch (e: CancellationException) { throw e }
            catch (e: AcademicValidationException) { mutable.update { it.copy(message = e.message) } }
            catch (e: IllegalArgumentException) { mutable.update { it.copy(message = e.message?.take(160) ?: "请检查考试名称和起止时间") } }
            catch (e: AcademicException) { mutable.update { it.copy(message = e.problem.hint, blocked = it.blocked || e.problem == AcademicProblem.STORAGE) } }
            catch (_: Exception) { mutable.update { it.copy(message = AcademicProblem.STORAGE.hint) } }
            finally { mutable.update { it.copy(saving = false) } }
        }
    }
    fun saveExam(id: String?, fields: ExamFields, expectedRevision: Long) = mutate(id != null, edit = true) {
        repository.saveExam(id, fields, expectedRevision)
    }
    fun deleteExam(id: String, expectedRevision: Long) = mutate(true) { repository.deleteExam(id, expectedRevision) }
    fun restoreExam(id: String, expectedRevision: Long) = mutate(true) { repository.restoreExam(id, expectedRevision) }
    fun unbind(expectedRevision: Long) = mutate(false) {
        val epoch = ++credentialEpoch
        try { credentials?.clear { credentialEpoch == epoch } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw AcademicValidationException("密码未能清除，请重试解除绑定") }
        mutable.update { it.copy(rememberedAccount = null, credentialMessage = null) }
        repository.unbind(expectedRevision)
    }
    fun dismissUndo(token: String? = null) {
        if (token == null || token == undoState?.token) { undoState = null; mutable.update { it.copy(undo = null) } }
    }
    fun undoRemaining(token: String, timeout: Long): Long? {
        val action = undoState?.takeIf { it.token == token } ?: return null
        if (action.deadline == null) action.deadline = elapsed() + timeout.coerceAtLeast(10_000)
        return (action.deadline!! - elapsed()).coerceAtLeast(0)
    }
    fun undo(token: String) {
        val action = undoState?.takeIf { it.token == token } ?: return
        if (!foreground || !canWrite() || action.deadline == null || elapsed() >= action.deadline!!) { dismissUndo(token); return }
        mutable.update { it.copy(saving = true, undo = null, message = null) }
        scope.launch {
            try {
                val data = withContext(io) { repository.restoreSnapshot(action.after, action.before) {
                    undoState === action && foreground && elapsed() < action.deadline!!
                } }
                mutable.update { it.copy(data = data, message = "已撤销考试修改") }
            } catch (_: CancellationException) { /* Retired undo leaves the current data intact. */ }
            catch (e: AcademicValidationException) { mutable.update { it.copy(message = e.message) } }
            catch (_: Exception) { mutable.update { it.copy(message = "撤销未完成，请检查当前考试资料") } }
            finally { dismissUndo(token); mutable.update { it.copy(saving = false) } }
        }
    }
}
