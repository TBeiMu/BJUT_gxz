package io.github.tbeimu.gongxiaozhu.billing

import io.github.tbeimu.gongxiaozhu.data.VaultCipher
import io.github.tbeimu.gongxiaozhu.data.VaultStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val BILLING_CACHE_MAX_BYTES = 32 * 1024
private const val ENVELOPE_BYTES = 4 + 12 + 16
private const val DEVICE_CACHE_ERROR = "在线设备读取失败，请重新查询"
private val CACHE_METRIC_KEYS = setOf("已用流量", "可用流量", "消费保护", "账户余额")

data class CachedBilling(val accountId: String, val snapshot: BillingSnapshot, val loadedAt: Long) {
    override fun toString() = "CachedBilling(data=<redacted>)"
}

/** One optional display snapshot. Authentication pages, credentials and sessions have no cache DTO. */
open class BillingCacheRepository(private val store: VaultStore, private val cipher: VaultCipher) {
    private val json = Json { encodeDefaults = true }

    @Synchronized
    open fun load(accountId: String, isCurrent: () -> Boolean = { true }): CachedBilling? = optional(null) {
        if (!isCurrent() || !validText(accountId, 128, nonblank = true)) return@optional null
        val envelope = store.read() ?: return@optional null
        require(envelope.size in ENVELOPE_BYTES..BILLING_CACHE_MAX_BYTES)
        if (!isCurrent()) return@optional null
        val plain = cipher.decrypt(envelope)
        val cached = try {
            require(plain.size <= BILLING_CACHE_MAX_BYTES - ENVELOPE_BYTES)
            json.decodeFromString<BillingCacheData>(plain.decodeToString(throwOnInvalidSequence = true)).also(::validate)
        } finally { plain.fill(0) }
        if (!isCurrent() || cached.accountId != accountId) null else cached.toCachedBilling()
    }

    @Synchronized
    open fun save(value: CachedBilling, isCurrent: () -> Boolean = { true }): Boolean = optional(false) {
        if (!isCurrent()) return@optional false
        val cached = value.toCacheData().also(::validate)
        val plain = json.encodeToString(cached).encodeToByteArray()
        try {
            require(plain.size <= BILLING_CACHE_MAX_BYTES - ENVELOPE_BYTES)
            if (!isCurrent()) return@optional false
            val envelope = cipher.encrypt(plain)
            require(envelope.size in ENVELOPE_BYTES..BILLING_CACHE_MAX_BYTES)
            // The caller may have changed account or retired this query during encryption.
            if (!isCurrent()) return@optional false
            store.write(envelope)
            true
        } finally { plain.fill(0) }
    }

    @Synchronized
    open fun clear(isCurrent: () -> Boolean = { true }): Boolean = optional(false) {
        if (!isCurrent()) return@optional false
        store.clear()
        true
    }

    private fun validate(value: BillingCacheData) {
        require(value.schema == 1 && validText(value.accountId, 128, nonblank = true) && value.loadedAt > 0)
        require(value.metrics.size <= 4 && value.metrics.keys.all { it in CACHE_METRIC_KEYS })
        value.metrics.values.forEach(::validateMetric)
        require(value.devices.size <= 64)
        value.devices.forEach { device ->
            require(validText(device.ip, 128) && validText(device.ipv6, 128) && validText(device.mac, 64) &&
                validText(device.loginTime, 128))
            require(device.minutes == null || device.minutes >= 0)
            device.traffic?.let(::validateMetric)
        }
    }

    private fun validateMetric(value: CachedMetric) {
        require(validText(value.value, 128, nonblank = true) && validText(value.unit, 32))
    }

    private inline fun <T> optional(fallback: T, action: () -> T): T = try { action() }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { fallback }
}

private fun validText(value: String, maximum: Int, nonblank: Boolean = false) =
    value.length <= maximum && (!nonblank || value.isNotBlank()) && value.none(Char::isISOControl)

@Serializable
private data class BillingCacheData(
    val schema: Int = 1,
    val accountId: String,
    val loadedAt: Long,
    val metrics: Map<String, CachedMetric>,
    val devices: List<CachedDevice>,
    // Only the failure category is retained, never a server/error message.
    val devicesError: Boolean = false,
)

@Serializable
private data class CachedMetric(val value: String, val unit: String)

@Serializable
private data class CachedDevice(val ip: String, val ipv6: String, val mac: String,
    val loginTime: String, val minutes: Long?, val traffic: CachedMetric? = null)

private fun BillingMetric.toCacheData() = CachedMetric(value, unit)
private fun CachedMetric.toMetric() = BillingMetric(value, unit)
private fun CachedBilling.toCacheData() = BillingCacheData(accountId = accountId, loadedAt = loadedAt,
    metrics = snapshot.metrics.mapValues { it.value.toCacheData() },
    devices = snapshot.devices.map { CachedDevice(it.ip, it.ipv6, it.mac, it.loginTime, it.minutes, it.traffic?.toCacheData()) },
    devicesError = snapshot.devicesError != null)

private fun BillingCacheData.toCachedBilling() = CachedBilling(accountId, BillingSnapshot(
    metrics = metrics.mapValues { it.value.toMetric() },
    devices = devices.mapIndexed { index, device -> OnlineDevice("cached-device-$index", device.ip, device.ipv6, device.mac,
        device.loginTime, device.minutes, device.traffic?.toMetric()) },
    devicesError = if (devicesError) DEVICE_CACHE_ERROR else null,
    notice = null), loadedAt)
