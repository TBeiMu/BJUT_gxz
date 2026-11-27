package io.github.tbeimu.gongxiaozhu.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val MIN_CARD_OPACITY_PERCENT = 0
internal const val MAX_CARD_OPACITY_PERCENT = 100

@Serializable
internal enum class WallpaperMode(val title: String) {
    PRESET("颜色背景"), SYSTEM_COLORS("跟随壁纸颜色"), PHOTO("自选壁纸")
}

@Serializable
internal enum class WallpaperPreset(val title: String) {
    BLUE_CYAN("蓝青"), MINT("薄荷"), LAVENDER("薰衣草"), PINK("樱粉"), APRICOT("暖杏"), GRAY("灰白")
}

@Serializable
internal enum class AppThemeMode(val title: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

@Serializable
internal enum class AccentPreset(val title: String) {
    VERMILION("朱红"), BLUE("晴空蓝"), GREEN("松绿"), VIOLET("暮紫")
}

@Serializable
internal data class WallpaperPreferences(
    val schemaVersion: Int = 1,
    val mode: WallpaperMode = WallpaperMode.PRESET,
    val preset: WallpaperPreset = WallpaperPreset.BLUE_CYAN,
    val photoBlurDp: Int = 12,
    val photoName: String? = null,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val accentPreset: AccentPreset = AccentPreset.VERMILION,
    val cardOpacityPercent: Int = 96
) {
    fun validate(): WallpaperPreferences {
        require(schemaVersion == 1 && photoBlurDp in 0..24 &&
            cardOpacityPercent in MIN_CARD_OPACITY_PERCENT..MAX_CARD_OPACITY_PERCENT)
        require(photoName == null || photoName == LEGACY_PHOTO || PHOTO_NAME.matches(photoName))
        require(mode != WallpaperMode.PHOTO || photoName != null)
        return this
    }

    fun restoredDefault() = copy(mode = WallpaperMode.PRESET, preset = WallpaperPreset.BLUE_CYAN,
        themeMode = AppThemeMode.SYSTEM, accentPreset = AccentPreset.VERMILION, cardOpacityPercent = 96)

    companion object {
        const val LEGACY_PHOTO = "wallpaper.jpg"
        private val PHOTO_NAME = Regex("wallpaper-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.jpg")
        private val json = Json { encodeDefaults = true }

        fun migrated(hasLegacyPhoto: Boolean) = if (hasLegacyPhoto) WallpaperPreferences(
            mode = WallpaperMode.PHOTO, photoBlurDp = 0, photoName = LEGACY_PHOTO
        ) else WallpaperPreferences()

        fun decode(bytes: ByteArray): WallpaperPreferences {
            require(bytes.size <= 16 * 1024)
            return json.decodeFromString<WallpaperPreferences>(bytes.decodeToString(throwOnInvalidSequence = true)).validate()
        }

        fun encode(value: WallpaperPreferences): ByteArray = json.encodeToString(value.validate()).encodeToByteArray()
    }
}
