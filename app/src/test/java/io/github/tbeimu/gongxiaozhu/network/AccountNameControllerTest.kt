package io.github.tbeimu.gongxiaozhu.network

import io.github.tbeimu.gongxiaozhu.data.*
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleIdentity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountNameControllerTest {
    private class Store : VaultStore {
        var bytes: ByteArray? = null
        var failWrites = false
        var corruptOnFailure = false
        var writes = 0
        override fun read() = bytes
        override fun write(bytes: ByteArray) {
            writes++
            if (failWrites) {
                if (corruptOnFailure) this.bytes = byteArrayOf(1, 2, 3)
                throw IOException("Fictional write failure")
            }
            this.bytes = bytes
        }
        override fun clear() { bytes = null }
    }
    private fun repository(store: Store = Store()): AccountRepository = AccountRepository(store, object : VaultCipher {
        override fun encrypt(plaintext: ByteArray, requireExistingKey: Boolean) = plaintext.copyOf()
        override fun decrypt(envelope: ByteArray) = envelope.copyOf()
        override fun clearKey() {}
    }).apply { load() }
    private fun add(repo: AccountRepository, user: String = "fictional-student", label: String = "") =
        repo.save(AccountDraft(label = label, username = user, password = "fictional-password")).accounts.last()
    private fun route(key: String = "fictional-wifi", validated: Boolean = true, lookup: AccountNameLookup) =
        WifiObservation.Current(WifiConnection(key, PortalTransport { "" }, lookup, validated))
    private class QueuedMainDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun runQueued() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }

    @Test fun savesNameOnceAndKeepsExistingNetworkAccountSelection() = runTest {
        val repo = repository(); val first = add(repo); val second = add(repo, "other-student", "自定义备注")
        repo.setDefault(second.id)
        var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, networkAuthenticated = { true }, io = StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route { account -> calls++; assertEquals(first.id, account.id); ScheduleIdentity(account.username, "测试同学") })
        controller.enterForeground(); runCurrent()
        assertEquals("测试同学", repo.state.value!!.accounts.first().label)
        assertEquals(second.id, repo.state.value!!.defaultAccountId)
        repeat(3) { controller.leaveForeground(); controller.enterForeground(); runCurrent() }
        assertEquals(1, calls)
    }

    @Test fun waitsForCurrentWifiAndScheduleCacheBeforeLookingUp() = runTest {
        val repo = repository(); add(repo)
        var authenticated = false; var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, { authenticated }, StandardTestDispatcher(testScheduler))
        controller.enterForeground()
        controller.wifiObserved(route(validated = false) { calls++; ScheduleIdentity(it.username, "测试同学") })
        runCurrent(); assertEquals(0, calls)
        controller.scheduleIdentity(null); runCurrent(); assertEquals(0, calls)
        authenticated = true; controller.authenticationChanged(); runCurrent()
        assertEquals(1, calls)
    }

    @Test fun validatedInternetWithoutCampusAuthenticationCannotQueryOrConsumeTheAttempt() = runTest {
        val store = Store(); val repo = repository(store); add(repo)
        var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, io = StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route(validated = true) { calls++; error("Campus authentication has not succeeded") })
        val before = store.bytes!!.copyOf()
        val writes = store.writes
        controller.enterForeground(); runCurrent()
        repeat(3) { controller.authenticationChanged(); runCurrent() }
        assertEquals(0, calls)
        assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
        assertArrayEquals(before, store.bytes)
        assertEquals(writes, store.writes)
    }

    @Test fun failedAuthenticationNotificationsKeepTheAttemptUntilTheFirstSuccess() = runTest {
        val repo = repository(); add(repo)
        var authenticated = false; var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, { authenticated }, StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route(validated = true) { calls++; ScheduleIdentity(it.username, "测试同学") })
        controller.enterForeground(); runCurrent()
        repeat(3) {
            controller.authenticationChanged(); runCurrent()
            assertEquals(0, calls)
            assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
        }
        authenticated = true; controller.authenticationChanged(); runCurrent()
        assertEquals(1, calls)
        assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
        assertEquals("测试同学", repo.state.value!!.accounts.single().label)
    }

    @Test fun authenticationOfAnotherWifiCannotAuthorizeTheCurrentWifiLookup() = runTest {
        val repo = repository(); add(repo)
        var authenticatedNetwork = "previous-wifi"; var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, { it == authenticatedNetwork }, StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route(key = "current-wifi", validated = true) { calls++; ScheduleIdentity(it.username, "测试同学") })
        controller.enterForeground(); runCurrent()
        assertEquals(0, calls)
        assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
        authenticatedNetwork = "current-wifi"; controller.authenticationChanged(); runCurrent()
        assertEquals(1, calls)
    }

    @Test fun realFailedAndTimedOutAuthenticationWaitsForConfirmedSuccessThenNeverRepeatsLookup() = runTest {
        val repo = repository(); add(repo)
        val cleanup = CompletableDeferred<Unit>()
        val starts = mutableListOf<String>()
        var outcome = 0; var calls = 0
        val wifi = WifiConnection("fictional-campus-wifi", PortalTransport { error("No HTTP expected") },
            AccountNameLookup { calls++; throw IOException("Fictional name lookup failure") }, internetValidated = true)
        val login = CampusLoginController(backgroundScope, CampusAuthenticator { _, _, _, progress ->
            when (outcome) {
                0 -> LoginResult("虚构认证失败")
                1 -> awaitCancellation()
                else -> {
                    val success = LoginResult("虚构认证成功", NetworkMode.PUBLIC_WIFI, completed = true)
                    progress.confirmed(success)
                    cleanup.await()
                    success
                }
            }
        }, credentials = { error("No real credentials expected") }, startService = { starts += it }, storageFailure = {})
        val controller = AccountNameController(backgroundScope, repo, {}, login::isAuthenticatedOn, StandardTestDispatcher(testScheduler))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { login.state.collect { controller.authenticationChanged() } }
        login.configure(ready = true, automatic = false, mode = NetworkMode.PUBLIC_WIFI, accountId = null)
        login.wifiChanged(wifi); login.enterForeground()
        controller.scheduleIdentity(null); controller.wifiObserved(WifiObservation.Current(wifi))
        controller.enterForeground(); runCurrent()
        assertEquals(0, calls)
        fun startAuthentication(): String {
            login.login()
            val id = starts.last()
            assertTrue(login.serviceStarted(id))
            runCurrent()
            return id
        }
        try {
            val failed = startAuthentication()
            assertEquals(LoginTaskOutcome.FAILED, login.state.value.outcome)
            assertEquals(0, calls); assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
            login.serviceStopped(failed)
            outcome = 1
            val timedOut = startAuthentication()
            advanceTimeBy(LOGIN_TASK_TIMEOUT_MS + 1); runCurrent()
            assertEquals(LoginTaskOutcome.TIMED_OUT, login.state.value.outcome)
            assertEquals(0, calls); assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
            login.serviceStopped(timedOut)
            outcome = 2
            val confirmed = startAuthentication()
            assertTrue(login.state.value.completed)
            assertTrue("Confirmed connectivity can authorize lookup before authentication cleanup finishes", login.state.value.running)
            assertEquals(1, calls); assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
            cleanup.complete(Unit); runCurrent(); login.serviceStopped(confirmed)
            outcome = 0
            val laterFailure = startAuthentication()
            assertEquals(LoginTaskOutcome.FAILED, login.state.value.outcome)
            login.serviceStopped(laterFailure)
            outcome = 2
            startAuthentication()
            assertTrue(login.state.value.completed)
            assertEquals(1, calls)
        } finally { cleanup.complete(Unit); login.cancel(); runCurrent() }
    }

    @Test fun firstCampusSuccessInBackgroundWaitsForForegroundWithoutConsumingTheAttempt() = runTest {
        val repo = repository(); add(repo)
        var authenticated = false; var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, { authenticated }, StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route { calls++; ScheduleIdentity(it.username, "测试同学") })
        authenticated = true; controller.authenticationChanged(); runCurrent()
        assertEquals(0, calls); assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
        controller.enterForeground(); runCurrent()
        assertEquals(1, calls); assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
    }

    @Test fun restoringTheSameWifiSnapshotExplicitlyWakesLookupWithoutAnotherPublicLoginState() = runTest {
        val repo = repository(); add(repo)
        val result = CompletableDeferred<LoginResult>()
        val starts = mutableListOf<String>()
        var calls = 0; var loginEmissions = 0
        val success = LoginResult("虚构认证成功", NetworkMode.PUBLIC_WIFI, completed = true)
        val wifi = WifiConnection("fictional-restored-wifi", PortalTransport { error("No HTTP expected") },
            AccountNameLookup { calls++; ScheduleIdentity(it.username, "返回前台同学") }, internetValidated = true)
        val login = CampusLoginController(backgroundScope, CampusAuthenticator { _, _, _, _ -> result.await() },
            credentials = { error("No real credentials expected") }, startService = { starts += it }, storageFailure = {})
        val controller = AccountNameController(backgroundScope, repo, {}, login::isAuthenticatedOn, StandardTestDispatcher(testScheduler))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { login.state.collect {
            loginEmissions++; controller.authenticationChanged()
        } }
        login.configure(ready = true, automatic = false, mode = NetworkMode.PUBLIC_WIFI, accountId = null)
        login.wifiChanged(wifi); login.enterForeground()
        controller.scheduleIdentity(null); controller.wifiObserved(WifiObservation.Current(wifi)); controller.enterForeground()
        try {
            login.login(); assertTrue(login.serviceStarted(starts.last())); runCurrent()
            controller.leaveForeground(); login.leaveForeground()
            result.complete(success); runCurrent()
            assertTrue(login.state.value.completed); assertFalse(login.state.value.running)
            login.serviceStopped(starts.last())
            assertEquals(0, calls); assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
            login.awaitWifiSnapshot(); controller.wifiObserved(WifiObservation.Suspended)
            login.enterForeground(); controller.enterForeground()
            assertFalse(login.isAuthenticatedOn(wifi.key))
            // The name observer receives Current before the login controller restores its private snapshot.
            controller.wifiObserved(WifiObservation.Current(wifi)); runCurrent()
            assertEquals(0, calls); assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
            val before = login.state.value
            val emissions = loginEmissions
            login.wifiChanged(wifi); runCurrent()
            assertTrue(login.isAuthenticatedOn(wifi.key))
            assertEquals(before, login.state.value)
            assertEquals("Restoring this private snapshot must not require a new public state emission", emissions, loginEmissions)
            assertEquals(0, calls)
            controller.authenticationChanged(); runCurrent()
            assertEquals(1, calls)
            assertEquals("返回前台同学", repo.state.value!!.accounts.single().label)
            assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
            repeat(3) { controller.authenticationChanged(); runCurrent() }
            assertEquals(1, calls)
        } finally { result.complete(success); login.cancel(); runCurrent() }
    }

    @Test fun authenticationLostBeforeQueuedAttemptMarkerPreservesTheAttemptUntilNextSuccess() = runTest {
        val store = Store(); val repo = repository(store); add(repo)
        val main = QueuedMainDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        var authenticated = true; var calls = 0
        val controller = AccountNameController(scope, repo, {}, { authenticated }, StandardTestDispatcher(testScheduler))
        try {
            controller.scheduleIdentity(null)
            controller.wifiObserved(route { calls++; throw IOException("Fictional lookup failure") })
            controller.enterForeground(); main.runQueued()
            // IO is queued, so the once-only marker has not been read or written yet.
            val writes = store.writes
            assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
            authenticated = false; controller.authenticationChanged()
            runCurrent(); main.runQueued()
            assertEquals(0, calls)
            assertEquals(writes, store.writes)
            assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
            authenticated = true; controller.authenticationChanged(); main.runQueued()
            runCurrent(); main.runQueued()
            assertEquals(1, calls)
            assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
        } finally { scope.cancel(); main.runQueued(); runCurrent(); main.runQueued() }
    }

    @Test fun authenticationLostAfterPersistingTheMarkerSkipsCasWithoutRestoringTheAttempt() = runTest {
        val repo = repository(); add(repo)
        val main = QueuedMainDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        var authenticated = true; var calls = 0
        val controller = AccountNameController(scope, repo, {}, { authenticated }, StandardTestDispatcher(testScheduler))
        try {
            controller.scheduleIdentity(null)
            controller.wifiObserved(route { calls++; throw IOException("CAS must not start after authentication is lost") })
            controller.enterForeground(); main.runQueued()
            runCurrent() // Commit the marker on IO, but keep the return to Main queued.
            assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
            assertEquals(0, calls)
            authenticated = false; controller.authenticationChanged(); main.runQueued()
            assertEquals(0, calls)
            assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
            repeat(3) {
                authenticated = true; controller.authenticationChanged(); main.runQueued(); runCurrent(); main.runQueued()
                authenticated = false; controller.authenticationChanged(); main.runQueued()
            }
            assertEquals(0, calls)
            assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
        } finally { scope.cancel(); main.runQueued(); runCurrent(); main.runQueued() }
    }

    @Test fun authenticationLostAfterCasStartsDoesNotCancelOrRepeatTheSameWifiLookup() = runTest {
        val repo = repository(); val account = add(repo)
        val result = CompletableDeferred<ScheduleIdentity>()
        var authenticated = true; var calls = 0; var cancelled = false
        val controller = AccountNameController(backgroundScope, repo, {}, { authenticated }, StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route {
            calls++
            try { result.await() } catch (error: CancellationException) { cancelled = true; throw error }
        })
        controller.enterForeground(); runCurrent()
        assertEquals(1, calls); assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
        authenticated = false; controller.authenticationChanged(); runCurrent()
        assertFalse(cancelled); assertEquals(1, calls)
        result.complete(ScheduleIdentity(account.username, "已确认同学")); runCurrent()
        assertEquals("已确认同学", repo.state.value!!.accounts.single().label)
        repeat(3) {
            authenticated = true; controller.authenticationChanged(); runCurrent()
            authenticated = false; controller.authenticationChanged(); runCurrent()
        }
        assertEquals(1, calls); assertFalse(cancelled)
    }

    @Test fun localImportedIdentityAvoidsCredentialLookupEvenInBackground() = runTest {
        val repo = repository(); val account = add(repo)
        var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, io = StandardTestDispatcher(testScheduler))
        controller.wifiObserved(route { calls++; error("No school request expected") })
        controller.scheduleIdentity(ScheduleIdentity(account.username, "本地同学")); runCurrent()
        assertEquals("本地同学", repo.state.value!!.accounts.single().label)
        controller.enterForeground(); runCurrent(); assertEquals(0, calls)
    }

    @Test fun failedLookupDoesNotRetryAndLaterImportCanFillName() = runTest {
        val store = Store(); val repo = repository(store); val account = add(repo)
        var authenticated = true; var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, { authenticated }, StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route { calls++; error("Fictional server rejection") })
        controller.enterForeground(); runCurrent()
        controller.leaveForeground(); controller.enterForeground(); runCurrent()
        assertEquals(1, calls); assertEquals(account.username, repo.state.value!!.accounts.single().label)
        repeat(3) {
            authenticated = false; controller.authenticationChanged(); runCurrent()
            authenticated = true; controller.authenticationChanged(); runCurrent()
        }
        val restored = repository(store)
        val restarted = AccountNameController(backgroundScope, restored, {}, networkAuthenticated = { true }, io = StandardTestDispatcher(testScheduler))
        restarted.scheduleIdentity(null)
        restarted.wifiObserved(route { calls++; error("A completed attempt cannot be repeated after restart") })
        restarted.enterForeground(); runCurrent()
        assertEquals(1, calls)
        assertFalse(restored.state.value!!.accounts.single().nameLookupPending)
        controller.scheduleIdentity(ScheduleIdentity(account.username, "导入同学")); runCurrent()
        assertEquals("导入同学", repo.state.value!!.accounts.single().label)
    }

    @Test fun backgroundCancellationWaitsForCleanupAndRejectsLateName() = runTest {
        val repo = repository(); val account = add(repo)
        val cleanup = CompletableDeferred<Unit>(); var cancelled = false; var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, networkAuthenticated = { true }, io = StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route {
            calls++
            try { awaitCancellation() } finally { cancelled = true; withContext(NonCancellable) { cleanup.await() } }
        })
        controller.enterForeground(); runCurrent(); controller.leaveForeground(); runCurrent()
        assertTrue(cancelled)
        controller.enterForeground(); runCurrent(); assertEquals(1, calls)
        cleanup.complete(Unit); runCurrent()
        assertEquals(account.username, repo.state.value!!.accounts.single().label)
        assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
    }

    @Test fun networkSwitchAndEditedAccountDiscardNonCooperativeResults() = runTest {
        val repo = repository(); val account = add(repo)
        val delayed = CompletableDeferred<Unit>()
        val controller = AccountNameController(backgroundScope, repo, {}, networkAuthenticated = { true }, io = StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route {
            withContext(NonCancellable) { delayed.await() }
            ScheduleIdentity(it.username, "旧结果")
        })
        controller.enterForeground(); runCurrent()
        controller.wifiObserved(WifiObservation.Suspended)
        controller.editingAccount(account.id)
        repo.save(repo.edit(account.id).copy(label = "我的备注", password = "new-fictional-password"))
        delayed.complete(Unit); runCurrent(); controller.editingAccount(null); runCurrent()
        assertEquals("我的备注", repo.state.value!!.accounts.single().label)
    }

    @Test fun deletedAccountIsNotRecreatedByAnOldLookup() = runTest {
        val repo = repository(); val account = add(repo)
        val delayed = CompletableDeferred<Unit>()
        val controller = AccountNameController(backgroundScope, repo, {}, networkAuthenticated = { true }, io = StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route {
            withContext(NonCancellable) { delayed.await() }; ScheduleIdentity(it.username, "旧结果")
        })
        controller.enterForeground(); runCurrent()
        controller.accountWillChange(account.id); repo.delete(account.id)
        delayed.complete(Unit); runCurrent()
        assertTrue(repo.state.value!!.accounts.isEmpty())
    }

    @Test fun editingPausesCachedNameCompletionUntilEditorCloses() = runTest {
        val repo = repository(); val account = add(repo)
        val controller = AccountNameController(backgroundScope, repo, {}, io = StandardTestDispatcher(testScheduler))
        controller.editingAccount(account.id)
        controller.scheduleIdentity(ScheduleIdentity(account.username, "测试同学")); runCurrent()
        assertEquals(account.username, repo.state.value!!.accounts.single().label)
        controller.editingAccount(null); runCurrent()
        assertEquals("测试同学", repo.state.value!!.accounts.single().label)
    }

    @Test fun timeoutNeverRepeatsCredentialAttempt() = runTest {
        val store = Store(); val repo = repository(store); add(repo)
        var authenticated = true; var calls = 0
        val controller = AccountNameController(backgroundScope, repo, {}, { authenticated }, StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route { calls++; delay(60_000); ScheduleIdentity(it.username, "迟到姓名") })
        controller.enterForeground(); runCurrent(); advanceTimeBy(30_001); runCurrent()
        repeat(3) {
            authenticated = false; controller.authenticationChanged(); runCurrent()
            authenticated = true; controller.authenticationChanged(); runCurrent()
        }
        assertEquals(1, calls)
        assertEquals("fictional-student", repo.state.value!!.accounts.single().label)
        assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
        val restored = repository(store)
        val restarted = AccountNameController(backgroundScope, restored, {}, networkAuthenticated = { true }, io = StandardTestDispatcher(testScheduler))
        restarted.scheduleIdentity(null)
        restarted.wifiObserved(route { calls++; error("A timed-out attempt cannot be repeated after restart") })
        restarted.enterForeground(); runCurrent()
        assertEquals(1, calls)
        assertFalse(restored.state.value!!.accounts.single().nameLookupPending)
    }

    @Test fun healthyVaultNameWriteFailureIsNonBlockingAndReportedOnce() = runTest {
        val store = Store(); val repo = repository(store); val account = add(repo)
        var blocked = false
        val controller = AccountNameController(backgroundScope, repo, { blocked = true }, io = StandardTestDispatcher(testScheduler))
        val identity = ScheduleIdentity(account.username, "测试同学")
        val oldBytes = store.bytes!!.copyOf()
        val writes = store.writes
        store.failWrites = true
        controller.scheduleIdentity(identity); runCurrent()
        assertFalse(blocked)
        assertArrayEquals(oldBytes, store.bytes)
        assertEquals("fictional-password", repo.credentialsForLogin(account.id)!!.password)
        assertTrue(controller.consumeWriteFailure()); assertFalse(controller.consumeWriteFailure())
        repeat(3) { controller.scheduleIdentity(identity); controller.storageAvailable(true); controller.authenticationChanged(); runCurrent() }
        assertEquals(writes + 1, store.writes)
        assertFalse(controller.writeFailure.value)
        store.failWrites = false
        controller.scheduleIdentity(identity.copy()); runCurrent()
        assertEquals("测试同学", repo.state.value!!.accounts.single().label)
    }

    @Test fun failedAttemptMarkerWriteNeverSendsCredentialsOrLoops() = runTest {
        val store = Store(); val repo = repository(store); val account = add(repo)
        var calls = 0; var blocked = false
        val controller = AccountNameController(backgroundScope, repo, { blocked = true }, networkAuthenticated = { true }, io = StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route { calls++; ScheduleIdentity(it.username, "测试同学") })
        val writes = store.writes
        store.failWrites = true
        controller.enterForeground(); runCurrent()
        repeat(3) { controller.leaveForeground(); controller.enterForeground(); runCurrent() }
        assertEquals(0, calls); assertFalse(blocked); assertEquals(writes + 1, store.writes)
        assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
        store.failWrites = false
        controller.scheduleIdentity(ScheduleIdentity(account.username, "导入同学")); runCurrent()
        assertEquals("导入同学", repo.state.value!!.accounts.single().label)
        assertEquals(0, calls)
    }

    @Test fun laterStorageFailureDiscardsTheEarlierHealthyVaultNotice() = runTest {
        val store = Store(); val repo = repository(store); val account = add(repo)
        val controller = AccountNameController(backgroundScope, repo, {}, io = StandardTestDispatcher(testScheduler))
        store.failWrites = true
        controller.scheduleIdentity(ScheduleIdentity(account.username, "测试同学")); runCurrent()
        assertTrue(controller.writeFailure.value)
        controller.storageAvailable(false)
        assertFalse(controller.consumeWriteFailure())
    }

    @Test fun completedLookupWhoseNameCannotBeSavedIsNeverResubmitted() = runTest {
        val store = Store(); val repo = repository(store); val account = add(repo)
        var calls = 0; var blocked = false
        val controller = AccountNameController(backgroundScope, repo, { blocked = true }, networkAuthenticated = { true }, io = StandardTestDispatcher(testScheduler))
        controller.scheduleIdentity(null)
        controller.wifiObserved(route { calls++; store.failWrites = true; ScheduleIdentity(it.username, "测试同学") })
        controller.enterForeground(); runCurrent()
        assertFalse(blocked); assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
        controller.leaveForeground(); controller.enterForeground(); runCurrent()
        assertEquals(1, calls)
        store.failWrites = false
        controller.scheduleIdentity(ScheduleIdentity(account.username, "导入同学")); runCurrent()
        assertEquals("导入同学", repo.state.value!!.accounts.single().label)
        assertEquals(1, calls)
    }

    @Test fun uncertainWriteWithDamagedVaultStillReportsGlobalStorageFailure() = runTest {
        val store = Store(); val repo = repository(store); val account = add(repo)
        var blocked = false
        val controller = AccountNameController(backgroundScope, repo, { blocked = true }, io = StandardTestDispatcher(testScheduler))
        store.failWrites = true; store.corruptOnFailure = true
        controller.scheduleIdentity(ScheduleIdentity(account.username, "测试同学")); runCurrent()
        assertTrue(blocked); assertFalse(controller.writeFailure.value)
        assertThrows(VaultUnavailableException::class.java) { repo.credentialsForLogin(account.id) }
    }

    private fun waitForRepositoryLock(thread: AtomicReference<Thread>) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (thread.get()?.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("Name IO must be waiting for the repository monitor", Thread.State.BLOCKED, thread.get()?.state)
    }

    @Test fun cancelledQueuedLocalWriteCannotChangeAnOpenEditorDraft() {
        val repo = repository(); val account = add(repo)
        val thread = AtomicReference<Thread>()
        val io = Executors.newSingleThreadExecutor { Thread(it, "NameWriteTest").also(thread::set) }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = AccountNameController(scope, repo, { error("Unexpected storage failure") }, io = io)
        try {
            lateinit var editor: AccountDraft
            synchronized(repo) {
                controller.scheduleIdentity(ScheduleIdentity(account.username, "测试同学"))
                waitForRepositoryLock(thread)
                controller.editingAccount(account.id)
                editor = repo.edit(account.id)
            }
            runBlocking { withContext(io) {} } // Wait behind the cancelled IO on the same executor.
            assertEquals(editor.label, repo.state.value!!.accounts.single().label)
            repo.save(editor.copy(password = "edited-fictional-password"))
            controller.editingAccount(null); controller.accountChangeFinished()
            runBlocking { withContext(io) {} }
            assertEquals("测试同学", repo.state.value!!.accounts.single().label)
            assertEquals("edited-fictional-password", repo.edit(account.id).password)
        } finally { scope.cancel(); io.close() }
    }

    @Test fun cancelledQueuedAttemptMarkerIsNotWrittenAndSendsNoCredentials() {
        val repo = repository(); val account = add(repo)
        val thread = AtomicReference<Thread>()
        val io = Executors.newSingleThreadExecutor { Thread(it, "NameAttemptTest").also(thread::set) }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val controller = AccountNameController(scope, repo, { error("Unexpected storage failure") }, networkAuthenticated = { true }, io = io)
        try {
            controller.scheduleIdentity(null)
            controller.wifiObserved(route { calls++; ScheduleIdentity(it.username, "测试同学") })
            synchronized(repo) {
                controller.enterForeground()
                waitForRepositoryLock(thread)
                controller.leaveForeground()
            }
            runBlocking { withContext(io) {} }
            assertEquals(0, calls)
            assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
            assertEquals(account.username, repo.state.value!!.accounts.single().label)
        } finally { scope.cancel(); io.close() }
    }

    @Test fun cancelledQueuedNetworkResultCannotWriteAfterWifiChanges() {
        val repo = repository(); val account = add(repo)
        val thread = AtomicReference<Thread>()
        val io = Executors.newSingleThreadExecutor { Thread(it, "NameResultTest").also(thread::set) }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val result = CompletableDeferred<ScheduleIdentity>()
        val requested = CompletableDeferred<Unit>()
        val controller = AccountNameController(scope, repo, { error("Unexpected storage failure") }, networkAuthenticated = { true }, io = io)
        try {
            controller.scheduleIdentity(null)
            controller.wifiObserved(route { requested.complete(Unit); result.await() })
            controller.enterForeground()
            runBlocking { withTimeout(3_000) { requested.await() } }
            synchronized(repo) {
                result.complete(ScheduleIdentity(account.username, "测试同学"))
                waitForRepositoryLock(thread)
                controller.wifiObserved(WifiObservation.Suspended)
            }
            runBlocking { withContext(io) {} }
            assertEquals(account.username, repo.state.value!!.accounts.single().label)
            assertFalse(repo.state.value!!.accounts.single().nameLookupPending)
        } finally { scope.cancel(); io.close() }
    }
}
