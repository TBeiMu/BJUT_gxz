package io.github.tbeimu.gongxiaozhu.ui

import org.junit.Assert.*
import org.junit.Test

class WallpaperPreferencesTest {
    @Test fun migrationPreservesAnExistingPhotoWithoutAddingBlur() {
        val migrated = WallpaperPreferences.migrated(true)
        assertEquals(WallpaperMode.PHOTO, migrated.mode)
        assertEquals(0, migrated.photoBlurDp)
        assertEquals("wallpaper.jpg", migrated.photoName)
        assertEquals(WallpaperPreferences(), WallpaperPreferences.migrated(false))
    }

    @Test fun allModesPresetsAndBlurLimitsRoundTrip() {
        for (mode in WallpaperMode.entries) for (preset in WallpaperPreset.entries)
            for (theme in AppThemeMode.entries) for (accent in AccentPreset.entries)
                for (blur in listOf(0, 12, 24)) for (opacity in listOf(84, 92, 96, 98)) {
                    val preferences = WallpaperPreferences(mode = mode, preset = preset, photoBlurDp = blur,
                        photoName = "wallpaper-00000000-0000-0000-0000-000000000000.jpg",
                        themeMode = theme, accentPreset = accent, cardOpacityPercent = opacity)
                    assertEquals(preferences, WallpaperPreferences.decode(WallpaperPreferences.encode(preferences)))
                }
    }

    @Test fun oldWallpaperSettingsLoadWithDefaultAppearanceAndSystemModeFollowsInput() {
        val old = """{"schemaVersion":1,"mode":"PRESET","preset":"MINT","photoBlurDp":5,"photoName":null}"""
        val migrated = WallpaperPreferences.decode(old.encodeToByteArray())
        assertEquals(AppThemeMode.SYSTEM, migrated.themeMode)
        assertEquals(AccentPreset.VERMILION, migrated.accentPreset)
        assertEquals(96, migrated.cardOpacityPercent)
        assertFalse(AppThemeMode.SYSTEM.isDark(false))
        assertTrue(AppThemeMode.SYSTEM.isDark(true))
        assertFalse(AppThemeMode.LIGHT.isDark(true))
        assertTrue(AppThemeMode.DARK.isDark(false))
    }

    @Test fun restoringDefaultRetainsPhotoAndItsBlur() {
        val photo = WallpaperPreferences(mode = WallpaperMode.PHOTO, preset = WallpaperPreset.PINK,
            photoBlurDp = 7, photoName = "wallpaper.jpg", themeMode = AppThemeMode.DARK,
            accentPreset = AccentPreset.BLUE, cardOpacityPercent = 88)
        val restored = photo.restoredDefault()
        assertEquals(WallpaperMode.PRESET, restored.mode)
        assertEquals(WallpaperPreset.BLUE_CYAN, restored.preset)
        assertEquals(AppThemeMode.SYSTEM, restored.themeMode)
        assertEquals(AccentPreset.VERMILION, restored.accentPreset)
        assertEquals(96, restored.cardOpacityPercent)
        assertEquals(photo.photoName, restored.photoName)
        assertEquals(7, restored.photoBlurDp)
    }

    @Test fun invalidSettingsAndPathsCannotAddressOtherPrivateFiles() {
        for (path in listOf("../accounts.json", "accounts.json", "D:/private.jpg", "/private.jpg", "wallpaper.jpg/../secret")) {
            assertThrows(IllegalArgumentException::class.java) { WallpaperPreferences(photoName = path).validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { WallpaperPreferences(photoBlurDp = -1).validate() }
        assertThrows(IllegalArgumentException::class.java) { WallpaperPreferences(photoBlurDp = 25).validate() }
        for (opacity in listOf(83, 99)) assertThrows(IllegalArgumentException::class.java) {
            WallpaperPreferences(cardOpacityPercent = opacity).validate()
        }
        assertThrows(IllegalArgumentException::class.java) { WallpaperPreferences(schemaVersion = 2).validate() }
        assertThrows(IllegalArgumentException::class.java) { WallpaperPreferences(mode = WallpaperMode.PHOTO).validate() }
    }

    @Test fun malformedOversizedAndUnknownConfigurationIsRejected() {
        for (value in listOf("not json", "{\"mode\":\"UNKNOWN\"}", "{\"photoBlurDp\":1.5}", "{\"schemaVersion\":2}")) {
            assertThrows(Exception::class.java) { WallpaperPreferences.decode(value.encodeToByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { WallpaperPreferences.decode(ByteArray(16 * 1024 + 1)) }
        assertThrows(Exception::class.java) { WallpaperPreferences.decode(byteArrayOf(0xff.toByte())) }
    }
}
