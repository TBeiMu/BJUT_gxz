package io.github.tbeimu.gongxiaozhu.billing

import io.github.tbeimu.gongxiaozhu.data.AccountDraft
import io.github.tbeimu.gongxiaozhu.data.AccountRepository
import io.github.tbeimu.gongxiaozhu.data.AesGcmVaultCipher
import io.github.tbeimu.gongxiaozhu.data.VaultCipher
import io.github.tbeimu.gongxiaozhu.data.VaultKeySource
import io.github.tbeimu.gongxiaozhu.data.VaultStore
import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class BillingCacheRepositoryTest {
    private class Store : VaultStore {
        var bytes: ByteArray? = null
        var failRead = false
        var failWrite = false
        var failClear = false
        var reads = 0
        var writes = 0
        var clears = 0
        override fun read(): ByteArray? { reads++; if (failRead) throw IOException("Fictional read failure"); return bytes?.copyOf() }
        override fun write(bytes: ByteArray) { writes++; if (failWrite) throw IOException("Fictional write failure"); this.bytes = bytes.copyOf() }
        override fun clear() { clears++; if (failClear) throw IOException("Fictional clear failure"); bytes = null }
    }
    private class Keys : VaultKeySource {
        var key: SecretKey? = null
        var clears = 0
        override fun encryptionKey(): SecretKey = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        override fun decryptionKey(): SecretKey = key ?: error("Fictional missing key")
        override fun clear() { clears++; key = null }
    }
    private class Fixture {
        val store = Store()
        val keys = Keys()
        val cipher = AesGcmVaultCipher(keys)
        val repository = BillingCacheRepository(store, cipher)
    }
    private fun sample(account: String = "fictional-account-a", time: Long = 1000) = CachedBilling(account, BillingSnapshot(
        linkedMapOf("已用流量" to BillingMetric("1234.567", "MB"), "可用流量" to BillingMetric("不限量", ""),
            "消费保护" to BillingMetric("未设置", ""), "账户余额" to BillingMetric("-1.2300", "元")),
        listOf(OnlineDevice("fictional-session", "192.0.2.1", "2001:db8::1", "00:00:5e:00:53:01",
            "2026-11-20 08:00", 17, BillingMetric("1.234", "MB")))), time)
    private fun raw(account: String = "fictional-account-a", schema: Int = 1, time: Long = 1000) =
        """{"schema":$schema,"accountId":"$account","loadedAt":$time,"metrics":{"已用流量":{"value":"1","unit":"MB"}},"devices":[],"devicesError":false}"""
    private fun Fixture.storePlain(value: String) { store.bytes = cipher.encrypt(value.encodeToByteArray()) }
    private fun CachedBilling.displayOnly() = copy(snapshot = snapshot.copy(
        devices = snapshot.devices.mapIndexed { index, device -> device.copy(sessionId = "cached-device-$index") },
        notice = null))

    @Test fun encryptedSnapshotRestoresOnlyItsAccountAndLatestOverwrite() {
        val f = Fixture(); val first = sample()
        assertTrue(f.repository.save(first))
        assertEquals(first.displayOnly(), BillingCacheRepository(f.store, f.cipher).load(first.accountId))
        assertNull(f.repository.load("different-account"))
        val second = sample("fictional-account-b", 2000).copy(snapshot = BillingSnapshot(emptyMap(), emptyList()))
        assertTrue(f.repository.save(second))
        assertNull(f.repository.load(first.accountId))
        assertEquals(second, f.repository.load(second.accountId))
        assertEquals(2, f.store.writes)
    }

    @Test fun ciphertextUsesFreshIvAndDoesNotExposeSnapshotFields() {
        val f = Fixture(); val value = sample()
        assertTrue(f.repository.save(value)); val first = f.store.bytes!!.copyOf()
        val encoded = first.toString(Charsets.ISO_8859_1)
        for (secret in listOf(value.accountId, "fictional-session", "192.0.2.1", "1234.567")) assertFalse(encoded.contains(secret))
        assertTrue(f.repository.save(value)); val second = f.store.bytes!!
        assertFalse(first.contentEquals(second))
        assertFalse(first.copyOfRange(4, 16).contentEquals(second.copyOfRange(4, 16)))
        assertEquals(value.displayOnly(), f.repository.load(value.accountId))
        assertFalse(value.toString().contains(value.accountId)); assertFalse(value.toString().contains("fictional-session"))
    }

    @Test fun noticesAreOmittedAndDeviceErrorsUseOnlyAFixedCategory() {
        val f = Fixture(); val value = sample()
        assertTrue(f.repository.save(value.copy(snapshot = value.snapshot.copy(
            notice = "fictional secret response body", devicesError = "fictional cookie or server detail"))))
        val restored = f.repository.load(value.accountId)!!
        assertNull(restored.snapshot.notice)
        assertEquals("在线设备读取失败，请重新查询", restored.snapshot.devicesError)
        val plain = f.cipher.decrypt(f.store.bytes!!)
        try {
            val json = plain.decodeToString()
            assertFalse(json.contains("notice")); assertFalse(json.contains("fictional secret")); assertFalse(json.contains("cookie"))
            assertFalse(json.contains("sessionId")); assertFalse(json.contains("fictional-session"))
            assertTrue(json.contains("\"devicesError\":true"))
        } finally { plain.fill(0) }
    }

    @Test fun missingCorruptAndUnavailableCachesAreNonBlocking() {
        val f = Fixture()
        assertNull(f.repository.load("fictional-account-a"))
        f.store.bytes = byteArrayOf(1, 2, 3); assertNull(f.repository.load("fictional-account-a"))
        assertTrue(f.repository.save(sample()))
        f.store.bytes = f.store.bytes!!.also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertNull(f.repository.load("fictional-account-a"))
        f.store.failRead = true; assertNull(f.repository.load("fictional-account-a"))
        assertTrue("A new successful snapshot may replace a broken optional cache", f.repository.save(sample()))
    }

    @Test fun malformedSchemaDatesAndUnknownFieldsAreRejectedWithoutDeletingTheFile() {
        val f = Fixture()
        for (bad in listOf(raw(schema = 2), raw(time = -1), raw(time = 0), raw(account = ""), "null", "{}",
            raw().dropLast(1) + ",\"notice\":\"untrusted raw page\"}")) {
            f.storePlain(bad); val bytes = f.store.bytes!!.copyOf()
            assertNull(f.repository.load("fictional-account-a")); assertArrayEquals(bytes, f.store.bytes)
        }
        assertEquals(0, f.store.clears)
    }

    @Test fun oversizedCiphertextIsRejectedBeforeDecrypting() {
        val f = Fixture()
        var decryptions = 0
        val monitored = object : VaultCipher by f.cipher {
            override fun decrypt(envelope: ByteArray): ByteArray { decryptions++; return f.cipher.decrypt(envelope) }
        }
        f.store.bytes = ByteArray(BILLING_CACHE_MAX_BYTES + 1)
        assertNull(BillingCacheRepository(f.store, monitored).load("fictional-account-a"))
        assertEquals(0, decryptions)
    }

    @Test fun invalidMetricsDevicesAndStringBoundsPreserveThePreviousGoodSnapshot() {
        val f = Fixture(); val good = sample(); assertTrue(f.repository.save(good))
        val bytes = f.store.bytes!!.copyOf(); val device = good.snapshot.devices.single()
        val badSnapshots = listOf(
            good.snapshot.copy(metrics = mapOf("unknown-key" to BillingMetric("1", "MB"))),
            good.snapshot.copy(metrics = mapOf("已用流量" to BillingMetric("v".repeat(129), "MB"))),
            good.snapshot.copy(metrics = mapOf("已用流量" to BillingMetric("1", "u".repeat(33)))),
            good.snapshot.copy(metrics = mapOf("已用流量" to BillingMetric("1\n2", "MB"))),
            good.snapshot.copy(devices = listOf(device.copy(ip = "i".repeat(129)))),
            good.snapshot.copy(devices = listOf(device.copy(ipv6 = "i".repeat(129)))),
            good.snapshot.copy(devices = listOf(device.copy(mac = "m".repeat(65)))),
            good.snapshot.copy(devices = listOf(device.copy(loginTime = "t".repeat(129)))),
            good.snapshot.copy(devices = listOf(device.copy(minutes = -1))),
            good.snapshot.copy(devices = (1..65).map { device.copy(sessionId = "s-$it") }),
        )
        for (bad in badSnapshots) { assertFalse(f.repository.save(good.copy(snapshot = bad))); assertArrayEquals(bytes, f.store.bytes) }
        assertFalse(f.repository.save(good.copy(accountId = "a".repeat(129))))
        assertFalse(f.repository.save(good.copy(loadedAt = -1)))
        assertEquals(good.displayOnly(), f.repository.load(good.accountId))
    }

    @Test fun readValidationAlsoRejectsUnknownMetricsAndInvalidDevices() {
        val f = Fixture()
        val invalid = listOf(
            raw().replace("已用流量", "private-field"),
            raw().replace("\"1\"", "\"${"x".repeat(129)}\""),
            raw().replace("\"devices\":[]", "\"devices\":[{\"ip\":\"\",\"ipv6\":\"\",\"mac\":\"\",\"loginTime\":\"\",\"minutes\":-1}]"),
        )
        for (json in invalid) { f.storePlain(json); assertNull(f.repository.load("fictional-account-a")) }
    }

    @Test fun allowedSparseFieldsAndSixtyFourCompactDevicesRestoreWithoutInventingValues() {
        val f = Fixture(); val sparse = sample().copy(snapshot = BillingSnapshot(
            mapOf("可用流量" to BillingMetric("不限量", "")), (1..64).map {
                OnlineDevice("s-$it", "", "", "", "", null, null)
            }))
        assertTrue(f.repository.save(sparse)); assertTrue(f.store.bytes!!.size <= BILLING_CACHE_MAX_BYTES)
        assertEquals(sparse.displayOnly(), f.repository.load(sparse.accountId))
        assertNull(f.repository.load(sparse.accountId)!!.snapshot.metrics["已用流量"])
        assertTrue(f.repository.load(sparse.accountId)!!.snapshot.devices.all { it.minutes == null && it.traffic == null })
    }

    @Test fun cumulativeUtf8PayloadOverTheCiphertextBudgetKeepsTheOldSnapshot() {
        val f = Fixture(); val good = sample(); assertTrue(f.repository.save(good))
        val before = f.store.bytes!!.copyOf()
        val large = good.copy(snapshot = good.snapshot.copy(devices = (1..64).map {
            OnlineDevice("$it" + "会".repeat(250), "地".repeat(128), "址".repeat(128), "网".repeat(64), "时".repeat(128), 1,
                BillingMetric("量".repeat(128), "单".repeat(32)))
        }))
        assertFalse(f.repository.save(large)); assertArrayEquals(before, f.store.bytes)
        assertEquals(good.displayOnly(), f.repository.load(good.accountId))
    }

    @Test fun failedAtomicWriteAndClearKeepExistingDataAndReturnFalse() {
        val f = Fixture(); val good = sample(); assertTrue(f.repository.save(good))
        val bytes = f.store.bytes!!.copyOf()
        f.store.failWrite = true; assertFalse(f.repository.save(sample(time = 2000))); assertArrayEquals(bytes, f.store.bytes)
        f.store.failClear = true; assertFalse(f.repository.clear()); assertArrayEquals(bytes, f.store.bytes)
        f.store.failClear = false; assertTrue(f.repository.clear()); assertNull(f.repository.load(good.accountId))
        assertEquals(0, f.keys.clears)
    }

    @Test fun retiredTasksNeverReadWriteOrClear() {
        val f = Fixture()
        assertNull(f.repository.load("fictional-account-a") { false })
        assertFalse(f.repository.save(sample()) { false }); assertFalse(f.repository.clear { false })
        assertEquals(0, f.store.reads + f.store.writes + f.store.clears)
        assertNull(f.keys.key)
    }

    @Test fun retirementAfterEncryptionDoesNotCommitAnOldAccountSnapshot() {
        val f = Fixture(); val good = sample(); assertTrue(f.repository.save(good))
        val bytes = f.store.bytes!!.copyOf(); var current = true
        val retiring = object : VaultCipher by f.cipher {
            override fun encrypt(plaintext: ByteArray, requireExistingKey: Boolean): ByteArray =
                f.cipher.encrypt(plaintext, requireExistingKey).also { current = false }
        }
        assertFalse(BillingCacheRepository(f.store, retiring).save(sample("other-account", 2000)) { current })
        assertArrayEquals(bytes, f.store.bytes); assertEquals(good.displayOnly(), f.repository.load(good.accountId))
    }

    @Test fun retirementAfterDecryptionDiscardsTheLoadedSnapshot() {
        val f = Fixture(); assertTrue(f.repository.save(sample())); var current = true
        val retiring = object : VaultCipher by f.cipher {
            override fun decrypt(envelope: ByteArray): ByteArray = f.cipher.decrypt(envelope).also { current = false }
        }
        assertNull(BillingCacheRepository(f.store, retiring).load("fictional-account-a") { current })
    }

    @Test fun cancellationIsNeverConvertedIntoAnOptionalCacheMiss() {
        val f = Fixture(); assertTrue(f.repository.save(sample()))
        val cancelledStore = object : VaultStore {
            override fun read(): ByteArray? = throw CancellationException("Fictional cancellation")
            override fun write(bytes: ByteArray) { throw CancellationException("Fictional cancellation") }
            override fun clear() { throw CancellationException("Fictional cancellation") }
        }
        val cancelled = BillingCacheRepository(cancelledStore, f.cipher)
        assertThrows(CancellationException::class.java) { cancelled.load("fictional-account-a") }
        assertThrows(CancellationException::class.java) { cancelled.save(sample()) }
        assertThrows(CancellationException::class.java) { cancelled.clear() }
        assertThrows(CancellationException::class.java) { f.repository.save(sample()) { throw CancellationException() } }
        val cancelledCipher = object : VaultCipher by f.cipher {
            override fun encrypt(plaintext: ByteArray, requireExistingKey: Boolean): ByteArray = throw CancellationException()
            override fun decrypt(envelope: ByteArray): ByteArray = throw CancellationException()
        }
        val cipherCancellation = BillingCacheRepository(f.store, cancelledCipher)
        assertThrows(CancellationException::class.java) { cipherCancellation.load("fictional-account-a") }
        assertThrows(CancellationException::class.java) { cipherCancellation.save(sample()) }
    }

    @Test fun missingCacheKeyAndCacheClearingCannotChangeAccountStorageOrKeys() {
        val accounts = Store(); val accountKeys = Keys(); val accountCipher = AesGcmVaultCipher(accountKeys)
        val accountRepository = AccountRepository(accounts, accountCipher)
        accountRepository.load()
        val saved = accountRepository.save(AccountDraft(label = "虚构备注", username = "fictional-user", password = "fictional-password"))
        val bytes = accounts.bytes!!.copyOf(); val key = accountKeys.key
        val f = Fixture(); assertTrue(f.repository.save(sample())); f.keys.key = null
        assertNull(f.repository.load("fictional-account-a")); assertTrue(f.repository.clear())
        assertArrayEquals(bytes, accounts.bytes); assertSame(key, accountKeys.key); assertEquals(0, accountKeys.clears)
        assertEquals(saved, AccountRepository(accounts, accountCipher).load())
        assertEquals("fictional-password", accountRepository.edit(saved.defaultAccountId!!).password)
        assertTrue(f.repository.save(sample())); assertEquals(sample().displayOnly(), f.repository.load("fictional-account-a"))
    }
}
