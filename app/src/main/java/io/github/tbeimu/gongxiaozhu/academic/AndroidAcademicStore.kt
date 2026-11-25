package io.github.tbeimu.gongxiaozhu.academic

import android.content.Context
import android.util.AtomicFile
import io.github.tbeimu.gongxiaozhu.data.AesGcmVaultCipher
import io.github.tbeimu.gongxiaozhu.data.AndroidVaultKeySource
import io.github.tbeimu.gongxiaozhu.data.VaultStore
import io.github.tbeimu.gongxiaozhu.data.readBounded
import java.io.File
import java.io.FileNotFoundException

internal class AndroidAcademicStore(context: Context) : VaultStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "academic.vault"))
    override fun read(): ByteArray? = try {
        file.openRead().use { it.readBounded(ACADEMIC_MAX_BYTES) }
    } catch (error: FileNotFoundException) { if (file.baseFile.exists()) throw error else null }

    override fun write(bytes: ByteArray) {
        require(bytes.size in 32..ACADEMIC_MAX_BYTES)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    override fun clear() { file.delete(); check(!file.baseFile.exists()) }
}

fun createAcademicRepository(context: Context) = AcademicRepository(AndroidAcademicStore(context.applicationContext),
    AesGcmVaultCipher(AndroidVaultKeySource("gongxiaozhu.academic.v1")))
