package io.github.tbeimu.gongxiaozhu

import android.app.WallpaperManager
import android.app.UiAutomation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tbeimu.gongxiaozhu.ui.*
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test

class WallpaperTest {
    @get:Rule val compose = createAndroidComposeRule<WallpaperTestActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = WallpaperStore(context)
    private lateinit var state: WallpaperState
    private var observedDark = false
    private var observedAccent = Color.Unspecified
    private var observedBrandAccent = Color.Unspecified
    private var observedPrimary = Color.Unspecified
    private var observedTertiary = Color.Unspecified
    private var observedGlassAlpha = 0f
    private val settings get() = File(context.noBackupFilesDir, "wallpaper.json")
    private val blockedWrite get() = File(context.noBackupFilesDir, "wallpaper.json.new")

    @Before fun clean() {
        check(context.packageName.endsWith(".debug"))
        unblock()
        store.save(WallpaperPreferences())
        context.noBackupFilesDir.listFiles()?.filter {
            it.name == "wallpaper.jpg" || Regex("wallpaper-[a-f0-9-]+\\.jpg").matches(it.name)
        }?.forEach { it.delete() }
    }

    @After fun finish() {
        compose.runOnUiThread { WallpaperTestActivity.scene = null }
        unblock()
        store.clear()
        File(context.cacheDir, "wallpaper-test.png").delete()
    }

    private fun unblock() {
        if (blockedWrite.isDirectory) { File(blockedWrite, "blocked").delete(); blockedWrite.delete() }
    }

    private fun image(color: Int = android.graphics.Color.BLUE): File {
        val file = File(context.cacheDir, "wallpaper-test.png")
        Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        return file
    }

    private fun show(fontScale: Float = 1f) {
        compose.runOnUiThread { WallpaperTestActivity.scene = {
            state = rememberWallpaper()
            GongXiaoZhuTheme(state.preferences) {
            val palette = gxz
            val primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
            val tertiary = androidx.compose.material3.MaterialTheme.colorScheme.tertiary
            SideEffect {
                observedDark = palette.dark
                observedAccent = palette.accent
                observedBrandAccent = palette.brandAccent
                observedPrimary = primary
                observedTertiary = tertiary
                observedGlassAlpha = palette.glass.alpha
            }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppBackdrop(state) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        WallpaperSettingsPanel(state)
                    }
                }
            }
        } } }
        compose.waitUntil(5000) { ::state.isInitialized && !state.busy }
    }

    @Test fun legacyPhotoMigratesUnblurredAndModeChangesRetainItsBytes() {
        settings.delete()
        val legacy = File(context.noBackupFilesDir, "wallpaper.jpg")
        image().copyTo(legacy, overwrite = true)
        val bytes = legacy.readBytes()
        show()
        assertEquals(WallpaperMode.PHOTO, state.preferences.mode)
        assertEquals(0, state.preferences.photoBlurDp)
        compose.onNodeWithTag("wallpaper_mode_PRESET").performScrollTo().performClick()
        compose.waitUntil { !state.busy }
        compose.onNodeWithTag("wallpaper_preset_PINK").performScrollTo().performClick()
        compose.waitUntil { !state.busy }
        assertEquals(WallpaperPreset.PINK, store.loadPreferences().preset)
        compose.onNodeWithTag("wallpaper_mode_PHOTO").performScrollTo().performClick()
        compose.waitUntil { !state.busy }
        assertEquals(0, state.preferences.photoBlurDp)
        compose.onNodeWithTag("wallpaper_restore").performScrollTo().performClick()
        compose.waitUntil { !state.busy }
        assertEquals(WallpaperMode.PRESET, store.loadPreferences().mode)
        assertArrayEquals(bytes, legacy.readBytes())
        assertNotNull(store.loadImage(store.loadPreferences()))
    }

    @Test fun sixPresetsPersistAndRemainUsableWithLargeText() {
        show(1.6f)
        for (preset in WallpaperPreset.entries) {
            compose.onNodeWithTag("wallpaper_preset_${preset.name}").performScrollTo().performClick()
            compose.waitUntil { !state.busy }
            compose.onNodeWithTag("wallpaper_preset_${preset.name}").assertIsSelected()
            assertEquals(preset, WallpaperStore(context).loadPreferences().preset)
        }
        preview("wallpaper-presets-large")
        val palettes = WallpaperPreset.entries.map { wallpaperPresetColors(it, false) }
        assertEquals(6, palettes.toSet().size)
    }

    @Test fun appearanceControlsPersistAndRecommendedCombosPreserveWallpaper() {
        store.save(WallpaperPreferences(preset = WallpaperPreset.MINT))
        val photo = store.importPhoto(Uri.fromFile(image()), store.loadPreferences()).first
        show()
        compose.onNodeWithTag("theme_mode_DARK").performScrollTo().performClick()
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().themeMode == AppThemeMode.DARK }
        compose.runOnIdle {
            assertTrue(observedDark)
            assertEquals(Color(0xFFECEDEF), observedPrimary)
            assertEquals(Color(0xFFFF6E52), observedTertiary)
            val controller = WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
        }

        compose.onNodeWithTag("accent_BLUE").performScrollTo().performClick()
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().accentPreset == AccentPreset.BLUE }
        compose.runOnIdle {
            assertEquals(Color(0xFF83B8FF), observedAccent)
            assertEquals(Color(0xFFFF6E52), observedBrandAccent)
            assertEquals(Color(0xFFFF6E52), observedTertiary)
        }

        compose.onNodeWithTag("card_transparency_8").performScrollTo().performClick()
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().cardOpacityPercent == 92 }
        compose.runOnIdle { assertEquals(.92f, observedGlassAlpha, .002f) }

        compose.onNodeWithTag("appearance_combo_NIGHT").performScrollTo().performClick()
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().accentPreset == AccentPreset.GREEN }
        val result = store.loadPreferences()
        assertEquals(AppThemeMode.DARK, result.themeMode)
        assertEquals(92, result.cardOpacityPercent)
        assertEquals(WallpaperMode.PHOTO, result.mode)
        assertEquals(WallpaperPreset.MINT, result.preset)
        assertEquals(photo.photoName, result.photoName)
        assertEquals(photo.photoBlurDp, result.photoBlurDp)
        assertTrue(File(context.noBackupFilesDir, photo.photoName!!).exists())
        compose.runOnIdle { assertTrue(observedDark) }

        val previousState = state
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { state !== previousState && !state.busy }
        compose.runOnIdle {
            assertEquals(result, state.preferences)
            assertNotNull(state.image)
            assertTrue(observedDark)
            assertEquals(.92f, observedGlassAlpha, .002f)
        }

        compose.onNodeWithTag("appearance_combo_SKY").performScrollTo().performClick()
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().themeMode == AppThemeMode.LIGHT }
        compose.runOnIdle {
            assertFalse(observedDark)
            assertEquals(Color(0xFF276BC0), observedAccent)
            assertEquals(Color(0xFFE5482E), observedTertiary)
            val controller = WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
            assertTrue(controller.isAppearanceLightStatusBars)
            assertTrue(controller.isAppearanceLightNavigationBars)
        }
        assertEquals(WallpaperMode.PHOTO, store.loadPreferences().mode)
        assertEquals(photo.photoName, store.loadPreferences().photoName)

        compose.onNodeWithTag("wallpaper_restore").performScrollTo().performClick()
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().themeMode == AppThemeMode.SYSTEM }
        val reset = store.loadPreferences()
        assertEquals(AccentPreset.VERMILION, reset.accentPreset)
        assertEquals(96, reset.cardOpacityPercent)
        assertEquals(WallpaperPreset.BLUE_CYAN, reset.preset)
        assertEquals(photo.photoName, reset.photoName)
        assertEquals(photo.photoBlurDp, reset.photoBlurDp)
        assertTrue(File(context.noBackupFilesDir, photo.photoName!!).exists())
        compose.runOnIdle {
            val systemDark = compose.activity.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            assertEquals(systemDark, observedDark)
        }
    }

    @Test fun appearanceOptionsRemainReachableWithLargeText() {
        show(1.6f)
        compose.onNodeWithTag("theme_mode_DARK").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().themeMode == AppThemeMode.DARK }
        preview("appearance-large-text-dark-theme")
        compose.onNodeWithTag("accent_VIOLET").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().accentPreset == AccentPreset.VIOLET }
        compose.onNodeWithTag("accent_VIOLET").assertIsSelected()
        preview("appearance-large-text-dark-accent")
        compose.onNodeWithTag("card_transparency_8").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().cardOpacityPercent == 92 }
        preview("appearance-large-text-transparency")
        compose.onNodeWithTag("appearance_combo_SKY").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().themeMode == AppThemeMode.LIGHT }
        compose.runOnIdle { assertFalse(observedDark) }
        preview("appearance-large-text")
    }

    @Test fun transparencySliderAcceptsTouchAtBothEndsAndPersistsItsPreview() {
        show()
        val slider = compose.onNodeWithTag("card_transparency_slider").performScrollTo()
        slider.assertContentDescriptionEquals("卡片通透度")
        val track = compose.onNodeWithTag("card_transparency_slider_track", useUnmergedTree = true)
        val thumb = compose.onNodeWithTag("card_transparency_slider_thumb", useUnmergedTree = true)
        // Material adds an accessibility margin around the slider. Gesture coordinates
        // come from the painted track, rather than that expanded semantics rectangle.
        track.performTouchInput { click(centerRight) }
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().cardOpacityPercent == 84 }
        compose.onNodeWithTag("card_transparency_value").assertTextEquals("16%")
        slider.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "16%"))
        compose.runOnIdle { assertEquals(.84f, observedGlassAlpha, .002f) }
        assertEquals(track.fetchSemanticsNode().boundsInRoot.right,
            thumb.fetchSemanticsNode().boundsInRoot.center.x, 1.5f)
        preview("appearance-refined-touch-right")
        track.performTouchInput { click(centerLeft) }
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().cardOpacityPercent == 98 }
        compose.onNodeWithTag("card_transparency_value").assertTextEquals("2%")
        slider.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "2%"))
        compose.runOnIdle { assertEquals(.98f, observedGlassAlpha, .002f) }
        assertEquals(track.fetchSemanticsNode().boundsInRoot.left,
            thumb.fetchSemanticsNode().boundsInRoot.center.x, 1.5f)
        track.performTouchInput { swipe(centerLeft, centerRight, durationMillis = 300) }
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().cardOpacityPercent == 84 }
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(8f) }
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().cardOpacityPercent == 92 }
        compose.onNodeWithTag("card_transparency_8").assertIsSelected()
    }

    @Test fun refinedGroupsRemainOperableAtDoubleFontScale() {
        show(2f)
        compose.onNodeWithTag("wallpaper_preview").assertIsDisplayed()
        preview("appearance-refined-double-font-preview")
        listOf("PAPER", "SKY", "NIGHT").forEach { name ->
            compose.onNodeWithTag("appearance_combo_$name").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(5000) { !state.busy }
            compose.onNodeWithTag("appearance_combo_$name").assertIsSelected()
        }
        preview("appearance-refined-double-font-combos")
        compose.onNodeWithTag("wallpaper_mode_PRESET").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("wallpaper_preset_APRICOT").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { !state.busy }
        compose.onNodeWithTag("wallpaper_preset_APRICOT").assertIsSelected()
        preview("appearance-refined-double-font-background")
        compose.onNodeWithTag("theme_mode_LIGHT").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().themeMode == AppThemeMode.LIGHT }
        compose.onNodeWithTag("accent_BLUE").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().accentPreset == AccentPreset.BLUE }
        compose.onNodeWithTag("accent_BLUE").assertIsSelected()
        preview("appearance-refined-double-font-colors")
        compose.onNodeWithTag("card_transparency_4").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { !state.busy && store.loadPreferences().cardOpacityPercent == 96 }
        preview("appearance-refined-double-font-cards")
        compose.onNodeWithTag("wallpaper_restore").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000) { !state.busy }
        assertEquals(WallpaperPreferences(), store.loadPreferences())
        preview("appearance-refined-double-font-footer")
    }

    @Test fun landscapeAtDoubleFontScaleKeepsPreviewCompactAndChoicesOperable() {
        show(2f)
        val before = store.loadPreferences()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
            compose.waitUntil(10000) {
                !state.busy &&
                    compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            assertEquals(before, state.preferences)
            compose.onNodeWithTag("wallpaper_preview").assertIsDisplayed()
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val sample = compose.onNodeWithTag("wallpaper_preview").fetchSemanticsNode().boundsInRoot
            assertTrue("Landscape preview must leave room for settings", sample.height < root.height * .55f)
            preview("appearance-refined-landscape-double-font-preview")
            compose.onNodeWithTag("appearance_combo_NIGHT").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(5000) { !state.busy && store.loadPreferences().themeMode == AppThemeMode.DARK }
            compose.onNodeWithTag("appearance_combo_NIGHT").assertIsSelected()
            compose.onNodeWithTag("theme_mode_LIGHT").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(5000) { !state.busy && store.loadPreferences().themeMode == AppThemeMode.LIGHT }
            compose.onNodeWithTag("accent_BLUE").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(5000) { !state.busy && store.loadPreferences().accentPreset == AccentPreset.BLUE }
            preview("appearance-refined-landscape-double-font-colors")
        } finally {
            assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0))
            compose.waitUntil(10000) {
                !state.busy && compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
        }
    }

    @Test fun blurPreviewsImmediatelyPersistsOnReleaseAndRollsBackOnSaveFailure() {
        store.import(Uri.fromFile(image()))
        show()
        assertEquals(12, state.preferences.photoBlurDp)
        compose.onNodeWithTag("wallpaper_blur").performScrollTo()
            .assertContentDescriptionEquals("图片模糊")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(24f) }
        compose.waitUntil { !state.busy }
        compose.onNodeWithTag("wallpaper_blur").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "24"))
        assertEquals(24, WallpaperStore(context).loadPreferences().photoBlurDp)
        val before = settings.readBytes()
        assertTrue(blockedWrite.mkdir()); File(blockedWrite, "blocked").writeText("fictional failure")
        compose.runOnIdle { state.previewBlur(0f) }
        assertEquals(0, state.preferences.photoBlurDp)
        compose.runOnIdle { state.saveBlur() }
        compose.waitUntil { !state.busy }
        assertEquals(24, state.preferences.photoBlurDp)
        assertNotNull(state.message)
        assertArrayEquals(before, settings.readBytes())
        compose.runOnIdle { state.previewCardOpacity(88f) }
        compose.runOnIdle { state.saveCardOpacity() }
        compose.waitUntil { !state.busy }
        assertEquals(96, state.preferences.cardOpacityPercent)
        assertArrayEquals(before, settings.readBytes())
    }

    @Test fun failedPhotoCommitKeepsOldImageAndCorruptionIsNotSilentlyOverwritten() {
        val old = store.importPhoto(Uri.fromFile(image()), WallpaperPreferences()).first
        val oldImage = File(context.noBackupFilesDir, old.photoName!!).readBytes()
        val before = settings.readBytes()
        assertTrue(blockedWrite.mkdir()); File(blockedWrite, "blocked").writeText("fictional failure")
        assertThrows(Exception::class.java) { store.importPhoto(Uri.fromFile(image(android.graphics.Color.RED)), old) }
        assertArrayEquals(before, settings.readBytes())
        assertArrayEquals(oldImage, File(context.noBackupFilesDir, old.photoName).readBytes())
        unblock()
        settings.writeText("fictional invalid settings")
        show()
        assertEquals("fictional invalid settings", settings.readText())
        assertNotNull(state.message)
        assertArrayEquals(oldImage, File(context.noBackupFilesDir, old.photoName).readBytes())
        compose.onNodeWithTag("wallpaper_preset_MINT").performScrollTo().performClick()
        compose.waitUntil { !state.busy }
        assertEquals(WallpaperPreset.MINT, store.loadPreferences().preset)
    }

    @Test fun unreadablePhotoDoesNotEraseItsReferenceOrFile() {
        val preferences = store.importPhoto(Uri.fromFile(image()), WallpaperPreferences()).first
        val photo = File(context.noBackupFilesDir, preferences.photoName!!)
        photo.writeText("fictional damaged image")
        val before = settings.readBytes()
        show()
        assertNull(state.image)
        assertNotNull(state.message)
        assertArrayEquals(before, settings.readBytes())
        assertEquals("fictional damaged image", photo.readText())
    }

    @Test fun systemColorsFollowChangesRefreshOnReturnAndNullKeepsPreviousPalette() {
        val manager = WallpaperManager.getInstance(InstrumentationRegistry.getInstrumentation().context)
        fun set(color: Int) {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.adoptShellPermissionIdentity("android.permission.SET_WALLPAPER")
            try { manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM) }
            finally { automation.dropShellPermissionIdentity(); bitmap.recycle() }
        }
        set(android.graphics.Color.RED)
        show()
        compose.onNodeWithTag("wallpaper_mode_SYSTEM_COLORS").performScrollTo().performClick()
        compose.waitUntil(10000) { !state.busy && state.systemColors?.firstOrNull()?.red?.let { it > .8f } == true }
        val red = state.systemColors
        compose.runOnIdle { state.acceptSystemColors(null) }
        assertEquals(red, state.systemColors)
        assertTrue(state.systemColorsUnavailable)
        set(android.graphics.Color.GREEN)
        compose.waitUntil(10000) { state.systemColors?.firstOrNull()?.green?.let { it > .8f } == true }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val previous = state.systemColors
        set(android.graphics.Color.BLUE)
        assertEquals(previous, state.systemColors)
        // Wallpaper changes can recreate a stopped Activity. Bring its task to the front as a
        // real launcher return would, instead of asking ActivityScenario to resume a stale instance.
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(context, WallpaperTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        // A wallpaper change may schedule another recreation while resuming. The
        // previous state's colour can already be blue before the replacement UI exists.
        compose.waitUntil(10000) {
            state.systemColors?.firstOrNull()?.blue?.let { it > .8f } == true &&
                runCatching { compose.onNodeWithTag("appearance_panel").isDisplayed() }.getOrDefault(false)
        }
        preview("wallpaper-system-colors")
    }

    @Test fun renderedPhotoBlurSoftensBackgroundButNotForeground() {
        val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) for (y in 0 until height) setPixel(x, y,
                if (x % 8 < 4) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        val local = WallpaperState().apply {
            image = bitmap.asImageBitmap()
            preferences = WallpaperPreferences(mode = WallpaperMode.PHOTO, photoBlurDp = 0, photoName = "wallpaper.jpg")
        }
        compose.runOnUiThread { WallpaperTestActivity.scene = { GongXiaoZhuTheme {
            Box(Modifier.size(200.dp, 400.dp)) {
                WallpaperBackground(local, Modifier.fillMaxSize().testTag("blur_background"))
                Text("前景保持清晰", Modifier.align(Alignment.Center).background(Color.White).testTag("foreground"), color = Color.Black)
            }
        } } }
        fun contrast(): Float {
            val pixels = compose.onNodeWithTag("blur_background").captureToImage().asAndroidBitmap()
            val y = pixels.height / 4
            return (11 until pixels.width - 10).sumOf { x -> abs(android.graphics.Color.red(pixels.getPixel(x, y)) -
                android.graphics.Color.red(pixels.getPixel(x - 1, y))) }.toFloat() / (pixels.width - 21)
        }
        val original = contrast()
        val foreground = compose.onNodeWithTag("foreground").captureToImage().asAndroidBitmap()
        compose.runOnIdle { local.preferences = local.preferences.copy(photoBlurDp = 24) }
        assertTrue("Gaussian blur must visibly soften the image", contrast() < original * .25f)
        compose.onNodeWithTag("foreground").assertIsDisplayed()
        assertTrue("Foreground pixels must not be blurred", foreground.sameAs(
            compose.onNodeWithTag("foreground").captureToImage().asAndroidBitmap()))
        preview("wallpaper-photo-blur")
    }

    private fun preview(name: String) {
        context.getExternalFilesDir(null)?.resolve("$name.png")?.outputStream()?.use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
