package io.github.tbeimu.gongxiaozhu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.academic.*
import io.github.tbeimu.gongxiaozhu.data.AesGcmVaultCipher
import io.github.tbeimu.gongxiaozhu.data.AndroidVaultKeySource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AcademicCredentialsAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val filename = "instrumentation-academic-credentials.vault"
    private val alias = "gongxiaozhu.instrumentation.academic.credentials"
    private val file get() = File(context.noBackupFilesDir, filename)
    private fun repository() = AcademicCredentialsRepository(AndroidAcademicCredentialsStore(context, filename),
        AesGcmVaultCipher(AndroidVaultKeySource(alias)))
    private val credentials = AcademicCredentials("fictional-student", "fictional-student", " demo&+%?=密码 ")

    @Before fun setup() = cleanup()
    @After fun cleanup() { runBlocking { repository().clear() }; AndroidVaultKeySource(alias).clear() }

    @Test fun keystoreAndAtomicFileRememberOneVerifiedLoginAcrossRepositoryRecreation() = runBlocking {
        val repository = repository(); repository.save(credentials)
        val first = file.readBytes()
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("fictional-student"))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("demo&+%"))
        assertEquals(credentials, repository().load())
        repository.save(credentials); assertFalse(first.contentEquals(file.readBytes()))
        assertEquals(context.noBackupFilesDir.canonicalFile, file.parentFile!!.canonicalFile)
        repository.clear(); assertFalse(file.exists()); assertNull(repository().load())
    }

    @Test fun lostKeyPreservesEncryptedRecordUntilUserExplicitlyForgetsIt() = runBlocking {
        repository().save(credentials); val original = file.readBytes()
        AndroidVaultKeySource(alias).clear()
        assertTrue(runCatching { repository().load() }.isFailure)
        assertTrue(runCatching { repository().save(credentials) }.isFailure)
        assertArrayEquals(original, file.readBytes())
        repository().clear(); repository().save(credentials)
        assertEquals(credentials, repository().load())
    }

    @Test fun damagedOrOversizedCredentialFileCannotBeSilentlyOverwritten() = runBlocking {
        for (invalid in listOf(byteArrayOf(7, 8, 9), ByteArray(ACADEMIC_CREDENTIALS_MAX_BYTES + 1))) {
            file.writeBytes(invalid)
            assertTrue(runCatching { repository().load() }.isFailure)
            assertTrue(runCatching { repository().save(credentials) }.isFailure)
            assertArrayEquals(invalid, file.readBytes())
            repository().clear()
        }
    }
}
