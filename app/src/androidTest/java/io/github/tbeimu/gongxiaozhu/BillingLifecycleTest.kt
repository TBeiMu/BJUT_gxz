package io.github.tbeimu.gongxiaozhu

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.billing.BillingController
import io.github.tbeimu.gongxiaozhu.billing.BillingMetric
import io.github.tbeimu.gongxiaozhu.billing.BillingSession
import io.github.tbeimu.gongxiaozhu.billing.BillingSnapshot
import io.github.tbeimu.gongxiaozhu.billing.OnlineDevice
import io.github.tbeimu.gongxiaozhu.billing.createBillingCacheRepository
import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.AppSettings
import io.github.tbeimu.gongxiaozhu.data.createAccountRepository
import io.github.tbeimu.gongxiaozhu.network.CampusAuthenticator
import io.github.tbeimu.gongxiaozhu.network.CampusLogin
import io.github.tbeimu.gongxiaozhu.network.CampusLoginController
import io.github.tbeimu.gongxiaozhu.network.CampusLoginRuntime
import io.github.tbeimu.gongxiaozhu.network.LoginResult
import io.github.tbeimu.gongxiaozhu.network.PortalTransport
import io.github.tbeimu.gongxiaozhu.network.WifiConnection
import io.github.tbeimu.gongxiaozhu.network.WifiObservation
import io.github.tbeimu.gongxiaozhu.ui.AppViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real runtime and lifecycle, but never sends billing credentials to the school. */
@RunWith(AndroidJUnit4::class)
class BillingLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var runtime: CampusLoginRuntime
    private lateinit var model: AppViewModel
    private lateinit var billingController: BillingController
    private lateinit var wifiKey: String
    private lateinit var cacheAccountId: String
    private lateinit var cachedBytes: ByteArray
    private var loadedAt: Long? = null
    @Volatile private var sessionClosed = false
    private val queries = AtomicInteger()
    private val offlineTips = AtomicInteger()
    private val snapshot = BillingSnapshot(mapOf(
        "已用流量" to BillingMetric("12", "GB"), "可用流量" to BillingMetric("38", "GB"),
        "消费保护" to BillingMetric("50", "元"), "账户余额" to BillingMetric("20", "元")),
        listOf(OnlineDevice("fictional-device", "192.0.2.1", "", "02:00:00:00:00:01", "", 10)))

    @Before fun setup() {
        check(context.packageName.endsWith(".debug"))
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        createAccountRepository(context).apply {
            reset(); load()
            save(AccountDraft(label = "资费回归测试", username = "fictional-lifecycle", password = "fictional-password"))
            settings(AppSettings(autoLogin = false))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        runtime = CampusLoginRuntime.get(context)
        scenario.onActivity { model = ViewModelProvider(it).get(AppViewModel::class.java) }
        waitFor { !model.state.value.loading && currentWifi() != null }
        wifiKey = currentWifi()!!.key
        main {
            billingController = AppViewModel::class.java.getDeclaredField("billingController").apply { isAccessible = true }
                .get(model) as BillingController
            val session = object : BillingSession {
                override suspend fun refresh(credentials: AccountDraft, code: String?): BillingSnapshot {
                    queries.incrementAndGet()
                    return snapshot
                }
                override suspend fun offlineTip(): String { offlineTips.incrementAndGet(); return "虚构提示" }
                override suspend fun offline(device: OnlineDevice) { error("No device logout in this test") }
                override fun close() { sessionClosed = true }
            }
            billingController.wifiChanged(null)
            billingController.wifiChanged(WifiConnection(wifiKey, PortalTransport { "" }) { session })
            billingController.query()
        }
        waitFor { model.state.value.billing.snapshot == snapshot && !model.state.value.billing.running }
        loadedAt = model.state.value.billing.loadedAt
        assertNotNull(loadedAt)
        cacheAccountId = checkNotNull(model.state.value.data.defaultAccountId)
        // Read through the same repository lock until its asynchronous AtomicFile write finishes.
        waitFor { runtime.billingCache.load(cacheAccountId)?.let {
            it.loadedAt == loadedAt && it.snapshot == displayOnlySnapshot()
        } == true }
        cachedBytes = cacheFile().readBytes()
    }

    @After fun cleanup() {
        if (::runtime.isInitialized) {
            main { runtime.controller.cancel() }
            waitFor { !runtime.controller.state.value.running }
            main { replaceAuthenticator(CampusLogin()) }
        }
        if (::scenario.isInitialized) scenario.close()
        shell("svc wifi enable")
        createAccountRepository(context).reset()
    }

    @Test fun homeAndReturnOnSameWifiKeepMetricsDevicesAndTimestampButClearSession() {
        scenario.moveToState(Lifecycle.State.CREATED)
        waitFor { runtime.connections.value == WifiObservation.Suspended }
        instrumentation.waitForIdleSync()
        assertTrue(sessionClosed)
        assertSavedDisplay()
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor { currentWifi() != null }
        assertEquals(wifiKey, currentWifi()!!.key)
        assertSavedDisplay()
    }

    @Test fun authenticationCompletingInBackgroundDoesNotEraseBilling() {
        val result = CompletableDeferred<LoginResult>()
        val entered = CompletableDeferred<Unit>()
        val networkIdentityChanged = AtomicBoolean(false)
        val observation = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var taskId: String? = null
        // These emulator fixtures have working internet; a merely present WiFi can still be changing DHCP links.
        waitFor(diagnostics = { "等待模拟器实体 WiFi 完成系统验证；" + authenticationDiagnostics() }) {
            currentWifi()?.internetValidated == true
        }
        try {
            main {
                assertFalse("认证前账号界面存储必须可用；" + authenticationDiagnostics(), model.state.value.storageBlocked)
                assertFalse("认证前共享账号存储必须可用；" + authenticationDiagnostics(), runtime.storageBlocked.value)
                val startingNetworkKey = checkNotNull(currentWifi()).key
                observation.launch { runtime.connections.collect { value ->
                    if (value is WifiObservation.Current && value.connection?.key != startingNetworkKey) networkIdentityChanged.set(true)
                } }
                replaceAuthenticator(CampusAuthenticator { _, _, _, _ -> entered.complete(Unit); result.await() })
                runtime.controller.login()
                taskId = runtime.controller.state.value.taskId
            }
            assertNotNull("必须创建本次虚构认证任务；networkIdentityChanged=" + networkIdentityChanged.get() + "；" + authenticationDiagnostics(), taskId)
            waitFor(diagnostics = { "等待真实认证服务握手；entered=" + entered.isCompleted +
                ", sameTask=" + (taskId == runtime.controller.state.value.taskId) +
                ", networkIdentityChanged=" + networkIdentityChanged.get() + "；" + authenticationDiagnostics() }) {
                entered.isCompleted && runtime.controller.state.value.running && taskId == runtime.controller.state.value.taskId
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue("服务已接管且虚构认证未完成时仍须监听 WiFi；networkIdentityChanged=" + networkIdentityChanged.get() +
                "；" + authenticationDiagnostics(), runtime.connections.value is WifiObservation.Current)
            result.complete(LoginResult("虚构后台成功", completed = true))
            waitFor { runtime.connections.value == WifiObservation.Suspended }
            instrumentation.waitForIdleSync()
            assertTrue(sessionClosed)
            assertSavedDisplay()
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor { currentWifi() != null }
            assertSavedDisplay()
        } finally { observation.cancel() }
    }

    @Test fun realDisconnectWhileObservationIsPausedKeepsHistoricalDisplayAndCacheOnReturn() {
        scenario.moveToState(Lifecycle.State.CREATED)
        waitFor { runtime.connections.value == WifiObservation.Suspended }
        assertTrue(sessionClosed)
        shell("svc wifi disable")
        waitForNoPhysicalWifi()
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor { runtime.connections.value == WifiObservation.Current(null) && !model.state.value.billing.devicesActionable }
        instrumentation.waitForIdleSync()
        assertSavedDisplay()
        assertSavedCache()
        assertDeviceActionsUnavailable()
        assertEquals(1, queries.get())
    }

    @Test fun realDisconnectAndReconnectInForegroundKeepHistoricalDisplayWithoutAnotherQuery() {
        assertTrue(model.state.value.billing.devicesActionable)
        assertFalse(sessionClosed)
        shell("svc wifi disable")
        waitForNoPhysicalWifi()
        waitFor { runtime.connections.value == WifiObservation.Current(null) && !model.state.value.billing.devicesActionable }
        instrumentation.waitForIdleSync()
        assertTrue(sessionClosed)
        assertSavedDisplay()
        assertSavedCache()
        assertDeviceActionsUnavailable()
        shell("svc wifi enable")
        waitFor { currentWifi() != null }
        instrumentation.waitForIdleSync()
        assertSavedDisplay()
        assertSavedCache()
        assertDeviceActionsUnavailable()
        assertEquals(1, queries.get())
    }

    private fun assertSavedDisplay() {
        assertEquals(snapshot, model.state.value.billing.snapshot)
        assertEquals(loadedAt, model.state.value.billing.loadedAt)
        assertFalse(model.state.value.billing.running)
    }
    private fun displayOnlySnapshot() = snapshot.copy(devices = snapshot.devices.mapIndexed { index, device ->
        device.copy(sessionId = "cached-device-$index")
    })
    private fun cacheFile() = File(context.noBackupFilesDir, "billing-cache.vault")
    private fun assertSavedCache() {
        assertArrayEquals("Disconnecting or reconnecting WiFi must not rewrite or remove historical billing", cachedBytes, cacheFile().readBytes())
        val restored = createBillingCacheRepository(context).load(cacheAccountId)
        assertNotNull(restored)
        assertEquals(cacheAccountId, restored!!.accountId)
        assertEquals(displayOnlySnapshot(), restored.snapshot)
        assertEquals(loadedAt, restored.loadedAt)
    }
    private fun assertDeviceActionsUnavailable() {
        assertFalse(model.state.value.billing.devicesActionable)
        main { billingController.requestOffline("fictional-device"); billingController.confirmOffline() }
        instrumentation.waitForIdleSync()
        assertNull(model.state.value.billing.confirmation)
        assertEquals(0, offlineTips.get())
    }
    @Suppress("DEPRECATION")
    private fun waitForNoPhysicalWifi() {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        waitFor { manager.allNetworks.none { manager.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true } }
    }
    private fun currentWifi() = (runtime.connections.value as? WifiObservation.Current)?.connection
    private fun authenticationDiagnostics(): String {
        val login = runtime.controller.state.value
        val connection = when (val observed = runtime.connections.value) {
            WifiObservation.Suspended -> "suspended"
            is WifiObservation.Current -> if (observed.connection == null) "current-without-wifi" else "current-with-wifi"
        }
        return "running=" + login.running + ", outcome=" + login.outcome + ", message=" + login.message +
            ", connection=" + connection + ", accountsLoading=" + model.state.value.loading +
            ", uiStorageBlocked=" + model.state.value.storageBlocked + ", runtimeStorageBlocked=" + runtime.storageBlocked.value
    }
    private fun replaceAuthenticator(value: CampusAuthenticator) {
        CampusLoginController::class.java.getDeclaredField("authenticator").apply { isAccessible = true }.set(runtime.controller, value)
    }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .use { it.readBytes(); Unit }
    private fun waitFor(diagnostics: () -> String = { "等待资费生命周期状态超时" }, predicate: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000
        while (!predicate() && System.nanoTime() < end) Thread.sleep(50)
        assertTrue(diagnostics(), predicate())
    }
}
