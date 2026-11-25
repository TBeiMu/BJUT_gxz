package io.github.tbeimu.gongxiaozhu.academic

import android.content.Context
import android.util.AtomicFile
import io.github.tbeimu.gongxiaozhu.data.AesGcmVaultCipher
import io.github.tbeimu.gongxiaozhu.data.AndroidVaultKeySource
import io.github.tbeimu.gongxiaozhu.data.VaultCipher
import io.github.tbeimu.gongxiaozhu.data.VaultStore
import io.github.tbeimu.gongxiaozhu.data.readBounded
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val ACADEMIC_CREDENTIALS_MAX_BYTES = 16 * 1024

/** One explicitly remembered, school-verified login; never a school web session. */
@Serializable
data class AcademicCredentials(val studentId: String, val username: String, val password: String, val schema: Int = 1) {
    fun matches(account: String) = account.trim().isNotEmpty() && username.equals(account.trim(), ignoreCase = true)
    override fun toString() = "AcademicCredentials(<redacted>)"
}

class AcademicCredentialsRepository(private val store: VaultStore, private val cipher: VaultCipher) {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }

    suspend fun load(): AcademicCredentials? = mutex.withLock { read() }

    suspend fun save(value: AcademicCredentials, isCurrent: () -> Boolean = { true }) = mutex.withLock {
        guard(isCurrent)
        validate(value)
        val previous = store.read()
        // A damaged existing vault must be explicitly forgotten, not silently replaced.
        if (previous != null) decode(previous)
        val plaintext = json.encodeToString(value).encodeToByteArray()
        try {
            val envelope = cipher.encrypt(plaintext, requireExistingKey = previous != null)
            require(envelope.size in 32..ACADEMIC_CREDENTIALS_MAX_BYTES)
            guard(isCurrent)
            check(equalBytes(previous, store.read()))
            guard(isCurrent)
            store.write(envelope)
        } finally { plaintext.fill(0) }
    }

    suspend fun clear(isCurrent: () -> Boolean = { true }) = mutex.withLock {
        guard(isCurrent)
        store.clear()
        check(store.read() == null)
    }

    private fun read() = store.read()?.let(::decode)
    private fun decode(bytes: ByteArray): AcademicCredentials {
        require(bytes.size in 32..ACADEMIC_CREDENTIALS_MAX_BYTES)
        val plaintext = cipher.decrypt(bytes)
        return try {
            require(plaintext.size <= ACADEMIC_CREDENTIALS_MAX_BYTES - 32)
            json.decodeFromString<AcademicCredentials>(plaintext.decodeToString(throwOnInvalidSequence = true)).also(::validate)
        } finally { plaintext.fill(0) }
    }
    private fun validate(value: AcademicCredentials) {
        require(value.schema == 1 && value.studentId.length in 3..64 && value.username.length in 1..128)
        require(value.studentId == value.studentId.trim() && value.username == value.username.trim())
        require(value.studentId.equals(value.username, ignoreCase = true) && value.password.length in 1..512)
    }
    private suspend fun guard(isCurrent: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Academic credentials retired")
    }
    private fun equalBytes(a: ByteArray?, b: ByteArray?) = if (a == null) b == null else b != null && a.contentEquals(b)
}

internal class AndroidAcademicCredentialsStore(context: Context, filename: String = "academic-credentials.vault") : VaultStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, filename))
    override fun read(): ByteArray? = try {
        file.openRead().use { it.readBounded(ACADEMIC_CREDENTIALS_MAX_BYTES) }
    } catch (error: FileNotFoundException) { if (file.baseFile.exists()) throw error else null }
    override fun write(bytes: ByteArray) {
        require(bytes.size in 32..ACADEMIC_CREDENTIALS_MAX_BYTES)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    override fun clear() { file.delete(); check(!file.baseFile.exists()) }
}

fun createAcademicCredentialsRepository(context: Context) = AcademicCredentialsRepository(
    AndroidAcademicCredentialsStore(context.applicationContext),
    AesGcmVaultCipher(AndroidVaultKeySource("gongxiaozhu.academic.credentials.v1")))
