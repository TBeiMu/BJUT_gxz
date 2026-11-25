package io.github.tbeimu.gongxiaozhu.academic

import io.github.tbeimu.gongxiaozhu.data.AesGcmVaultCipher
import io.github.tbeimu.gongxiaozhu.data.VaultCipher
import io.github.tbeimu.gongxiaozhu.data.VaultKeySource
import io.github.tbeimu.gongxiaozhu.data.VaultStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AcademicCredentialsRepositoryTest {
    private class Store : VaultStore {
        var bytes: ByteArray? = null
        var failWrite = false
        override fun read() = bytes?.copyOf()
        override fun write(bytes: ByteArray) { if (failWrite) error("Fictional atomic failure"); this.bytes = bytes.copyOf() }
        override fun clear() { bytes = null }
    }
    private class Keys : VaultKeySource {
        var key: SecretKey? = null
        override fun encryptionKey(): SecretKey = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        override fun decryptionKey() = key ?: error("Fictional unavailable key")
        override fun clear() { key = null }
    }
    private val sample = AcademicCredentials("fictional-student", "fictional-student", "fictional ! +&密码")

    @Test fun oneEncryptedRecordUsesFreshIvAndNeverPrintsOrStoresPlainCredentials() = runBlocking {
        val store = Store(); val keys = Keys(); val cipher = AesGcmVaultCipher(keys)
        val repository = AcademicCredentialsRepository(store, cipher)
        assertNull(repository.load())
        repository.save(sample); val first = store.bytes!!.clone()
        assertEquals(sample, AcademicCredentialsRepository(store, cipher).load())
        repository.save(sample); assertFalse(first.contentEquals(store.bytes!!))
        assertFalse(store.bytes!!.toString(Charsets.ISO_8859_1).contains(sample.password))
        assertFalse(sample.toString().contains(sample.username)); assertFalse(sample.toString().contains(sample.password))
        assertTrue(sample.matches(" FICTIONAL-STUDENT ")); assertFalse(sample.matches("fictional-other")); assertFalse(sample.matches(""))
        repository.clear(); assertNull(repository.load())
    }

    @Test fun corruptOversizeAndMissingKeyNeverSilentlyReplaceAnExistingRecord() = runBlocking {
        val store = Store(); val keys = Keys(); val repository = AcademicCredentialsRepository(store, AesGcmVaultCipher(keys))
        for (invalid in listOf(ByteArray(36) { 1 }, ByteArray(ACADEMIC_CREDENTIALS_MAX_BYTES + 1))) {
            store.bytes = invalid
            assertTrue(runCatching { repository.load() }.isFailure)
            assertTrue(runCatching { repository.save(sample) }.isFailure)
            assertArrayEquals(invalid, store.bytes)
        }
        repository.clear(); repository.save(sample); val before = store.bytes!!.clone(); keys.key = null
        assertTrue(runCatching { repository.load() }.isFailure)
        assertTrue(runCatching { repository.save(sample) }.isFailure)
        assertArrayEquals(before, store.bytes); assertNull(keys.key)
        repository.clear(); assertNull(store.bytes)
    }

    @Test fun failedAtomicWriteAndRetiredSaveOrForgetKeepPreviousBytes() = runBlocking {
        val store = Store(); val cipher = AesGcmVaultCipher(Keys()); val repository = AcademicCredentialsRepository(store, cipher)
        repository.save(sample); val before = store.bytes!!.clone()
        store.failWrite = true
        assertTrue(runCatching { repository.save(sample.copy(password = "new-fictional")) }.isFailure)
        assertArrayEquals(before, store.bytes)
        store.failWrite = false
        assertTrue(runCatching { repository.save(sample) { false } }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { repository.clear { false } }.exceptionOrNull() is CancellationException)
        assertArrayEquals(before, store.bytes)
        var current = true
        val retiringCipher = object : VaultCipher by cipher {
            override fun encrypt(plaintext: ByteArray, requireExistingKey: Boolean): ByteArray =
                cipher.encrypt(plaintext, requireExistingKey).also { current = false }
        }
        assertTrue(runCatching { AcademicCredentialsRepository(store, retiringCipher).save(sample) { current } }.exceptionOrNull() is CancellationException)
        assertArrayEquals(before, store.bytes)
    }

    @Test fun onlyVerifiedBoundedCredentialFieldsCanBePersisted() = runBlocking {
        val store = Store(); val repository = AcademicCredentialsRepository(store, AesGcmVaultCipher(Keys()))
        listOf(sample.copy(studentId = "different-student"), sample.copy(password = ""), sample.copy(password = "x".repeat(513)),
            sample.copy(schema = 2), sample.copy(username = " fictional-student")).forEach { invalid ->
            assertTrue(runCatching { repository.save(invalid) }.isFailure); assertNull(store.bytes)
        }
    }
}
