package io.github.tbeimu.gongxiaozhu.billing

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.VaultCipher
import io.github.tbeimu.gongxiaozhu.data.VaultStore
import io.github.tbeimu.gongxiaozhu.network.PortalTransport
import io.github.tbeimu.gongxiaozhu.network.WifiConnection
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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

    @Test fun queuedRestoresCannotRepopulateDataAfterAccountForceSwitchOrRealWifiChange() = runTest {
        for (reason in listOf("force", "account", "wifi")) {
            val io = QueuedIo(); val cache = Cache().apply { value = cached(); ignoreLoadValidity = true }
            val h = Harness(backgroundScope, cache, io); val session = Session()
            h.configure(session); runCurrent()
            when (reason) {
                "force" -> h.controller.accountChanged("account-a", force = true)
                "account" -> h.controller.accountChanged("account-b")
                else -> h.controller.wifiChanged(wifi(session, "replacement-wifi"))
            }
            settle(io)
            assertNull("Invalidation: $reason", h.state.snapshot)
            assertNull(h.state.loadedAt); assertNull(cache.value); assertFalse(h.state.devicesActionable)
        }
    }

    @Test fun queuedSuccessfulWritesCannotOutliveAccountForceSwitchOrRealWifiChange() = runTest {
        for (reason in listOf("force", "account", "wifi")) {
            val io = QueuedIo(); val cache = Cache(); val h = Harness(backgroundScope, cache, io)
            val session = Session(); h.configure(session); settle(io)
            h.controller.query(); runCurrent()
            assertEquals(demoSnapshot, h.state.snapshot); assertTrue(io.pending)
            when (reason) {
                "force" -> h.controller.accountChanged("account-a", force = true)
                "account" -> h.controller.accountChanged("account-b")
                else -> h.controller.wifiChanged(null)
            }
            settle(io)
            assertNull("Invalidation: $reason", cache.value)
            assertNull(h.state.snapshot); assertFalse(h.state.devicesActionable)
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
