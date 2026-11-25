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

data class AcademicUndo(val token: String)
data class AcademicUiState(
    val data: AcademicData = AcademicData(), val loading: Boolean = true, val blocked: Boolean = false,
    val busy: Boolean = false, val saving: Boolean = false, val login: AcademicLoginPage? = null,
    val message: String? = null, val examMessage: String? = null, val gradeMessage: String? = null,
    val networkAvailable: Boolean = false, val networkLabel: String? = null,
    val undo: AcademicUndo? = null, val editCompleted: Long = 0,
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
) {
    private val mutable = MutableStateFlow(AcademicUiState())
    val state = mutable.asStateFlow()
    @Volatile private var foreground = false
    private var monitor: Job? = null
    @Volatile private var connection: AcademicConnection? = null
    @Volatile private var task: Task? = null
    private var undoState: UndoState? = null
    private class Task(val connection: String, val session: AcademicSession) {
        @Volatile var invalid = false
        var job: Job? = null
        val cleanup = Mutex()
        var cleaned = false
    }
    private class UndoState(val token: String, val before: AcademicData, val after: AcademicData, var deadline: Long? = null)

    init {
        scope.launch { repository.state.filterNotNull().collect { data -> mutable.update { it.copy(data = data) } } }
        reload()
    }

    fun reload() {
        if (state.value.busy || state.value.saving) return
        dismissUndo()
        mutable.update { it.copy(loading = true, message = null) }
        scope.launch {
            try {
                val data = withContext(io) { repository.load() }
                mutable.update { it.copy(data = data, loading = false, blocked = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(loading = false, blocked = true, message = AcademicProblem.STORAGE.hint) } }
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
    private fun live(t: Task) = task === t && !t.invalid && foreground && connection?.identity == t.connection
    private fun requireLive(t: Task) { if (!live(t)) throw CancellationException("教务任务已取消") }

    fun start() {
        if (task != null || state.value.loading || state.value.blocked || state.value.saving || !foreground) return
        val route = connection ?: run { mutable.update { it.copy(message = "请先连接可上网的 WiFi 或移动数据") }; return }
        dismissUndo()
        val t = try { Task(route.identity, route.session()) } catch (_: Exception) {
            mutable.update { it.copy(message = AcademicProblem.CONNECTION.hint) }; return
        }
        task = t
        mutable.update { it.copy(busy = true, message = null, examMessage = null, gradeMessage = null) }
        phase(t, keepSession = true) {
            val page = t.session.open()
            requireLive(t)
            mutable.update { it.copy(login = page, busy = false) }
        }
    }
    fun submit(token: String, username: String, password: String, captcha: String) {
        val t = task ?: return
        if (!live(t) || state.value.busy || state.value.login?.token != token ||
            username.trim().isEmpty() || password.isEmpty() || captcha.trim().isEmpty()) return
        mutable.update { it.copy(busy = true, message = null) }
        phase(t) {
            val identity = t.session.submit(token, username.trim(), password, captcha.trim())
            requireLive(t)
            // Binding is derived only from a school response, never from the typed identifier.
            val old = state.value.data
            if (old.identity != null && old.identity.studentId != identity.studentId)
                throw AcademicException(AcademicProblem.ACCOUNT_MISMATCH)
            withContext(io) {
                requireLive(t)
                repository.bind(identity, old.revision, isCurrent = { live(t) })
            }
            requireLive(t)
            mutable.update { it.copy(login = null, examMessage = "正在查询", gradeMessage = "等待查询") }
            var succeeded = 0
            try {
                val exams = t.session.exams()
                requireLive(t)
                withContext(io) { repository.saveExams(identity, exams, now(), isCurrent = { live(t) }) }
                requireLive(t)
                succeeded++
                mutable.update { it.copy(examMessage = "查询成功") }
            } catch (e: CancellationException) { throw e }
            catch (e: AcademicException) {
                requireLive(t)
                mutable.update { it.copy(examMessage = e.problem.hint) }
                if (e.problem == AcademicProblem.STORAGE) throw e
            } catch (_: Exception) {
                requireLive(t)
                mutable.update { it.copy(examMessage = AcademicProblem.CONNECTION.hint) }
            }
            requireLive(t)
            mutable.update { it.copy(gradeMessage = "正在查询") }
            try {
                val grades = t.session.grades(now())
                requireLive(t)
                withContext(io) { repository.saveGrades(identity, grades, isCurrent = { live(t) }) }
                requireLive(t)
                succeeded++
                mutable.update { it.copy(gradeMessage = "查询成功") }
            } catch (e: CancellationException) { throw e }
            catch (e: AcademicException) {
                requireLive(t)
                mutable.update { it.copy(gradeMessage = e.problem.hint) }
                if (e.problem == AcademicProblem.STORAGE) throw e
            } catch (_: Exception) {
                requireLive(t)
                mutable.update { it.copy(gradeMessage = AcademicProblem.CONNECTION.hint) }
            }
            requireLive(t)
            mutable.update { it.copy(message = when (succeeded) {
                2 -> "考试与成绩查询成功"
                1 -> "部分查询完成，未完成项目保留上次数据"
                else -> "查询未完成，已保留上次数据"
            }) }
        }
    }
    fun refreshCaptcha(token: String) {
        val t = task ?: return
        if (!live(t) || state.value.busy || state.value.login?.token != token) return
        mutable.update { it.copy(busy = true, message = null) }
        phase(t, keepSession = true) {
            val page = t.session.refreshCaptcha(token)
            requireLive(t)
            mutable.update { it.copy(login = page, busy = false) }
        }
    }
    private fun phase(t: Task, keepSession: Boolean = false, block: suspend () -> Unit) {
        t.job = scope.launch {
            var keep = false
            try {
                withTimeout(90_000) { block() }
                keep = keepSession && live(t)
            } catch (_: TimeoutCancellationException) {
                if (live(t)) mutable.update { it.copy(message = "教务查询超时，请手动重试；原有数据保留",
                    examMessage = it.examMessage.takeUnless { m -> m == "正在查询" },
                    gradeMessage = it.gradeMessage.takeUnless { m -> m == "正在查询" || m == "等待查询" }) }
            } catch (e: CancellationException) { throw e }
            catch (e: AcademicException) {
                if (live(t)) mutable.update { it.copy(message = e.problem.hint, blocked = it.blocked || e.problem == AcademicProblem.STORAGE) }
            } catch (_: Exception) { if (live(t)) mutable.update { it.copy(message = AcademicProblem.CONNECTION.hint) } }
            finally { if (!keep) finish(t) }
        }
    }
    fun cancel() = cancel(null)
    private fun cancel(message: String?) {
        val t = task ?: return
        t.invalid = true
        val job = t.job
        mutable.update { it.copy(login = null, busy = true, message = message ?: it.message,
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
                    if (task === t) mutable.update { it.copy(login = null, busy = true, message = "请求清理尚未完成，请稍后取消后重试") }
                    return@withLock
                }
                t.cleaned = true
                if (task === t) { task = null; mutable.update { it.copy(login = null, busy = false) } }
            }
        }
    }

    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun beginEdit() = dismissUndo()
    private fun canWrite() = task == null && !state.value.loading && !state.value.blocked && !state.value.busy && !state.value.saving
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
    fun unbind(expectedRevision: Long) = mutate(false) { repository.unbind(expectedRevision) }
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
