package io.github.tbeimu.gongxiaozhu.update

import android.app.Notification
import android.content.Intent
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the real service actions with fictional transfers; no system service or network is started. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UpdateNotificationActionTest {
    @get:Rule val temp = TemporaryFolder()
    private val manifest = UpdateManifest(1, "0.5.7", 38, "io.github.tbeimu.gongxiaozhu", 31,
        4096, "0".repeat(64), "虚构更新")

    private fun service(repository: UpdateRepository, permit: String): UpdateDownloadService =
        Robolectric.buildService(UpdateDownloadService::class.java).get().also { service ->
            field(service, "repository", repository)
            field(service, "ownedPermit", permit)
            field(service, "observing", true)
        }

    private fun field(service: UpdateDownloadService, name: String, value: Any) {
        UpdateDownloadService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun notification(service: UpdateDownloadService, state: UpdateUiState): Notification =
        UpdateDownloadService::class.java.getDeclaredMethod("notification", UpdateUiState::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, state, true) as Notification

    private fun cancel(permit: String? = null) = Intent("io.github.tbeimu.gongxiaozhu.CANCEL_UPDATE").apply {
        if (permit != null) putExtra(UpdateDownloadService.DOWNLOAD_PERMIT, permit)
    }

    @Test fun staleOrMissingNotificationPermitCannotCancelANewerDownload() = runTest {
        val source = object : UpdateSource {
            override suspend fun check() = manifest
            override suspend fun download(manifest: UpdateManifest, file: File, etag: String?,
                saveEtag: (String?) -> Unit, progress: (Long) -> Unit) = awaitCancellation()
        }
        val repository = UpdateRepository(backgroundScope, UpdateStore(temp.newFolder()),
            ApkVerifier(37, manifest.packageName, 35, "1".repeat(64)) { null }, 37, 35)
        repository.networkChanged(UpdateNetwork("fictional-wifi", source)); repository.check(); runCurrent()
        val oldPermit = repository.requestDownload()!!
        assertTrue(repository.download(oldPermit)); runCurrent()
        repository.pause(); runCurrent()
        val permit = repository.requestDownload()!!
        assertNotEquals(oldPermit, permit)
        assertTrue(repository.download(permit)); runCurrent()
        val service = service(repository, permit)
        service.onStartCommand(cancel(oldPermit), 0, 1); runCurrent()
        assertEquals(UpdatePhase.DOWNLOADING, repository.state.value.phase)
        service.onStartCommand(cancel(), 0, 2); runCurrent()
        assertEquals(UpdatePhase.DOWNLOADING, repository.state.value.phase)
        service.onStartCommand(cancel(permit), 0, 3); runCurrent()
        assertEquals(UpdatePhase.IDLE, repository.state.value.phase)
    }

    @Test fun notificationActionsKeepIndependentIdentityAndOriginalPermit() = runTest {
        val repository = UpdateRepository(backgroundScope, UpdateStore(temp.newFolder()),
            ApkVerifier(37, manifest.packageName, 35, "1".repeat(64)) { null }, 37, 35)
        val service = service(repository, "fictional-old-permit")
        val state = UpdateUiState(phase = UpdatePhase.DOWNLOADING, manifest = manifest)
        val oldAction = notification(service, state).actions.single().actionIntent
        field(service, "ownedPermit", "fictional-new-permit")
        val newAction = notification(service, state).actions.single().actionIntent
        assertNotEquals(oldAction, newAction)
        assertEquals("fictional-old-permit", shadowOf(oldAction).savedIntent.getStringExtra(UpdateDownloadService.DOWNLOAD_PERMIT))
        assertEquals("fictional-new-permit", shadowOf(newAction).savedIntent.getStringExtra(UpdateDownloadService.DOWNLOAD_PERMIT))
    }

    @Test fun staleServiceOwnerCannotCancelANewerReservedOrRunningDownload() = runTest {
        val source = object : UpdateSource {
            override suspend fun check() = manifest
            override suspend fun download(manifest: UpdateManifest, file: File, etag: String?,
                saveEtag: (String?) -> Unit, progress: (Long) -> Unit) = awaitCancellation()
        }
        val repository = UpdateRepository(backgroundScope, UpdateStore(temp.newFolder()),
            ApkVerifier(37, manifest.packageName, 35, "1".repeat(64)) { null }, 37, 35)
        repository.networkChanged(UpdateNetwork("fictional-wifi", source)); repository.check(); runCurrent()
        val oldPermit = repository.requestDownload()!!
        assertTrue(repository.download(oldPermit)); runCurrent()
        val service = service(repository, oldPermit)
        repository.pause(); runCurrent()
        val newPermit = repository.requestDownload()!!
        service.onStartCommand(cancel(oldPermit), 0, 1); runCurrent()
        assertTrue(repository.state.value.downloadStarting)
        assertTrue(repository.download(newPermit)); runCurrent()
        service.onStartCommand(cancel(oldPermit), 0, 2); runCurrent()
        assertEquals(UpdatePhase.DOWNLOADING, repository.state.value.phase)
    }

    @Test fun unownedStaleCancelStopsOnlyItsOwnStartCommandAndPreservesNewAuthorization() = runTest {
        val source = object : UpdateSource {
            override suspend fun check() = manifest
            override suspend fun download(manifest: UpdateManifest, file: File, etag: String?,
                saveEtag: (String?) -> Unit, progress: (Long) -> Unit) = awaitCancellation()
        }
        val repository = UpdateRepository(backgroundScope, UpdateStore(temp.newFolder()),
            ApkVerifier(37, manifest.packageName, 35, "1".repeat(64)) { null }, 37, 35)
        repository.networkChanged(UpdateNetwork("fictional-wifi", source)); repository.check(); runCurrent()
        val newPermit = repository.requestDownload()!!
        val service = service(repository, "fictional-expired-permit")
        field(service, "observing", false)
        service.onStartCommand(cancel("fictional-expired-permit"), 0, 41); runCurrent()
        assertTrue(repository.state.value.downloadStarting)
        assertEquals(41, shadowOf(service).stopSelfResultId)
        assertTrue(repository.download(newPermit)); runCurrent()
        assertEquals(UpdatePhase.DOWNLOADING, repository.state.value.phase)
    }

    @Test fun rejectedOldDownloadStartStopsOnlyItsOwnCommandAndPreservesNewAuthorization() = runTest {
        val source = object : UpdateSource {
            override suspend fun check() = manifest
            override suspend fun download(manifest: UpdateManifest, file: File, etag: String?,
                saveEtag: (String?) -> Unit, progress: (Long) -> Unit) = awaitCancellation()
        }
        val repository = UpdateRepository(backgroundScope, UpdateStore(temp.newFolder()),
            ApkVerifier(37, manifest.packageName, 35, "1".repeat(64)) { null }, 37, 35)
        repository.networkChanged(UpdateNetwork("fictional-wifi", source)); repository.check(); runCurrent()
        val newPermit = repository.requestDownload()!!
        val service = service(repository, "fictional-expired-permit")
        field(service, "observing", false)
        val oldStart = Intent().putExtra(UpdateDownloadService.DOWNLOAD_PERMIT, "fictional-expired-permit")
        service.onStartCommand(oldStart, 0, 42); runCurrent()
        assertTrue(repository.state.value.downloadStarting)
        assertEquals(42, shadowOf(service).stopSelfResultId)
        assertTrue(repository.download(newPermit)); runCurrent()
        assertEquals(UpdatePhase.DOWNLOADING, repository.state.value.phase)
    }
}
