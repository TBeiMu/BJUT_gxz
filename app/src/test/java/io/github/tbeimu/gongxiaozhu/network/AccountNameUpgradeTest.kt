package io.github.tbeimu.gongxiaozhu.network

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.AccountRepository
import io.github.tbeimu.gongxiaozhu.data.AppSettings
import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import io.github.tbeimu.gongxiaozhu.data.VaultCipher
import io.github.tbeimu.gongxiaozhu.data.VaultStore
import io.github.tbeimu.gongxiaozhu.schedule.ScheduleIdentity
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Version changes use independent repository owners, without any school HTTP or real credentials. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountNameUpgradeTest {
    private class Store : VaultStore {
        var bytes: ByteArray? = null
        var writes = 0
            private set
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf(); writes++ }
        override fun clear() { bytes = null }
    }

    private fun repository(store: Store, version: Int) = AccountRepository(store, object : VaultCipher {
        override fun encrypt(plaintext: ByteArray, requireExistingKey: Boolean) = plaintext.copyOf()
        override fun decrypt(envelope: ByteArray) = envelope.copyOf()
        override fun clearKey() {}
    }, currentVersionCode = version).apply { load() }

    private fun account(user: String, label: String = "") =
        AccountDraft(label = label, username = user, password = "fictional-upgrade-password")

    @Test fun eachUpgradeRenewsOneAttemptButCurrentCampusSuccessStillGatesEveryQuery() = runTest {
        val store = Store()
        val version37 = repository(store, 37)
        val saved = version37.save(account("fictional-upgrade-student")).accounts.single()
        assertNotNull(version37.beginNameLookup(saved.id))
        assertFalse(version37.state.value!!.accounts.single().nameLookupPending)
        val consumed37 = store.bytes!!.copyOf()
        val writesBeforeUpgrade = store.writes
        val version38 = repository(store, 38)
        assertTrue(version38.state.value!!.accounts.single().nameLookupPending)
        assertArrayEquals(consumed37, store.bytes)
        assertEquals(writesBeforeUpgrade, store.writes)

        val dispatcher = StandardTestDispatcher(testScheduler)
        var calls = 0
        val wifi = WifiConnection("fictional-upgrade-wifi", PortalTransport { error("No HTTP expected") },
            AccountNameLookup { calls++; throw IOException("Fictional name lookup failure") }, internetValidated = true)
        val scope38 = CoroutineScope(SupervisorJob() + dispatcher)
        var successful = false
        val starts = mutableListOf<String>()
        val login = CampusLoginController(scope38, CampusAuthenticator { _, _, _, _ ->
            if (successful) LoginResult("虚构校园认证成功", completed = true) else LoginResult("虚构校园认证失败")
        }, credentials = { error("No real credentials expected") }, startService = starts::add,
            storageFailure = { error("Unexpected storage failure") })
        val names38 = AccountNameController(scope38, version38, { error("Unexpected storage failure") },
            login::isAuthenticatedOn, dispatcher)
        try {
            scope38.launch(UnconfinedTestDispatcher(testScheduler)) {
                login.state.collect { names38.authenticationChanged() }
            }
            names38.scheduleIdentity(null)
            names38.wifiObserved(WifiObservation.Current(wifi))
            names38.enterForeground()
            login.configure(ready = true, automatic = false, mode = NetworkMode.PUBLIC_WIFI, accountId = saved.id)
            login.wifiChanged(wifi); login.enterForeground(); runCurrent()
            assertEquals(0, calls)
            assertEquals(writesBeforeUpgrade, store.writes)
            assertArrayEquals(consumed37, store.bytes)
            repeat(2) {
                login.login()
                val id = starts.last()
                assertTrue(login.serviceStarted(id)); runCurrent()
                assertEquals(LoginTaskOutcome.FAILED, login.state.value.outcome)
                login.serviceStopped(id)
                assertEquals(0, calls)
                assertEquals(writesBeforeUpgrade, store.writes)
                assertArrayEquals(consumed37, store.bytes)
                assertTrue(version38.state.value!!.accounts.single().nameLookupPending)
            }
            successful = true
            login.login()
            val id = starts.last()
            assertTrue(login.serviceStarted(id)); runCurrent()
            assertTrue(login.state.value.completed)
            assertEquals(1, calls)
            assertEquals(writesBeforeUpgrade + 1, store.writes)
            assertFalse(version38.state.value!!.accounts.single().nameLookupPending)
            repeat(3) { names38.authenticationChanged(); names38.leaveForeground(); names38.enterForeground(); runCurrent() }
            assertEquals(1, calls)
            assertEquals(writesBeforeUpgrade + 1, store.writes)
            login.serviceStopped(id)
        } finally { scope38.cancel(); runCurrent() }

        val consumed38 = store.bytes!!.copyOf()
        val writesAfter38 = store.writes
        val restarted38 = repository(store, 38)
        assertFalse(restarted38.state.value!!.accounts.single().nameLookupPending)
        val restartScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val names = AccountNameController(restartScope, restarted38, { error("Unexpected storage failure") },
                networkAuthenticated = { it == wifi.key }, io = dispatcher)
            names.scheduleIdentity(null); names.wifiObserved(WifiObservation.Current(wifi)); names.enterForeground(); runCurrent()
            assertEquals(1, calls)
            assertEquals(writesAfter38, store.writes)
            assertArrayEquals(consumed38, store.bytes)
        } finally { restartScope.cancel(); runCurrent() }

        val version39 = repository(store, 39)
        assertTrue(version39.state.value!!.accounts.single().nameLookupPending)
        assertEquals(writesAfter38, store.writes)
        assertArrayEquals(consumed38, store.bytes)
        val scope39 = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val names = AccountNameController(scope39, version39, { error("Unexpected storage failure") },
                networkAuthenticated = { it == wifi.key }, io = dispatcher)
            names.scheduleIdentity(null); names.wifiObserved(WifiObservation.Current(wifi)); names.enterForeground(); runCurrent()
            assertEquals(2, calls)
            assertFalse(version39.state.value!!.accounts.single().nameLookupPending)
            names.authenticationChanged(); runCurrent()
            assertEquals(2, calls)
        } finally { scope39.cancel(); runCurrent() }
    }

    @Test fun upgradedAccountsPreferLocalIdentityAndNeverReplaceVerifiedNamesOrCustomRemarks() = runTest {
        val store = Store()
        val version37 = repository(store, 37)
        val pending = version37.save(account("fictional-local-student")).accounts.last()
        assertNotNull(version37.beginNameLookup(pending.id))
        val named = version37.save(account("fictional-named-student")).accounts.last()
        val namedRequest = version37.beginNameLookup(named.id)!!
        assertTrue(version37.completeNameLookup(namedRequest, named.username, "已有同学"))
        val custom = version37.save(account("fictional-custom-student", "我的备注")).accounts.last()
        val explicit = version37.save(account("fictional-explicit-student", "fictional-explicit-student")).accounts.last()
        version37.setDefault(custom.id)
        val settings = AppSettings(autoLogin = false, networkMode = NetworkMode.DORM_WIFI)
        version37.settings(settings)
        var calls = 0
        val wifi = WifiConnection("fictional-local-wifi", PortalTransport { error("No HTTP expected") },
            AccountNameLookup { calls++; error("Local or protected names must not query CAS") }, internetValidated = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val version38 = repository(store, 38)
        assertEquals(listOf(pending.id), version38.state.value!!.accounts.filter { it.nameLookupPending }.map { it.id })
        val scope38 = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val names = AccountNameController(scope38, version38, { error("Unexpected storage failure") },
                networkAuthenticated = { it == wifi.key }, io = dispatcher)
            names.scheduleIdentity(ScheduleIdentity(pending.username, "本机课表同学"))
            names.wifiObserved(WifiObservation.Current(wifi)); names.enterForeground(); runCurrent()
            assertEquals(0, calls)
            assertEquals("本机课表同学", version38.state.value!!.accounts.single { it.id == pending.id }.label)
            for (protected in listOf(named, custom, explicit)) {
                names.scheduleIdentity(ScheduleIdentity(protected.username, "不应覆盖的课表姓名")); runCurrent()
                assertEquals(0, calls)
            }
            assertEquals("已有同学", version38.state.value!!.accounts.single { it.id == named.id }.label)
            assertEquals("我的备注", version38.state.value!!.accounts.single { it.id == custom.id }.label)
            assertEquals(explicit.username, version38.state.value!!.accounts.single { it.id == explicit.id }.label)
            assertEquals(custom.id, version38.state.value!!.defaultAccountId)
            assertEquals(settings, version38.state.value!!.settings)
            for (saved in version38.state.value!!.accounts) {
                assertFalse(saved.nameLookupPending)
                assertEquals("fictional-upgrade-password", version38.edit(saved.id).password)
            }
        } finally { scope38.cancel(); runCurrent() }

        val version39 = repository(store, 39)
        assertTrue(version39.state.value!!.accounts.none { it.nameLookupPending })
        val scope39 = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val names = AccountNameController(scope39, version39, { error("Unexpected storage failure") },
                networkAuthenticated = { it == wifi.key }, io = dispatcher)
            names.scheduleIdentity(null); names.wifiObserved(WifiObservation.Current(wifi)); names.enterForeground(); runCurrent()
            assertEquals(0, calls)
            assertEquals(version38.state.value, version39.state.value)
        } finally { scope39.cancel(); runCurrent() }
    }
}
