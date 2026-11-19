package io.github.tbeimu.gongxiaozhu.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/** Exercises the real monitor with ordered Android callbacks and no HTTP or school credentials. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [WifiStartupConnectivityShadow::class])
class WifiMonitorStartupTest {
    private lateinit var context: Context
    private lateinit var connectivity: WifiStartupConnectivityShadow

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        connectivity = Shadow.extract(context.getSystemService(ConnectivityManager::class.java))
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun network(id: Int): Network = ShadowNetwork.newInstance(id).also {
        Shadow.extract<ShadowNetwork>(it).setSocketFactory(SocketFactory.getDefault())
    }

    private fun TestScope.observe(values: MutableList<WifiConnection?>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            WifiMonitor(context).connections.collect { values += it }
        }

    private fun TestScope.settleCallbacks() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    @Test fun disconnectDuringTheInitialSnapshotCannotLeaveAStaleWifi() = runTest {
        val original = network(101)
        connectivity.connect(original, active = true)
        connectivity.afterNextLinkSnapshot = { connectivity.disconnect(original) }
        val values = mutableListOf<WifiConnection?>()
        val observer = observe(values)
        try {
            settleCallbacks()
            assertTrue("The monitor must publish its settled connection state", values.isNotEmpty())
            assertNull("A WiFi lost while starting observation must not stay connected", values.last())
        } finally { observer.cancelAndJoin() }
        assertEquals(0, connectivity.activeCallbackCount)
    }

    @Test fun switchingDuringTheInitialSnapshotSelectsOnlyTheReplacementWifi() = runTest {
        val original = network(101)
        val replacement = network(102)
        connectivity.connect(original, active = true)
        connectivity.afterNextLinkSnapshot = {
            connectivity.disconnect(original)
            // A captive WiFi need not become Android's default, validated network.
            connectivity.connect(replacement, active = false)
        }
        val values = mutableListOf<WifiConnection?>()
        val observer = observe(values)
        try {
            settleCallbacks()
            assertNotNull(values.lastOrNull())
            assertTrue("Only the replacement WiFi may be selected after callbacks settle",
                values.last()!!.key.startsWith("${replacement.networkHandle}:"))
        } finally { observer.cancelAndJoin() }
        assertEquals(0, connectivity.activeCallbackCount)
    }

    @Test fun aStableWifiIsPublishedWithoutNeedingALaterNetworkChange() = runTest {
        val original = network(101)
        connectivity.connect(original, active = true)
        val values = mutableListOf<WifiConnection?>()
        val observer = observe(values)
        try {
            settleCallbacks()
            assertNotNull(values.lastOrNull())
            assertTrue(values.last()!!.key.startsWith("${original.networkHandle}:"))
            assertEquals(1, connectivity.activeCallbackCount)
        } finally { observer.cancelAndJoin() }
        assertEquals(1, connectivity.unregisterCount)
        assertEquals(0, connectivity.activeCallbackCount)
    }

    @Test fun noWifiPublishesAConfirmedEmptySnapshot() = runTest {
        val values = mutableListOf<WifiConnection?>()
        val observer = observe(values)
        try {
            settleCallbacks()
            assertEquals(listOf<WifiConnection?>(null), values)
            assertEquals(1, connectivity.activeCallbackCount)
        } finally { observer.cancelAndJoin() }
        assertEquals(0, connectivity.activeCallbackCount)
    }

    @Test fun cancellingObservationReleasesTheCallbackAndStopsFurtherEmissions() = runTest {
        val original = network(101)
        connectivity.connect(original, active = true)
        val values = mutableListOf<WifiConnection?>()
        val observer = observe(values)
        settleCallbacks()
        assertEquals(1, connectivity.activeCallbackCount)
        observer.cancelAndJoin()
        val completed = values.toList()
        connectivity.disconnect(original)
        settleCallbacks()
        assertEquals(completed, values)
        assertEquals(1, connectivity.unregisterCount)
        assertEquals(0, connectivity.activeCallbackCount)
    }

    @Test fun aFailedInitialSnapshotDoesNotLeakANetworkCallback() = runTest {
        connectivity.failNextSnapshot = true
        val error = runCatching {
            WifiMonitor(context).connections.collect { error("A failed snapshot must not publish WiFi") }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(0, connectivity.activeCallbackCount)
    }
}

/** Sends changes only to callbacks registered when they happen, as Android does. */
@Implements(ConnectivityManager::class)
class WifiStartupConnectivityShadow : ShadowConnectivityManager() {
    private data class Registration(val callback: ConnectivityManager.NetworkCallback, val handler: Handler)
    private val callbacks = mutableListOf<Registration>()
    private val defaultCallbacks = mutableListOf<Registration>()
    private val capabilities = linkedMapOf<Network, NetworkCapabilities>()
    private val links = mutableMapOf<Network, LinkProperties>()
    private var active: Network? = null
    var afterNextLinkSnapshot: (() -> Unit)? = null
    var failNextSnapshot = false
    var failDefaultRegistration = false
    var unregisterCount = 0
        private set
    val activeCallbackCount: Int get() = callbacks.size + defaultCallbacks.size

    fun connect(network: Network, active: Boolean, cellular: Boolean = false, foreground: Boolean = true) {
        val value = ShadowNetworkCapabilities.newInstance()
        Shadow.extract<ShadowNetworkCapabilities>(value).apply {
            addTransportType(if (cellular) NetworkCapabilities.TRANSPORT_CELLULAR else NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            if (foreground) addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
            else removeCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
        }
        capabilities[network] = value
        links[network] = LinkProperties()
        callbacks.toList().forEach { announce(it, network, value, links.getValue(network)) }
        if (active) makeDefault(network)
    }

    fun makeDefault(network: Network?) {
        if (active == network) return
        val previous = active
        active = network
        defaultCallbacks.toList().forEach { registration ->
            if (previous != null) post(registration) { it.onLost(previous) }
            if (network != null) post(registration) { it.onAvailable(network) }
        }
    }

    fun disconnect(network: Network) {
        capabilities.remove(network)
        links.remove(network)
        if (active == network) makeDefault(null)
        callbacks.toList().forEach { registration ->
            post(registration) { it.onLost(network) }
        }
    }

    private fun announce(registration: Registration, network: Network, value: NetworkCapabilities, link: LinkProperties) {
        post(registration) { it.onAvailable(network) }
        post(registration) { it.onCapabilitiesChanged(network, value) }
        post(registration) { it.onLinkPropertiesChanged(network, link) }
    }

    private fun post(registration: Registration, event: (ConnectivityManager.NetworkCallback) -> Unit) {
        registration.handler.post {
            if (registration in callbacks || registration in defaultCallbacks) event(registration.callback)
        }
    }

    @Implementation
    public override fun registerNetworkCallback(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback, handler: Handler) {
        val registration = Registration(callback, handler)
        callbacks += registration
        capabilities.toList().forEach { (network, value) ->
            announce(registration, network, value, links.getValue(network))
        }
    }

    @Implementation
    public override fun registerDefaultNetworkCallback(callback: ConnectivityManager.NetworkCallback, handler: Handler) {
        if (failDefaultRegistration) error("Fictional default-network registration failure")
        val registration = Registration(callback, handler)
        defaultCallbacks += registration
        active?.let { network -> post(registration) { it.onAvailable(network) } }
    }

    @Implementation
    public override fun unregisterNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
        val ordinary = callbacks.removeAll { it.callback === callback }
        val default = defaultCallbacks.removeAll { it.callback === callback }
        check(ordinary || default)
        unregisterCount++
    }

    @Implementation
    public override fun getAllNetworks(): Array<Network> {
        if (failNextSnapshot) { failNextSnapshot = false; error("Fictional connectivity snapshot failure") }
        return capabilities.keys.toTypedArray()
    }

    @Implementation
    public override fun getNetworkCapabilities(network: Network): NetworkCapabilities? = capabilities[network]

    @Implementation
    public override fun getLinkProperties(network: Network): LinkProperties? {
        val snapshot = links[network]
        val change = afterNextLinkSnapshot
        afterNextLinkSnapshot = null
        change?.invoke()
        return snapshot
    }

    @Implementation
    public override fun getActiveNetwork(): Network? = active
}
