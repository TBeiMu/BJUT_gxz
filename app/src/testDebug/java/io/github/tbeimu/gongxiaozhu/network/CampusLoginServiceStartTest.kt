package io.github.tbeimu.gongxiaozhu.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.NetworkMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real controller/service handshake with suspended fictional authentication and no network calls. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CampusLoginServiceStartTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Fixture(scope: CoroutineScope, cleanup: CompletableDeferred<Unit>? = null) {
        val starts = mutableListOf<String>()
        var authentications = 0
        var credentialReads = 0
        val controller = CampusLoginController(scope, CampusAuthenticator { _, _, credentials, _ ->
            authentications++
            credentials()
            try { awaitCancellation() }
            finally { if (cleanup != null) withContext(NonCancellable) { cleanup.await() } }
        }, credentials = { id ->
            assertEquals("fictional-account", id)
            credentialReads++
            AccountDraft(id, "测试账号", "fictional-student", "fictional-password")
        }, startService = starts::add, storageFailure = { error("Unexpected fictional storage failure") })

        init {
            controller.configure(ready = true, automatic = false, mode = NetworkMode.PUBLIC_WIFI, accountId = "fictional-account")
            controller.wifiChanged(WifiConnection("fictional-wifi", PortalTransport { error("No HTTP expected") }))
            controller.enterForeground()
        }

        fun request(): String {
            controller.login()
            return starts.last()
        }

        fun service(oldOwner: String? = null): CampusLoginService =
            Robolectric.buildService(CampusLoginService::class.java).get().also { service ->
                CampusLoginService::class.java.getDeclaredField("controller").apply { isAccessible = true }.set(service, controller)
                if (oldOwner != null) CampusLoginService::class.java.getDeclaredField("ownedId")
                    .apply { isAccessible = true }.set(service, oldOwner)
                service.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel("campus_login", "虚构认证进度", NotificationManager.IMPORTANCE_LOW))
            }
    }

    private fun startIntent(id: String) = Intent().putExtra(CampusLoginService.TASK_ID, id)
    private fun cancelIntent(id: String) = startIntent(id).setAction("io.github.tbeimu.gongxiaozhu.CANCEL_LOGIN")

    @Test fun aLateSameTaskStartAfterCompletionStopsItsOwnCommandWithoutAnotherLogin() = runTest {
        val fixture = Fixture(backgroundScope)
        val task = fixture.request()
        val service = fixture.service()
        try {
            service.onStartCommand(startIntent(task), 0, 1)
            runCurrent()
            assertEquals(1, fixture.authentications)
            fixture.controller.cancel(task)
            runCurrent()
            assertFalse(fixture.controller.state.value.running)
            assertEquals(1, shadowOf(service).stopSelfResultId)
            // Android can already know about this queued start when stopping start 1.
            service.onStartCommand(startIntent(task), 0, 2)
            runCurrent()
            assertEquals(2, shadowOf(service).stopSelfResultId)
            assertEquals(1, fixture.authentications)
            assertEquals(1, fixture.credentialReads)
            assertFalse(fixture.controller.state.value.running)
        } finally { service.onDestroy(); runCurrent() }
    }

    @Test fun anOldServiceOwnerCannotBlockTheControllersDifferentPendingTask() = runTest {
        val fixture = Fixture(backgroundScope)
        val task = fixture.request()
        val service = fixture.service(oldOwner = "fictional-retired-owner")
        try {
            service.onStartCommand(startIntent(task), 0, 7)
            runCurrent()
            assertEquals(task, fixture.controller.state.value.taskId)
            assertTrue(fixture.controller.state.value.running)
            assertEquals(1, fixture.authentications)
            assertEquals(1, fixture.credentialReads)
            assertFalse(shadowOf(service).isForegroundStopped)
        } finally { service.onDestroy(); runCurrent() }
    }

    @Test fun aLateSameTaskStartDuringDetachedCleanupStopsItsCommandAndKeepsTheTaskSlot() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, cleanup)
        val task = fixture.request()
        val service = fixture.service()
        try {
            service.onStartCommand(startIntent(task), 0, 1)
            runCurrent()
            service.onTaskRemoved(null)
            runCurrent()
            assertTrue("HTTP cleanup must still own the controller task slot", fixture.controller.state.value.running)
            assertEquals(1, shadowOf(service).stopSelfResultId)
            service.onStartCommand(startIntent(task), 0, 2)
            runCurrent()
            assertEquals(2, shadowOf(service).stopSelfResultId)
            assertTrue(fixture.controller.state.value.running)
            assertEquals(task, fixture.controller.state.value.taskId)
            fixture.controller.login()
            assertEquals(1, fixture.starts.size)
            assertEquals(1, fixture.authentications)
            assertEquals(1, fixture.credentialReads)
        } finally { cleanup.complete(Unit); runCurrent(); service.onDestroy(); runCurrent() }
    }

    @Test fun aLateCancelDuringDetachedCleanupStopsItsCommandAndKeepsTheTaskSlot() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, cleanup)
        val task = fixture.request()
        val service = fixture.service()
        try {
            service.onStartCommand(startIntent(task), 0, 1)
            runCurrent()
            service.onTaskRemoved(null)
            runCurrent()
            assertTrue(fixture.controller.state.value.running)
            assertEquals(1, shadowOf(service).stopSelfResultId)
            service.onStartCommand(cancelIntent(task), 0, 2)
            runCurrent()
            assertEquals(2, shadowOf(service).stopSelfResultId)
            assertTrue("Stopping the idle service must not release the controller's cleanup slot",
                fixture.controller.state.value.running)
            assertEquals(task, fixture.controller.state.value.taskId)
            fixture.controller.login()
            assertEquals(1, fixture.starts.size)
            assertEquals(1, fixture.authentications)
            assertEquals(1, fixture.credentialReads)
        } finally { cleanup.complete(Unit); runCurrent(); service.onDestroy(); runCurrent() }
    }

    @Test fun replaysWhileTheOwnedTaskIsRunningNeverResubmitCredentialsOrStopItsService() = runTest {
        val fixture = Fixture(backgroundScope)
        val task = fixture.request()
        val service = fixture.service()
        try {
            service.onStartCommand(startIntent(task), 0, 1)
            runCurrent()
            service.onStartCommand(startIntent(task), 0, 2)
            service.onStartCommand(startIntent("fictional-expired-task"), 0, 3)
            service.onStartCommand(cancelIntent("fictional-expired-task"), 0, 4)
            runCurrent()
            assertEquals(task, fixture.controller.state.value.taskId)
            assertTrue(fixture.controller.state.value.running)
            assertEquals(1, fixture.authentications)
            assertEquals(1, fixture.credentialReads)
            assertFalse(shadowOf(service).isForegroundStopped)
        } finally { service.onDestroy(); runCurrent() }
    }
}
