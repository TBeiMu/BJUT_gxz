package io.github.tbeimu.gongxiaozhu.billing

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.VaultCipher
import io.github.tbeimu.gongxiaozhu.data.VaultStore
import io.github.tbeimu.gongxiaozhu.network.PortalTransport
import io.github.tbeimu.gongxiaozhu.network.WifiConnection
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Deterministic display-cache lifetimes, with fictional accounts and no Android, disk or HTTP. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BillingRetainedControllerTest {
    private class Cache : BillingCacheRepository(object : VaultStore {
        override fun read(): ByteArray? = error("Unused store")
        override fun write(bytes: ByteArray) = error("Unused store")
        override fun clear() = error("Unused store")
    }, object : VaultCipher {
        override fun encrypt(plaintext: ByteArray, requireExistingKey: Boolean): ByteArray = error("Unused cipher")
        override fun decrypt(envelope: ByteArray): ByteArray = error("Unused cipher")
        override fun clearKey() = error("Unused cipher")
    }) {
        var value: CachedBilling? = null
        var saveAttempts = 0
        var saves = 0
        var clears = 0
        var failLoad = false
        var failSave = false
        var failClear = false
        var ignoreLoadValidity = false
        override fun load(accountId: String, isCurrent: () -> Boolean): CachedBilling? =
            value?.takeIf { !failLoad && it.accountId == accountId && (ignoreLoadValidity || isCurrent()) }
        override fun save(value: CachedBilling, isCurrent: () -> Boolean): Boolean {
            saveAttempts++
            if (failSave || !isCurrent()) return false
            this.value = value; saves++
            return true
        }
        override fun clear(isCurrent: () -> Boolean): Boolean {
            if (failClear || !isCurrent()) return false
            value = null; clears++
            return true
        }
    }

    private class Session : BillingSession {
        var refreshes = 0
        var tips = 0
        var offlines = 0
        var closed = false
        var response: suspend () -> BillingSnapshot = { demoSnapshot }
        var offlineAction: suspend () -> Unit = {}
        var webResult: suspend () -> BillingWebResult = { BillingWebResult.Success(demoSnapshot) }
        override suspend fun refresh(credentials: AccountDraft, code: String?): BillingSnapshot {
            refreshes++; return response()
        }
        override suspend fun offlineTip(): String { tips++; return "虚构注销确认" }
        override suspend fun offline(device: OnlineDevice) { offlines++; offlineAction() }
        override suspend fun openWeb(credentials: AccountDraft) = BillingWebPage("fictional-page",
            "https://jfself.bjut.edu.cn/Self/login/", "fictional page")
        override suspend fun submitWeb(token: String, password: String, code: String) = webResult()
        override fun close() { closed = true }
    }

    private class QueuedIo : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val pending get() = tasks.isNotEmpty()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private inner class Harness(scope: CoroutineScope, val cache: Cache, io: CoroutineDispatcher) {
        var state = BillingUiState()
        var storageFailures = 0
        val controller = BillingController(scope, { id -> AccountDraft(id, "测试", "fictional-$id", "fictional-password") },
            { state = it }, { storageFailures++ }, {}, cache = cache, cacheIo = io)
        fun configure(session: Session, id: String = "account-a", visible: Boolean = false) {
            controller.accountChanged(id)
            controller.wifiChanged(wifi(session))
            controller.enterForeground()
            controller.setVisible(visible)
        }
    }

    private fun wifi(session: Session, key: String = "fictional-wifi") =
        WifiConnection(key, PortalTransport { error("No portal HTTP expected") }, billingSession = { session })
    private fun cached(id: String = "account-a", snapshot: BillingSnapshot = demoSnapshot, time: Long = 1234L) =
        CachedBilling(id, snapshot, time)
    private fun TestScope.harness(cache: Cache = Cache()) = Harness(backgroundScope, cache, StandardTestDispatcher(testScheduler))
    private fun TestScope.settle(io: QueuedIo) {
        runCurrent()
        do { io.drain(); runCurrent() } while (io.pending)
    }

    @Test fun successfulQueryFailureAndRetryKeepOnlyTheMostRecentSuccessfulResult() = runTest {
        val h = harness(); val session = Session()
        h.configure(session); h.controller.query(); runCurrent()
        val firstTime = h.state.loadedAt
        assertEquals(demoSnapshot, h.state.snapshot); assertEquals(demoSnapshot, h.cache.value?.snapshot)
        assertFalse(h.state.queryFailed); assertFalse(h.state.snapshotFromCache); assertTrue(h.state.devicesActionable)
        session.response = { throw BillingException(BillingProblem.CONNECTION) }
        h.controller.query(); runCurrent()
        assertEquals(demoSnapshot, h.state.snapshot); assertEquals(firstTime, h.state.loadedAt)
        assertTrue(h.state.queryFailed); assertFalse(h.state.devicesActionable)
        assertEquals(demoSnapshot, h.cache.value?.snapshot)
        val latest = demoSnapshot.copy(metrics = mapOf("已用流量" to BillingMetric("9876", "MB")))
        session.response = { latest }
        h.controller.query(); runCurrent()
        assertEquals(latest, h.state.snapshot); assertEquals(latest, h.cache.value?.snapshot)
        assertEquals(h.state.loadedAt, h.cache.value?.loadedAt)
        assertFalse(h.state.queryFailed); assertTrue(h.state.devicesActionable)
        assertEquals(3, session.refreshes); assertEquals(0, h.storageFailures)
    }

    @Test fun coldRestoreSurvivesFirstWifiAndFailedAutomaticRefreshWithoutAddingAnAutomaticAttempt() = runTest {
        val cache = Cache().apply { value = cached() }; val h = harness(cache)
        val session = Session().apply { response = { throw BillingException(BillingProblem.CONNECTION) } }
        h.controller.accountChanged("account-a"); runCurrent()
        assertEquals(demoSnapshot, h.state.snapshot); assertEquals(1234L, h.state.loadedAt)
        h.controller.enterForeground(); h.controller.setVisible(true)
        h.controller.wifiChanged(wifi(session)); runCurrent()
        assertEquals(demoSnapshot, h.state.snapshot); assertEquals(1234L, h.state.loadedAt)
        assertTrue(h.state.queryFailed); assertTrue(h.state.snapshotFromCache); assertFalse(h.state.devicesActionable)
        repeat(3) { h.controller.setVisible(false); h.controller.setVisible(true); h.controller.enterForeground(); runCurrent() }
        assertEquals(1, session.refreshes); assertEquals(demoSnapshot, cache.value?.snapshot)
    }

    @Test fun unconfirmedLoginKeepsItsSpecificHintForOrdinaryAndWebQueries() = runTest {
        val cache = Cache().apply { value = cached() }; val h = harness(cache)
        val session = Session().apply { response = { throw BillingException(BillingProblem.CONNECTION) } }
        h.configure(session); runCurrent()
        h.controller.query(); runCurrent()
        assertTrue("Ordinary connection failure still uses the compact failure status", h.state.queryFailed)
        session.response = { throw BillingException(BillingProblem.UNCONFIRMED) }
        h.controller.query(); runCurrent()
        assertFalse("An unconfirmed login must not be hidden by the generic failure label", h.state.queryFailed)
        assertEquals(BillingProblem.UNCONFIRMED.hint, h.state.message)
        assertTrue(h.state.offerWeb); assertEquals(demoSnapshot, h.state.snapshot); assertEquals(1234L, h.state.loadedAt)
        h.controller.openWeb(); runCurrent(); assertNotNull(h.state.webPage)
        session.webResult = { throw BillingException(BillingProblem.UNCONFIRMED) }
        h.controller.submitWeb("fictional-page", "fictional-password", ""); runCurrent()
        assertFalse(h.state.queryFailed); assertEquals(BillingProblem.UNCONFIRMED.hint, h.state.message)
        assertNull(h.state.webPage); assertFalse(h.state.devicesActionable)
        assertEquals(demoSnapshot, h.state.snapshot); assertEquals(1234L, h.state.loadedAt)
        assertEquals(demoSnapshot, cache.value?.snapshot)
    }

    @Test fun initialOfflineCacheAndAQueryCaptchaNeverAuthorizeCachedDeviceLogout() = runTest {
        val cache = Cache().apply { value = cached() }; val h = harness(cache)
        h.controller.accountChanged("account-a"); h.controller.wifiChanged(null)
        h.controller.enterForeground(); runCurrent()
        assertEquals(demoSnapshot, h.state.snapshot); assertFalse(h.state.devicesActionable)
        h.controller.requestOffline(demoDevice.sessionId); h.controller.confirmOffline(); runCurrent()
        assertNull(h.state.confirmation); assertEquals(demoSnapshot, cache.value?.snapshot)
        val session = Session().apply { response = { throw BillingException(BillingProblem.CAPTCHA) } }
        h.controller.wifiChanged(wifi(session)); h.controller.query(); runCurrent()
        assertFalse(h.state.queryFailed); assertEquals(BillingProblem.CAPTCHA.hint, h.state.message)
        assertFalse(h.state.devicesActionable)
        h.controller.requestOffline(demoDevice.sessionId); h.controller.confirmOffline(); runCurrent()
        assertEquals(0, session.tips); assertEquals(0, session.offlines); assertNull(h.state.confirmation)
    }

    @Test fun switchingAccountsClearsTheSingleCacheAndCannotRestoreTheOtherAccountsData() = runTest {
        val cache = Cache().apply { value = cached() }; val h = harness(cache)
        h.controller.accountChanged("account-a"); runCurrent()
        assertNotNull(h.state.snapshot)
        h.controller.accountChanged("account-b"); runCurrent()
        assertNull(h.state.snapshot); assertNull(h.state.loadedAt); assertNull(cache.value)
        h.controller.accountChanged("account-a"); runCurrent()
        assertNull(h.state.snapshot); assertNull(cache.value); assertEquals(0, h.storageFailures)
    }

    @Test fun aLateRestoreCannotReplaceANewerLiveSuccess() = runTest {
        val io = QueuedIo(); val cache = Cache().apply { value = cached(); ignoreLoadValidity = true }
        val h = Harness(backgroundScope, cache, io); val latest = demoSnapshot.copy(devices = emptyList())
        val session = Session().apply { response = { latest } }
        h.configure(session); runCurrent()
        assertTrue(io.pending)
        h.controller.query(); runCurrent()
        assertEquals(latest, h.state.snapshot); assertFalse(h.state.snapshotFromCache)
        settle(io)
        assertEquals(latest, h.state.snapshot); assertEquals(latest, cache.value?.snapshot)
        assertFalse(h.state.queryFailed); assertFalse(h.state.snapshotFromCache)
    }

    @Test fun queuedRestoresCannotRepopulateDataAfterAccountForceOrSwitch() = runTest {
        for (reason in listOf("force", "account")) {
            val io = QueuedIo(); val cache = Cache().apply { value = cached(); ignoreLoadValidity = true }
            val h = Harness(backgroundScope, cache, io); val session = Session()
            h.configure(session); runCurrent()
            when (reason) {
                "force" -> h.controller.accountChanged("account-a", force = true)
                "account" -> h.controller.accountChanged("account-b")
            }
            settle(io)
            assertNull("Invalidation: $reason", h.state.snapshot)
            assertNull(h.state.loadedAt); assertNull(cache.value); assertFalse(h.state.devicesActionable)
        }
    }

    @Test fun queuedSuccessfulWritesCannotOutliveAccountForceOrSwitch() = runTest {
        for (reason in listOf("force", "account")) {
            val io = QueuedIo(); val cache = Cache(); val h = Harness(backgroundScope, cache, io)
            val session = Session(); h.configure(session); settle(io)
            h.controller.query(); runCurrent()
            assertEquals(demoSnapshot, h.state.snapshot); assertTrue(io.pending)
            when (reason) {
                "force" -> h.controller.accountChanged("account-a", force = true)
                "account" -> h.controller.accountChanged("account-b")
            }
            settle(io)
            assertNull("Invalidation: $reason", cache.value)
            assertNull(h.state.snapshot); assertFalse(h.state.devicesActionable)
        }
    }

    @Test fun foregroundWifiChangesRetainDisplayAndDiskUntilARealSuccessfulQueryReplacesThem() = runTest {
        val h = harness(); val first = Session()
        h.configure(first, visible = true); runCurrent()
        val loadedAt = h.state.loadedAt
        val retained = h.cache.value
        assertEquals(1, first.refreshes); assertTrue(h.state.devicesActionable)
        h.controller.requestOffline(demoDevice.sessionId); runCurrent(); assertNotNull(h.state.confirmation)
        h.controller.wifiChanged(null); runCurrent()
        assertTrue(first.closed); assertEquals(demoSnapshot, h.state.snapshot); assertEquals(loadedAt, h.state.loadedAt)
        assertEquals(retained, h.cache.value); assertNull(h.state.confirmation); assertFalse(h.state.devicesActionable)
        h.controller.requestOffline(demoDevice.sessionId); h.controller.confirmOffline(); runCurrent()
        assertEquals(0, first.offlines)
        val reconnect = Session().apply { response = { throw BillingException(BillingProblem.CONNECTION) } }
        h.controller.wifiChanged(wifi(reconnect)); runCurrent()
        assertEquals(0, reconnect.refreshes); assertEquals(retained, h.cache.value)
        h.controller.query(); runCurrent()
        assertEquals(1, reconnect.refreshes); assertTrue(h.state.queryFailed)
        assertEquals(demoSnapshot, h.state.snapshot); assertEquals(loadedAt, h.state.loadedAt)
        assertEquals(retained, h.cache.value); assertFalse(h.state.devicesActionable)
        h.controller.wifiChanged(null); runCurrent()
        assertFalse("A fresh disconnect hint must replace an obsolete generic query failure", h.state.queryFailed)
        assertTrue(h.state.message.contains("未连接 WiFi"))
        assertEquals(demoSnapshot, h.state.snapshot); assertEquals(loadedAt, h.state.loadedAt)
        assertEquals(retained, h.cache.value)
        val replacement = Session()
        h.controller.wifiChanged(wifi(replacement, "replacement-wifi")); runCurrent()
        assertFalse(h.state.queryFailed); assertTrue(h.state.message.contains("网络已变"))
        assertEquals(0, replacement.refreshes); assertEquals(retained, h.cache.value)
        h.controller.requestOffline(demoDevice.sessionId); h.controller.confirmOffline(); runCurrent()
        assertEquals(0, replacement.tips); assertEquals(0, replacement.offlines)
        h.controller.query(); runCurrent()
        assertEquals(1, replacement.refreshes); assertTrue(h.state.devicesActionable)
        assertEquals(demoSnapshot, h.cache.value?.snapshot)
        assertEquals(h.state.loadedAt, h.cache.value?.loadedAt)
        assertEquals(1, first.refreshes)
    }

    @Test fun historicalResultRestoresAfterDisconnectAndControllerRecreationWithoutLogoutAuthority() = runTest {
        val h = harness(); val first = Session(); h.configure(first)
        h.controller.query(); runCurrent()
        val retained = h.cache.value!!
        h.controller.wifiChanged(null); runCurrent()
        assertEquals(retained, h.cache.value)
        val restarted = harness(h.cache)
        restarted.controller.accountChanged("account-a"); restarted.controller.wifiChanged(null)
        restarted.controller.enterForeground(); restarted.controller.setVisible(true); runCurrent()
        assertEquals(retained.snapshot, restarted.state.snapshot); assertEquals(retained.loadedAt, restarted.state.loadedAt)
        assertTrue(restarted.state.snapshotFromCache); assertFalse(restarted.state.devicesActionable)
        restarted.controller.requestOffline(demoDevice.sessionId); restarted.controller.confirmOffline(); runCurrent()
        assertNull(restarted.state.confirmation)
        val reconnect = Session().apply { response = { throw BillingException(BillingProblem.CONNECTION) } }
        restarted.controller.wifiChanged(wifi(reconnect)); runCurrent()
        assertEquals(1, reconnect.refreshes); assertTrue(restarted.state.queryFailed)
        assertEquals(retained.snapshot, restarted.state.snapshot); assertEquals(retained.loadedAt, restarted.state.loadedAt)
        assertEquals(retained, h.cache.value); assertFalse(restarted.state.devicesActionable)
        restarted.controller.requestOffline(demoDevice.sessionId); restarted.controller.confirmOffline(); runCurrent()
        assertEquals(0, reconnect.tips); assertEquals(0, reconnect.offlines)
    }

    @Test fun queuedHistoricalRestoresAndSavesSurviveDisconnectReconnectAndWifiReplacement() = runTest {
        for (change in listOf("disconnect", "reconnect", "replace")) {
            val restoreIo = QueuedIo(); val restoreCache = Cache().apply { value = cached() }
            val restoring = Harness(backgroundScope, restoreCache, restoreIo); val restoreSession = Session()
            restoring.configure(restoreSession); runCurrent(); assertTrue(restoreIo.pending)
            when (change) {
                "disconnect" -> restoring.controller.wifiChanged(null)
                "reconnect" -> { restoring.controller.wifiChanged(null); restoring.controller.wifiChanged(wifi(restoreSession)) }
                else -> restoring.controller.wifiChanged(wifi(restoreSession, "replacement-wifi"))
            }
            settle(restoreIo)
            assertEquals("Pending restore: $change", demoSnapshot, restoring.state.snapshot)
            assertEquals(1234L, restoring.state.loadedAt); assertTrue(restoring.state.snapshotFromCache)
            assertFalse(restoring.state.devicesActionable); assertEquals(0, restoreSession.refreshes)
            assertEquals(0, restoreCache.clears)

            val saveIo = QueuedIo(); val saveCache = Cache()
            val saving = Harness(backgroundScope, saveCache, saveIo); val saveSession = Session()
            saving.configure(saveSession); settle(saveIo)
            saving.controller.query(); runCurrent(); assertTrue(saveIo.pending)
            val loadedAt = saving.state.loadedAt
            when (change) {
                "disconnect" -> saving.controller.wifiChanged(null)
                "reconnect" -> { saving.controller.wifiChanged(null); saving.controller.wifiChanged(wifi(saveSession)) }
                else -> saving.controller.wifiChanged(wifi(saveSession, "replacement-wifi"))
            }
            settle(saveIo)
            assertEquals("Pending save: $change", demoSnapshot, saveCache.value?.snapshot)
            assertEquals(loadedAt, saveCache.value?.loadedAt)
            assertEquals(demoSnapshot, saving.state.snapshot); assertEquals(loadedAt, saving.state.loadedAt)
            assertFalse(saving.state.devicesActionable); assertEquals(1, saveSession.refreshes)
            assertEquals(0, saveCache.clears)
        }
    }

    @Test fun nonCooperativeRefreshFromThePreviousWifiCannotReplaceRetainedOrNewerResults() = runTest {
        val h = harness(); val first = Session(); h.configure(first, visible = true); runCurrent()
        val original = h.cache.value!!
        val gate = CompletableDeferred<Unit>()
        val stale = demoSnapshot.copy(metrics = mapOf("账户余额" to BillingMetric("999", "元")))
        first.response = { withContext(NonCancellable) { gate.await() }; stale }
        h.controller.query(); runCurrent(); assertEquals(2, first.refreshes)
        h.controller.wifiChanged(null); runCurrent()
        assertEquals(original.snapshot, h.state.snapshot); assertEquals(original.loadedAt, h.state.loadedAt)
        assertEquals(original, h.cache.value); assertFalse(h.state.running); assertFalse(h.state.devicesActionable)
        val latest = demoSnapshot.copy(metrics = mapOf("账户余额" to BillingMetric("12.5", "元")))
        val replacement = Session().apply { response = { latest } }
        h.controller.wifiChanged(wifi(replacement, "replacement-wifi")); h.controller.query(); runCurrent()
        assertEquals(0, replacement.refreshes)
        gate.complete(Unit); runCurrent()
        assertEquals(original.snapshot, h.state.snapshot); assertEquals(original.loadedAt, h.state.loadedAt)
        assertEquals(original, h.cache.value); assertFalse(h.state.devicesActionable)
        h.controller.query(); runCurrent()
        assertEquals(1, replacement.refreshes); assertEquals(latest, h.state.snapshot)
        assertEquals(latest, h.cache.value?.snapshot); assertTrue(h.state.devicesActionable)
        assertEquals(2, first.refreshes)
    }

    @Test fun disconnectKeepsConfirmedAndUnconfirmedLogoutOutcomesAheadOfNetworkHints() = runTest {
        for (confirmed in listOf(false, true)) {
            val h = harness(); val session = Session(); h.configure(session)
            h.controller.query(); runCurrent()
            val loadedAt = h.state.loadedAt
            val cleanup = CompletableDeferred<Unit>()
            if (confirmed) session.response = { withContext(NonCancellable) { cleanup.await() }; demoSnapshot }
            else session.offlineAction = { withContext(NonCancellable) { cleanup.await() } }
            h.controller.requestOffline(demoDevice.sessionId); runCurrent()
            h.controller.confirmOffline(); runCurrent()
            assertEquals(1, session.offlines)
            h.controller.wifiChanged(null); runCurrent()
            val message = h.state.message
            assertFalse(h.state.queryFailed); assertFalse(h.state.devicesActionable)
            if (confirmed) {
                assertTrue(message.contains("已注销")); assertFalse(message.contains("未确认"))
                assertTrue(h.state.snapshot!!.devices.isEmpty())
            } else {
                assertEquals(BillingProblem.OFFLINE.hint, message)
                assertEquals(demoSnapshot, h.state.snapshot)
            }
            assertEquals(loadedAt, h.state.loadedAt); assertNull(h.cache.value)
            cleanup.complete(Unit); runCurrent()
            assertEquals(message, h.state.message); assertNull(h.cache.value)
            assertEquals(1, session.offlines)
        }
    }

    @Test fun optionalCacheReadAndWriteFailuresNeverBlockTheAccountOrSuccessfulQuery() = runTest {
        val cache = Cache().apply { value = cached(); failLoad = true; failSave = true }
        val h = harness(cache); val session = Session()
        h.configure(session); runCurrent(); assertNull(h.state.snapshot)
        h.controller.query(); runCurrent()
        assertEquals(demoSnapshot, h.state.snapshot); assertNotNull(h.state.loadedAt)
        assertFalse(h.state.queryFailed); assertFalse(h.state.snapshotFromCache); assertTrue(h.state.devicesActionable)
        assertEquals(1, session.refreshes); assertEquals(0, h.storageFailures); assertEquals(0, cache.saves)
    }

    @Test fun temporaryAccountLoadingPreservesDiskButAConfirmedMissingAccountClearsIt() = runTest {
        val cache = Cache().apply { value = cached() }; val h = harness(cache)
        h.controller.accountsLoading(); runCurrent()
        assertEquals(demoSnapshot, cache.value?.snapshot); assertNull(h.state.snapshot)
        h.controller.accountChanged("account-a"); runCurrent()
        assertEquals(demoSnapshot, h.state.snapshot)
        h.controller.accountsLoading(); runCurrent()
        assertNull(h.state.snapshot); assertEquals(demoSnapshot, cache.value?.snapshot)
        h.controller.accountChanged("account-a"); runCurrent()
        assertEquals(demoSnapshot, h.state.snapshot)
        h.controller.accountChanged(null); runCurrent()
        assertNull(h.state.snapshot); assertNull(cache.value)
        val coldCache = Cache().apply { value = cached() }; val cold = harness(coldCache)
        cold.controller.accountChanged(null); runCurrent()
        assertNull(coldCache.value); assertNull(cold.state.snapshot)
    }

    @Test fun expiredOrMismatchedQueriesAndMismatchedWebLoginDiscardOldDisplayAndCache() = runTest {
        for (problem in listOf(BillingProblem.EXPIRED, BillingProblem.ACCOUNT_MISMATCH)) {
            val h = harness(); val session = Session(); h.configure(session)
            h.controller.query(); runCurrent(); assertNotNull(h.cache.value)
            session.response = { throw BillingException(problem) }
            h.controller.query(); runCurrent()
            assertNull(h.state.snapshot); assertNull(h.state.loadedAt); assertNull(h.cache.value)
            assertFalse(h.state.devicesActionable); assertTrue(session.closed)
        }
        val h = harness(); val session = Session(); h.configure(session)
        h.controller.query(); runCurrent()
        session.response = { throw BillingException(BillingProblem.REJECTED) }
        h.controller.query(); runCurrent(); assertTrue(h.state.offerWeb)
        h.controller.openWeb(); runCurrent(); assertNotNull(h.state.webPage)
        session.webResult = { throw BillingException(BillingProblem.ACCOUNT_MISMATCH) }
        h.controller.submitWeb("fictional-page", "fictional-password", ""); runCurrent()
        assertNull(h.state.snapshot); assertNull(h.state.webPage); assertNull(h.cache.value)
        assertFalse(h.state.devicesActionable)
    }

    @Test fun confirmedDeviceLogoutInvalidatesDiskBeforeSendingAndCannotResurrectAfterRestart() = runTest {
        val h = harness(); val session = Session(); h.configure(session)
        h.controller.query(); runCurrent(); assertNotNull(h.cache.value)
        session.offlineAction = {
            assertNull("Old disk snapshot must be retired before a destructive request", h.cache.value)
        }
        session.response = { throw BillingException(BillingProblem.CONNECTION) }
        h.controller.requestOffline(demoDevice.sessionId); runCurrent()
        h.controller.confirmOffline(); runCurrent()
        assertEquals(1, session.offlines); assertEquals(2, session.refreshes)
        assertTrue(h.state.message.contains("已注销")); assertTrue(h.state.snapshot!!.devices.isEmpty())
        assertNull(h.cache.value); assertFalse(h.state.devicesActionable)
        val restarted = harness(h.cache); restarted.controller.accountChanged("account-a"); runCurrent()
        assertNull(restarted.state.snapshot); assertFalse(restarted.state.devicesActionable)
    }

    @Test fun failedCacheClearDoesNotBlockExplicitDeviceLogoutOrAuthorizeAnotherCachedAction() = runTest {
        val cache = Cache(); val h = harness(cache); val session = Session(); h.configure(session)
        h.controller.query(); runCurrent(); assertNotNull(cache.value)
        cache.failClear = true
        session.response = { throw BillingException(BillingProblem.CONNECTION) }
        h.controller.requestOffline(demoDevice.sessionId); runCurrent()
        h.controller.confirmOffline(); runCurrent()
        assertEquals(1, session.offlines); assertEquals(0, h.storageFailures)
        assertTrue(h.state.message.contains("已注销")); assertFalse(h.state.devicesActionable)
        h.controller.requestOffline(demoDevice.sessionId); h.controller.confirmOffline(); runCurrent()
        assertEquals(1, session.offlines)
    }
}
