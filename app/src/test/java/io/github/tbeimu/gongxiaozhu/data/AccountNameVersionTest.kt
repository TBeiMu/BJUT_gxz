package io.github.tbeimu.gongxiaozhu.data

import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Test

/** Uses real authenticated encryption with fictional accounts and no Android or HTTP calls. */
class AccountNameVersionTest {
    private class MemoryStore : VaultStore {
        var bytes: ByteArray? = null
        var failWrite = false
        var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(bytes: ByteArray) {
            writes++
            if (failWrite) throw IOException("Fictional marker write failure")
            this.bytes = bytes.copyOf()
        }
        override fun clear() { bytes = null }
    }
    private class Keys : VaultKeySource {
        private var key: SecretKey? = null
        override fun encryptionKey(): SecretKey = key ?: KeyGenerator.getInstance("AES")
            .apply { init(256) }.generateKey().also { key = it }
        override fun decryptionKey(): SecretKey = checkNotNull(key)
        override fun clear() { key = null }
    }
    private class Fixture {
        val store = MemoryStore()
        val cipher = AesGcmVaultCipher(Keys())
        fun repository(version: Int, encryption: VaultCipher = cipher) =
            AccountRepository(store, encryption, currentVersionCode = version).apply { load() }
        fun legacy(text: String) { store.bytes = cipher.encrypt(text.encodeToByteArray()) }
    }
    private fun add(repo: AccountRepository, username: String = "fictional-student", label: String = "") =
        repo.save(AccountDraft(label = label, username = username, password = "  fictional&password=+中文  ")).accounts.last()

    @Test fun eachUpgradeGrantsOneAttemptButSameVersionReloadAndRestartNeverRestoreIt() {
        val f = Fixture(); val first = f.repository(37); val account = add(first)
        assertNotNull(first.beginNameLookup(account.id)); assertNull(first.beginNameLookup(account.id))
        val bytes = f.store.bytes!!.copyOf(); val writes = f.store.writes
        val upgraded = f.repository(38)
        assertTrue(upgraded.state.value!!.accounts.single().nameLookupPending)
        assertArrayEquals(bytes, f.store.bytes); assertEquals(writes, f.store.writes)
        assertNotNull(upgraded.beginNameLookup(account.id))
        repeat(3) {
            assertFalse(upgraded.load().accounts.single().nameLookupPending)
            val restarted = f.repository(38)
            assertFalse(restarted.state.value!!.accounts.single().nameLookupPending)
            assertNull(restarted.beginNameLookup(account.id))
        }
        val next = f.repository(39)
        assertTrue(next.state.value!!.accounts.single().nameLookupPending)
        assertNotNull(next.beginNameLookup(account.id)); assertNull(next.beginNameLookup(account.id))
    }

    @Test fun skippedVersionsGrantOnlyOneAttemptForTheInstalledVersion() {
        val f = Fixture(); val old = f.repository(37); val account = add(old)
        old.beginNameLookup(account.id)
        val jumped = f.repository(45)
        assertTrue(jumped.state.value!!.accounts.single().nameLookupPending)
        assertNotNull(jumped.beginNameLookup(account.id)); assertNull(jumped.beginNameLookup(account.id))
        assertFalse(f.repository(45).state.value!!.accounts.single().nameLookupPending)
    }

    @Test fun legacyAccountWithoutNameFieldsGainsEligibilityWithoutAWritingMigration() {
        val f = Fixture()
        f.legacy("""{"version":1,"accounts":[{"id":"legacy","label":"fictional-student","username":"fictional-student","password":"fictional-password"}],"defaultAccountId":"legacy"}""")
        val bytes = f.store.bytes!!.copyOf(); val repo = f.repository(37)
        assertTrue(repo.state.value!!.accounts.single().canFillName)
        assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
        assertArrayEquals(bytes, f.store.bytes); assertEquals(0, f.store.writes)
        assertEquals("fictional-password", repo.beginNameLookup("legacy")!!.password)
        assertFalse(f.repository(37).state.value!!.accounts.single().nameLookupPending)
    }

    @Test fun consumedV056AccountWithoutVersionFieldGetsOneAttemptAndRemainsEncrypted() {
        val f = Fixture()
        f.legacy("""{"version":1,"accounts":[{"id":"v056","label":"fictional-student","username":"fictional-student","password":"fictional-password","customLabel":false,"nameLookupPending":false}],"defaultAccountId":"v056"}""")
        val bytes = f.store.bytes!!.copyOf(); val repo = f.repository(37)
        assertTrue(repo.state.value!!.accounts.single().nameLookupPending)
        assertArrayEquals(bytes, f.store.bytes)
        assertNotNull(repo.beginNameLookup("v056")); assertNull(repo.beginNameLookup("v056"))
        assertFalse(f.repository(37).state.value!!.accounts.single().nameLookupPending)
        val encoded = f.store.bytes!!.toString(Charsets.ISO_8859_1)
        assertFalse(encoded.contains("fictional-student")); assertFalse(encoded.contains("fictional-password"))
    }

    @Test fun customRemarksIncludingAnExplicitUsernameNeverGainAnUpgradeAttempt() {
        for (label in listOf("我的校园网", "fictional-student")) {
            val f = Fixture(); val old = f.repository(37); val account = add(old, label = label)
            val upgraded = f.repository(38); val summary = upgraded.state.value!!.accounts.single()
            assertEquals(label, summary.label); assertFalse(summary.canFillName); assertFalse(summary.nameLookupPending)
            assertNull(upgraded.beginNameLookup(account.id)); assertEquals(label, upgraded.edit(account.id).label)
        }
    }

    @Test fun namesVerifiedByCasOrLocalScheduleAreNotQueriedAgainAfterUpgrade() {
        val f = Fixture(); val old = f.repository(37)
        val cas = add(old, "fictional-cas"); val local = add(old, "fictional-local")
        assertTrue(old.completeNameLookup(old.beginNameLookup(cas.id)!!, cas.username, "认证同学"))
        assertTrue(old.fillNameFromSchedule(local.username, "课表同学", local.id))
        for (version in listOf(38, 42)) {
            val upgraded = f.repository(version)
            assertEquals(listOf("认证同学", "课表同学"), upgraded.state.value!!.accounts.map { it.label })
            upgraded.state.value!!.accounts.forEach {
                assertFalse(it.canFillName); assertFalse(it.nameLookupPending); assertNull(upgraded.beginNameLookup(it.id))
            }
        }
    }

    @Test fun accountsRearmIndependentlyAndPreserveDefaultsSettingsAndExactCredentials() {
        val f = Fixture(); val old = f.repository(37)
        val first = add(old, "fictional-first"); val second = add(old, "fictional-second")
        old.setDefault(second.id)
        val settings = AppSettings(autoLogin = false, networkMode = NetworkMode.DORM_WIFI)
        old.settings(settings)
        val before = listOf(old.edit(first.id), old.edit(second.id))
        old.beginNameLookup(first.id)
        val upgraded = f.repository(38)
        assertTrue(upgraded.state.value!!.accounts.all { it.nameLookupPending })
        assertNotNull(upgraded.beginNameLookup(first.id))
        assertFalse(upgraded.state.value!!.accounts.first().nameLookupPending)
        assertTrue(upgraded.state.value!!.accounts.last().nameLookupPending)
        assertNotNull(upgraded.beginNameLookup(second.id))
        val restored = f.repository(38)
        assertTrue(restored.state.value!!.accounts.none { it.nameLookupPending })
        assertEquals(second.id, restored.state.value!!.defaultAccountId); assertEquals(settings, restored.state.value!!.settings)
        assertEquals(before, listOf(restored.edit(first.id), restored.edit(second.id)))
    }

    @Test fun failedUpgradeMarkerWriteReleasesNoCredentialsAndKeepsHealthyAccountsUsable() {
        val f = Fixture(); val old = f.repository(37); val account = add(old)
        old.beginNameLookup(account.id)
        val upgraded = f.repository(38); val bytes = f.store.bytes!!.copyOf(); val before = upgraded.state.value
        var released: AccountDraft? = null
        f.store.failWrite = true
        assertThrows(NameSaveException::class.java) { released = upgraded.beginNameLookup(account.id) }
        assertNull(released); assertArrayEquals(bytes, f.store.bytes); assertEquals(before, upgraded.state.value)
        assertTrue(upgraded.state.value!!.accounts.single().nameLookupPending)
        assertEquals("  fictional&password=+中文  ", upgraded.credentialsForLogin(account.id)!!.password)
        f.store.failWrite = false
        assertNotNull(upgraded.beginNameLookup(account.id))
        assertFalse(f.repository(38).state.value!!.accounts.single().nameLookupPending)
    }

    @Test fun cancellationBeforeOrDuringEncryptionDoesNotConsumeTheUpgradeAttempt() {
        val f = Fixture(); val old = f.repository(37); val account = add(old)
        old.beginNameLookup(account.id)
        val upgraded = f.repository(38); val bytes = f.store.bytes!!.copyOf(); val writes = f.store.writes
        assertNull(upgraded.beginNameLookup(account.id) { false })
        var valid = true
        val cancelling = object : VaultCipher by f.cipher {
            override fun encrypt(plaintext: ByteArray, requireExistingKey: Boolean) =
                f.cipher.encrypt(plaintext, requireExistingKey).also { valid = false }
        }
        val queued = f.repository(38, cancelling)
        assertNull(queued.beginNameLookup(account.id) { valid })
        assertArrayEquals(bytes, f.store.bytes); assertEquals(writes, f.store.writes)
        assertTrue(f.repository(38).state.value!!.accounts.single().nameLookupPending)
    }

    @Test fun downgradingAndEditingCannotResetAHigherVersionAttempt() {
        val f = Fixture(); val latest = f.repository(39); val account = add(latest)
        latest.beginNameLookup(account.id)
        val older = f.repository(38)
        assertFalse(older.state.value!!.accounts.single().nameLookupPending); assertNull(older.beginNameLookup(account.id))
        older.save(older.edit(account.id).copy(password = "edited-fictional-password"))
        assertFalse(f.repository(39).state.value!!.accounts.single().nameLookupPending)
        assertNull(f.repository(39).beginNameLookup(account.id))
        assertTrue(f.repository(40).state.value!!.accounts.single().nameLookupPending)
    }

    @Test fun editingCredentialsOrAddingThenClearingACustomRemarkCannotReviveTheSameVersionAttempt() {
        val f = Fixture(); val old = f.repository(37)
        val password = add(old, "fictional-password-account")
        val username = add(old, "fictional-username-account")
        val remark = add(old, "fictional-remark-account")
        val upgraded = f.repository(38)
        upgraded.save(upgraded.edit(password.id).copy(password = "corrected-fictional-password"))
        upgraded.save(upgraded.edit(username.id).copy(label = "", username = "corrected-fictional-account"))
        upgraded.save(upgraded.edit(remark.id).copy(label = "我的备注"))
        upgraded.save(upgraded.edit(remark.id).copy(label = ""))
        val restored = f.repository(38)
        assertTrue(restored.state.value!!.accounts.all { it.canFillName && !it.nameLookupPending })
        restored.state.value!!.accounts.forEach { assertNull(restored.beginNameLookup(it.id)) }
        assertEquals("corrected-fictional-password", restored.edit(password.id).password)
        assertEquals("corrected-fictional-account", restored.edit(username.id).username)
        assertTrue(f.repository(39).state.value!!.accounts.all { it.nameLookupPending })
    }

    @Test fun negativeStoredLookupVersionIsCorruptionAndCannotBeSilentlyOverwritten() {
        val f = Fixture()
        f.legacy("""{"version":1,"accounts":[{"id":"damaged","label":"fictional-student","username":"fictional-student","password":"fictional-password","customLabel":false,"nameLookupPending":false,"nameLookupVersionCode":-1}],"defaultAccountId":"damaged"}""")
        val bytes = f.store.bytes!!.copyOf()
        val repo = AccountRepository(f.store, f.cipher, currentVersionCode = 37)
        assertThrows(VaultUnavailableException::class.java) { repo.load() }
        assertThrows(VaultUnavailableException::class.java) {
            repo.save(AccountDraft(label = "", username = "replacement-fictional", password = "fictional-password"))
        }
        assertArrayEquals(bytes, f.store.bytes); assertEquals(0, f.store.writes)
    }
}
