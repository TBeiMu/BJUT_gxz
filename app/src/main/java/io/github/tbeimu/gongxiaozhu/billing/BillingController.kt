package io.github.tbeimu.gongxiaozhu.billing

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.VaultUnavailableException
import io.github.tbeimu.gongxiaozhu.network.WifiConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OfflineConfirmation(val device: OnlineDevice, val tip: String) {
    override fun toString() = "OfflineConfirmation(device=<redacted>)"
}

data class BillingUiState(
    val running: Boolean = false,
    val snapshot: BillingSnapshot? = null,
    val message: String = "",
    val captcha: ByteArray? = null,
    val confirmation: OfflineConfirmation? = null,
    val loadedAt: Long? = null,
    val diagnostics: List<BillingDiagnostic> = emptyList(),
    val offerWeb: Boolean = false,
    val webPage: BillingWebPage? = null,
    val queryFailed: Boolean = false,
    val snapshotFromCache: Boolean = false,
    val devicesActionable: Boolean = true,
) {
    override fun toString() = "BillingUiState(running=$running, data=<redacted>)"
}

/** UI-thread controller; invalidates responses and confirmations when account/network changes. */
class BillingController(
    private val scope: CoroutineScope,
    private val credentials: suspend (String) -> AccountDraft?,
    private val publish: (BillingUiState) -> Unit,
    private val storageFailure: () -> Unit,
    private val beforeOffline: () -> Unit,
    private val awaitLoginStopped: suspend () -> Unit = {},
    private val cache: BillingCacheRepository? = null,
    private val cacheIo: CoroutineDispatcher = Dispatchers.IO,
) {
    private var state = BillingUiState(devicesActionable = false)
    private var accountId: String? = null
    private var wifi: WifiConnection? = null
    private var wifiObserved = false
    private var session: BillingSession? = null
    private var foreground = false
    private var visible = false
    private var attempted = false
    private var job: Job? = null
    private var generation = 0L
    private var offlineSent = false
    private var offlineConfirmed = false
    private var accountsReady = false
    private var accountSelectionKnown = false
    private var hadWifiSnapshot = false
    @Volatile private var cacheEpoch = 0L

    /** Loading accounts is not evidence that the saved account was deleted. */
    fun accountsLoading() {
        if (!accountsReady) return
        accountsReady = false
        reset("请登录查询", clearData = true, clearCache = false)
    }

    fun accountChanged(id: String?, force: Boolean = false) {
        val resumedRead = !accountsReady
        accountsReady = true
        if (accountSelectionKnown && accountId == id && !force) {
            if (resumedRead && id != null) { restoreCached(id); maybeQuery() }
            return
        }
        val initial = !accountSelectionKnown
        accountSelectionKnown = true
        val hadAccount = accountId != null
        accountId = id
        reset("请登录查询", clearData = true, clearCache = !initial || force || id == null)
        if (initial && !force && id != null) restoreCached(id)
        // Switching accounts is a deliberate action, but querying remains explicit.
        if (hadAccount || force) attempted = true else maybeQuery()
    }

    fun wifiChanged(connection: WifiConnection?) {
        val changed = wifi?.key != connection?.key
        val firstSnapshot = !hadWifiSnapshot
        hadWifiSnapshot = true
        wifiObserved = true
        wifi = connection
        // The saved figures belong to the account; only its live session belongs to this WiFi.
        if (changed && !firstSnapshot) reset(if (connection == null) "未连接 WiFi" else "网络已变，请重查",
            clearData = false, queryFailed = false)
        maybeQuery()
    }

    fun wifiObservationPaused() {
        wifiObserved = false
        // Retain the last display and network identity, but never reuse its query session.
        reset(if (state.running) "查询已暂停，请重查" else state.message, clearData = false)
    }

    fun enterForeground() {
        if (foreground) return
        foreground = true
        attempted = false
        maybeQuery()
    }

    fun leaveForeground() {
        foreground = false
        wifiObserved = false
        reset(if (offlineSent) BillingProblem.OFFLINE.hint
            else if (state.running) "查询已暂停，请重查" else state.message, clearData = false)
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (!value && (state.webPage != null || state.offerWeb)) reset("网页登录已关闭，请重查", false)
        if (value) maybeQuery()
    }

    private fun maybeQuery() {
        if (visible && foreground && accountsReady && wifiObserved && !attempted && accountId != null && wifi != null) query()
    }

    fun query(code: String? = null) {
        if (!foreground || !accountsReady || !wifiObserved || job?.isCompleted == false) return
        if (state.webPage != null) return
        attempted = true
        val id = accountId ?: run { emit(state.copy(message = "请先添加账号")); return }
        val selectedWifi = wifi ?: run { emit(state.copy(message = "未连接 WiFi")); return }
        val current = session ?: selectedWifi.billingSession?.invoke()?.also { session = it }
            ?: run { emit(state.copy(message = "当前 WiFi 无法查询")); return }
        val token = ++generation
        emit(state.copy(running = true, message = "正在查询", confirmation = null, offerWeb = false,
            queryFailed = false, devicesActionable = false))
        job = scope.launch {
            try {
                val selected = credentials(id) ?: throw BillingException(BillingProblem.REJECTED)
                val result = current.refresh(selected, code)
                if (token == generation) queriedSuccessfully(id, result, current)
            } catch (error: CancellationException) { throw error }
            catch (_: VaultUnavailableException) {
                if (token == generation) { reset("账号存储异常，查询停止", true); storageFailure() }
            } catch (error: BillingException) {
                if (token == generation) {
                    if (error.problem == BillingProblem.ACCOUNT_MISMATCH) {
                        invalidateAccountSession()
                        return@launch
                    }
                    if (error.problem == BillingProblem.EXPIRED) {
                        reset(error.problem.hint, clearData = true)
                        return@launch
                    }
                    emit(state.copy(running = false, message = error.problem.hint,
                        queryFailed = error.problem !in listOf(BillingProblem.CAPTCHA, BillingProblem.CAPTCHA_REJECTED, BillingProblem.UNCONFIRMED),
                        devicesActionable = false,
                        captcha = if (error.problem in listOf(BillingProblem.CAPTCHA, BillingProblem.CAPTCHA_REJECTED)) current.captcha else null,
                        confirmation = null, diagnostics = current.diagnostics,
                        offerWeb = error.problem in listOf(BillingProblem.REJECTED, BillingProblem.UNCONFIRMED) ||
                            (error.problem == BillingProblem.RESPONSE && current.diagnostics.any { it.page == "登录页" })))
                    if (error.problem !in listOf(BillingProblem.CAPTCHA, BillingProblem.CAPTCHA_REJECTED)) { current.close(); session = null }
                }
            } catch (_: Exception) {
                if (token == generation) { current.close(); session = null; emit(state.copy(running = false,
                    captcha = null, confirmation = null, queryFailed = true, devicesActionable = false,
                    message = BillingProblem.RESPONSE.hint)) }
            }
        }.also { task -> task.invokeOnCompletion { scope.launch { maybeQuery() } } }
    }

    fun dismissWebOffer() { emit(state.copy(offerWeb = false)) }

    fun openWeb() {
        if (!foreground || job?.isCompleted == false || !state.offerWeb) return
        val id = accountId ?: return
        val selectedWifi = wifi ?: return
        session?.close()
        val current = selectedWifi.billingSession?.invoke()?.also { session = it } ?: return
        val token = ++generation
        emit(state.copy(running = true, offerWeb = false, captcha = null, message = "正在准备登录页",
            queryFailed = false, devicesActionable = false))
        job = scope.launch {
            try {
                val selected = credentials(id) ?: throw BillingException(BillingProblem.REJECTED)
                val page = current.openWeb(selected)
                if (token == generation) emit(state.copy(running = false, webPage = page,
                    message = "账号密码已预填，请在学校页面点击登录；修改本次密码不会保存", diagnostics = current.diagnostics))
            } catch (error: CancellationException) { throw error }
            catch (_: VaultUnavailableException) { if (token == generation) { reset("账号存储异常，查询停止", true); storageFailure() } }
            catch (error: BillingException) { if (token == generation) webFailed(current, error.problem) }
            catch (_: Exception) { if (token == generation) webFailed(current, BillingProblem.RESPONSE) }
        }
    }

    fun submitWeb(pageToken: String, password: String, code: String) {
        if (!foreground || job?.isCompleted == false || state.webPage?.token != pageToken) return
        val current = session ?: return
        val token = ++generation
        emit(state.copy(running = true, message = "正在网页登录", queryFailed = false, devicesActionable = false))
        job = scope.launch {
            try {
                when (val result = current.submitWeb(pageToken, password, code)) {
                    is BillingWebResult.Success -> if (token == generation) accountId?.let { queriedSuccessfully(it, result.snapshot, current) }
                    is BillingWebResult.Continue -> if (token == generation) emit(state.copy(running = false,
                        webPage = result.page, queryFailed = false, devicesActionable = false,
                        message = result.problem.hint, diagnostics = current.diagnostics))
                }
            } catch (error: CancellationException) { throw error }
            catch (error: BillingException) { if (token == generation) webFailed(current, error.problem) }
            catch (_: Exception) { if (token == generation) webFailed(current, BillingProblem.RESPONSE) }
        }
    }

    fun refreshWebCaptcha(pageToken: String, display: (ByteArray) -> Unit) {
        if (!foreground || job?.isCompleted == false || state.webPage?.token != pageToken) return
        val current = session ?: return
        val token = ++generation
        emit(state.copy(running = true))
        job = scope.launch {
            try {
                val image = current.refreshWebCaptcha(pageToken)
                if (token == generation && state.webPage?.token == pageToken) { display(image); emit(state.copy(running = false, diagnostics = current.diagnostics)) }
            } catch (error: CancellationException) { throw error }
            catch (error: BillingException) { if (token == generation) webFailed(current, error.problem) }
            catch (_: Exception) { if (token == generation) webFailed(current, BillingProblem.RESPONSE) }
        }
    }

    fun closeWeb() { reset("网页登录已取消，请重查", clearData = false) }
    fun unsupportedWeb() { reset("内置登录不可用，请用网页", false) }

    private fun webFailed(current: BillingSession, problem: BillingProblem) {
        if (problem == BillingProblem.ACCOUNT_MISMATCH) {
            invalidateAccountSession()
            return
        }
        if (problem == BillingProblem.EXPIRED) {
            reset(problem.hint, clearData = true)
            return
        }
        emit(state.copy(running = false, webPage = null, offerWeb = false, captcha = null,
            queryFailed = problem !in listOf(BillingProblem.CAPTCHA, BillingProblem.CAPTCHA_REJECTED, BillingProblem.UNCONFIRMED), devicesActionable = false,
            message = problem.hint, diagnostics = current.diagnostics))
        current.close(); session = null
    }

    fun requestOffline(sessionId: String) {
        if (!foreground || !accountsReady || !wifiObserved || !state.devicesActionable ||
            state.snapshotFromCache || job?.isCompleted == false) return
        val device = state.snapshot?.devices?.find { it.sessionId == sessionId } ?: return
        val current = session ?: run { emit(state.copy(message = BillingProblem.EXPIRED.hint)); return }
        val token = ++generation
        emit(state.copy(running = true, confirmation = null, queryFailed = false, message = "正在读取注销提示"))
        job = scope.launch {
            try {
                val tip = current.offlineTip()
                if (token == generation) emit(state.copy(running = false, message = "请确认注销设备", confirmation = OfflineConfirmation(device, tip)))
            } catch (error: CancellationException) { throw error }
            catch (error: BillingException) { if (token == generation) {
                if (error.problem == BillingProblem.ACCOUNT_MISMATCH) invalidateAccountSession()
                else if (error.problem == BillingProblem.EXPIRED) invalidateExpiredSession()
                else emit(state.copy(running = false, message = error.problem.hint))
            } }
            catch (_: Exception) { if (token == generation) emit(state.copy(running = false, message = BillingProblem.RESPONSE.hint)) }
        }
    }

    fun dismissOffline() { emit(state.copy(confirmation = null)) }

    fun confirmOffline() {
        if (!foreground || !accountsReady || !wifiObserved || !state.devicesActionable ||
            state.snapshotFromCache || job?.isCompleted == false) return
        val confirmation = state.confirmation ?: return
        val current = session ?: return
        val id = accountId ?: return
        val token = ++generation
        val cacheCleared = invalidateCache()
        beforeOffline()
        attempted = true
        offlineSent = true
        emit(state.copy(running = true, confirmation = null, message = "正在注销设备", queryFailed = false))
        job = scope.launch {
            try {
                // Retire the historical device display before a possibly successful offline request.
                cacheCleared?.join()
                awaitLoginStopped()
                currentCoroutineContext().ensureActive()
                if (token != generation) return@launch
                try { current.offline(confirmation.device) }
                catch (error: BillingException) {
                    // The server may already have acted before its response was lost or malformed.
                    // A failure while waiting for authentication cleanup above is still distinct.
                    if (error.problem == BillingProblem.CONNECTION || error.problem == BillingProblem.RESPONSE)
                        throw BillingException(BillingProblem.OFFLINE)
                    throw error
                }
                if (token != generation) return@launch
                offlineConfirmed = true
                // A successful offline response is conclusive even if the current WiFi then disconnects.
                emit(state.copy(snapshot = state.snapshot?.copy(devices = state.snapshot!!.devices.filterNot {
                    it.sessionId == confirmation.device.sessionId
                }), message = "已注销，正在刷新"))
                val refreshed = try {
                    val selected = credentials(id) ?: throw BillingException(BillingProblem.EXPIRED)
                    current.refresh(selected)
                }
                    catch (error: CancellationException) { throw error }
                    catch (_: VaultUnavailableException) {
                        reset("账号存储异常，刷新停止", clearData = true)
                        storageFailure()
                        null
                    }
                    catch (error: BillingException) {
                        if (token == generation && error.problem == BillingProblem.ACCOUNT_MISMATCH) {
                            invalidateAccountSession()
                        }
                        else if (token == generation && error.problem == BillingProblem.EXPIRED) {
                            invalidateExpiredSession()
                        }
                        null
                    }
                    catch (_: Exception) { null }
                if (token == generation) {
                    val loadedAt = if (refreshed != null) System.currentTimeMillis() else state.loadedAt
                    if (refreshed != null && loadedAt != null) saveCached(id, refreshed, loadedAt)
                    emit(state.copy(running = false, snapshot = refreshed ?: state.snapshot,
                    loadedAt = loadedAt,
                    devicesActionable = refreshed != null, snapshotFromCache = false,
                    message = if (refreshed != null) "已注销，在线信息已刷新" else "已注销，刷新失败，请重查"))
                }
            } catch (error: CancellationException) { throw error }
            catch (error: BillingException) { if (token == generation) {
                if (error.problem == BillingProblem.ACCOUNT_MISMATCH) invalidateAccountSession()
                else if (error.problem == BillingProblem.EXPIRED) invalidateExpiredSession()
                else emit(state.copy(running = false, queryFailed = false, devicesActionable = false, message = error.problem.hint))
            } }
            catch (_: Exception) { if (token == generation) emit(state.copy(running = false,
                queryFailed = false, devicesActionable = false, message = BillingProblem.OFFLINE.hint)) }
            finally { if (token == generation) { offlineSent = false; offlineConfirmed = false } }
        }
    }

    /** A mismatched identity invalidates both the session and every action derived from its data. */
    private fun invalidateAccountSession() {
        val message = if (offlineConfirmed) "已注销；账号不符，请重登" else BillingProblem.ACCOUNT_MISMATCH.hint
        // Preserve the confirmed outcome above instead of reset's generic connection-change message.
        offlineSent = false
        offlineConfirmed = false
        attempted = true
        reset(message, clearData = true)
    }

    private fun invalidateExpiredSession() {
        val message = if (offlineConfirmed) "已注销，会话失效，请重查" else BillingProblem.EXPIRED.hint
        offlineSent = false
        offlineConfirmed = false
        attempted = true
        reset(message, clearData = true)
    }

    private fun reset(message: String, clearData: Boolean, clearCache: Boolean = clearData,
        queryFailed: Boolean = state.queryFailed) {
        val actualMessage = if (offlineSent) {
            if (offlineConfirmed) "已注销；网络已变，请重查"
            else BillingProblem.OFFLINE.hint
        } else message
        generation++
        job?.cancel()
        session?.close()
        session = null
        if (clearCache) invalidateCache()
        emit(if (clearData) BillingUiState(message = actualMessage, devicesActionable = false) else state.copy(running = false, captcha = null,
            confirmation = null, offerWeb = false, webPage = null, diagnostics = emptyList(),
            devicesActionable = false, queryFailed = queryFailed, message = actualMessage))
        offlineSent = false
        offlineConfirmed = false
    }

    private fun queriedSuccessfully(id: String, result: BillingSnapshot, current: BillingSession) {
        val loadedAt = System.currentTimeMillis()
        emit(BillingUiState(snapshot = result, loadedAt = loadedAt,
            message = result.notice?.takeIf { it.isNotBlank() } ?: "查询成功",
            diagnostics = current.diagnostics, devicesActionable = true))
        saveCached(id, result, loadedAt)
    }

    private fun restoreCached(id: String) {
        val repository = cache ?: return
        val epoch = cacheEpoch
        scope.launch {
            val owner = currentCoroutineContext()[Job]!!
            val restored = withContext(cacheIo) { repository.load(id) { cacheEpoch == epoch && owner.isActive } } ?: return@launch
            if (cacheEpoch == epoch && accountId == id && accountsReady && state.snapshot == null) {
                emit(state.copy(snapshot = restored.snapshot, loadedAt = restored.loadedAt,
                    snapshotFromCache = true, devicesActionable = false))
            }
        }
    }

    private fun saveCached(id: String, result: BillingSnapshot, loadedAt: Long) {
        val repository = cache ?: return
        val epoch = ++cacheEpoch
        scope.launch(cacheIo) {
            val owner = currentCoroutineContext()[Job]!!
            repository.save(CachedBilling(id, result, loadedAt)) { cacheEpoch == epoch && owner.isActive }
        }
    }

    private fun invalidateCache(): Job? {
        val repository = cache ?: return null
        val epoch = ++cacheEpoch
        return scope.launch(cacheIo) {
            val owner = currentCoroutineContext()[Job]!!
            repository.clear { cacheEpoch == epoch && owner.isActive }
        }
    }

    private fun emit(next: BillingUiState) { state = next; publish(next) }
}
