package io.github.tbeimu.gongxiaozhu.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.tbeimu.gongxiaozhu.schedule.scheduleNetworks
import io.github.tbeimu.gongxiaozhu.update.updateNetworks
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork

data class StartupRoute(val key: String, val kind: String)

/** Runs the same observable contract against both physical-network selectors. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class RoutedNetworkStartupChecks {
    private lateinit var context: Context
    private lateinit var connectivity: WifiStartupConnectivityShadow
    protected abstract fun connections(context: Context): Flow<StartupRoute?>

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        connectivity = Shadow.extract(context.getSystemService(ConnectivityManager::class.java))
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun network(id: Int): Network = ShadowNetwork.newInstance(id).also {
        Shadow.extract<ShadowNetwork>(it).setSocketFactory(SocketFactory.getDefault())
    }

    private fun TestScope.observe(values: MutableList<StartupRoute?>): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connections(context).collect { values += it }
        }

    private fun TestScope.settleCallbacks() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    @Test fun disconnectDuringTheInitialSnapshotCannotLeaveAStaleRoute() = runTest {
        val original = network(101)
        connectivity.connect(original, active = true)
        connectivity.afterNextLinkSnapshot = { connectivity.disconnect(original) }
        val values = mutableListOf<StartupRoute?>()
        val observer = observe(values)
        try {
            settleCallbacks()
            assertTrue(values.isNotEmpty())
            assertNull("A network lost during startup must not remain selected", values.last())
        } finally { observer.cancelAndJoin() }
        assertEquals(0, connectivity.activeCallbackCount)
    }

    @Test fun switchingDuringTheInitialSnapshotSelectsTheReplacementMobileNetwork() = runTest {
        val original = network(101)
        val replacement = network(102)
        connectivity.connect(original, active = true)
        connectivity.afterNextLinkSnapshot = {
            connectivity.disconnect(original)
            connectivity.connect(replacement, active = true, cellular = true)
        }
        val values = mutableListOf<StartupRoute?>()
        val observer = observe(values)
        try {
            settleCallbacks()
            assertNotNull(values.lastOrNull())
            assertTrue("The disconnected WiFi must not outrank the replacement mobile network",
                values.last()!!.key.startsWith("${replacement.networkHandle}:"))
            assertEquals("CELLULAR", values.last()!!.kind)
        } finally { observer.cancelAndJoin() }
        assertEquals(0, connectivity.activeCallbackCount)
    }

    @Test fun losingTheDefaultNetworkDuringTheSnapshotDoesNotKeepABackgroundRoute() = runTest {
        val original = network(101)
        connectivity.connect(original, active = true, foreground = false)
        connectivity.afterNextLinkSnapshot = { connectivity.makeDefault(null) }
        val values = mutableListOf<StartupRoute?>()
        val observer = observe(values)
        try {
            settleCallbacks()
            assertTrue(values.isNotEmpty())
            assertNull("An inactive background network must not retain its old default status", values.last())
        } finally { observer.cancelAndJoin() }
        assertEquals(0, connectivity.activeCallbackCount)
    }

    @Test fun aStableNetworkIsPublishedAndBothCallbacksAreReleased() = runTest {
        val original = network(101)
        connectivity.connect(original, active = true)
        val values = mutableListOf<StartupRoute?>()
        val observer = observe(values)
        try {
            settleCallbacks()
            assertNotNull(values.lastOrNull())
            assertTrue(values.last()!!.key.startsWith("${original.networkHandle}:"))
            assertEquals("WIFI", values.last()!!.kind)
            assertEquals(2, connectivity.activeCallbackCount)
        } finally { observer.cancelAndJoin() }
        assertEquals(2, connectivity.unregisterCount)
        assertEquals(0, connectivity.activeCallbackCount)
    }

    @Test fun noNetworkPublishesAConfirmedEmptySnapshot() = runTest {
        val values = mutableListOf<StartupRoute?>()
        val observer = observe(values)
        try {
            settleCallbacks()
            assertEquals(listOf<StartupRoute?>(null), values)
            assertEquals(2, connectivity.activeCallbackCount)
        } finally { observer.cancelAndJoin() }
        assertEquals(0, connectivity.activeCallbackCount)
    }

    @Test fun cancellingObservationStopsEmissionsAndReleasesBothCallbacks() = runTest {
        val original = network(101)
        connectivity.connect(original, active = true)
        val values = mutableListOf<StartupRoute?>()
        val observer = observe(values)
        settleCallbacks()
        assertEquals(2, connectivity.activeCallbackCount)
        observer.cancelAndJoin()
        val completed = values.toList()
        connectivity.disconnect(original)
        settleCallbacks()
        assertEquals(completed, values)
        assertEquals(2, connectivity.unregisterCount)
        assertEquals(0, connectivity.activeCallbackCount)
    }

    @Test fun aFailedInitialSnapshotDoesNotLeakEitherCallback() = runTest {
        connectivity.failNextSnapshot = true
        val failure = runCatching {
            connections(context).collect { error("A failed snapshot must not publish a route") }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(0, connectivity.activeCallbackCount)
    }

    @Test fun failedDefaultRegistrationReleasesTheOrdinaryNetworkCallback() = runTest {
        connectivity.failDefaultRegistration = true
        val failure = runCatching {
            connections(context).collect { error("A failed registration must not publish a route") }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, connectivity.unregisterCount)
        assertEquals(0, connectivity.activeCallbackCount)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [WifiStartupConnectivityShadow::class])
class ScheduleNetworkStartupTest : RoutedNetworkStartupChecks() {
    override fun connections(context: Context): Flow<StartupRoute?> = scheduleNetworks(context).map { value ->
        value?.let { StartupRoute(it.key, it.kind.name) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [WifiStartupConnectivityShadow::class])
class UpdateNetworkStartupTest : RoutedNetworkStartupChecks() {
    override fun connections(context: Context): Flow<StartupRoute?> = updateNetworks(context).map { value ->
        value?.let { StartupRoute(it.key, it.kind.name) }
    }
}
