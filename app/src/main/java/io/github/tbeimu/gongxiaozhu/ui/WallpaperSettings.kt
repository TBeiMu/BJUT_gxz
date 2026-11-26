package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun WallpaperSettingsPanel(wallpaper: WallpaperState) {
    Column(Modifier.fillMaxWidth().testTag("appearance_panel"),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        AppearanceHero(wallpaper)
        RecommendedAppearances(wallpaper)
        BackgroundSettings(wallpaper)
        ThemeAndAccentSettings(wallpaper)
        CardTransparencySettings(wallpaper)
        AppearanceFooter(wallpaper)
    }
}

@Composable
private fun AppearanceHero(wallpaper: WallpaperState) {
    GlassCard(shape = RoundedCornerShape(28.dp), padding = PaddingValues(16.dp), spacing = 0.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val availableWidth = maxWidth
            if (availableWidth >= 500.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    AppearanceHeroHeading(wallpaper)
                    AppearanceHeroSummary(wallpaper)
                }
                AppearanceHeroSample(wallpaper, Modifier.weight(1.15f)
                    .height(if (availableWidth < 640.dp) 120.dp else 148.dp))
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppearanceHeroHeading(wallpaper)
                AppearanceHeroSample(wallpaper, Modifier.fillMaxWidth()
                    .height((availableWidth * (176f / 320f)).coerceAtMost(176.dp)))
                AppearanceHeroSummary(wallpaper)
            }
        }
    }
}

@Composable
private fun AppearanceHeroHeading(wallpaper: WallpaperState) {
    val palette = gxz
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("效果预览", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (wallpaper.busy) BrandLoader(Modifier.size(18.dp))
            else ThemeModeGlyph(wallpaper.preferences.themeMode, Modifier.size(18.dp), palette.inkSoft)
        }
    }
}

@Composable
private fun AppearanceHeroSummary(wallpaper: WallpaperState) {
    val preferences = wallpaper.preferences
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(gxz.accent))
        Text("${preferences.themeMode.title} · ${preferences.accentPreset.title} · 通透 ${100 - preferences.cardOpacityPercent}%",
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft)
    }
}

@Composable
private fun AppearanceHeroSample(wallpaper: WallpaperState, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    AppearancePreview(wallpaper, gxz, modifier.clip(shape).border(.7.dp, gxz.hairline, shape)
        .testTag("wallpaper_preview").semantics {
            contentDescription = "界面效果示例，含课程配色、强调标记和半透明卡片"
        })
}

private enum class AppearanceCombo(
    val title: String, val mode: AppThemeMode, val accent: AccentPreset, val cardOpacityPercent: Int,
) {
    PAPER("纸感经典", AppThemeMode.SYSTEM, AccentPreset.VERMILION, 96),
    SKY("清爽晴空", AppThemeMode.LIGHT, AccentPreset.BLUE, 92),
    NIGHT("深夜松绿", AppThemeMode.DARK, AccentPreset.GREEN, 92);

    fun matches(preferences: WallpaperPreferences) = preferences.themeMode == mode &&
        preferences.accentPreset == accent && preferences.cardOpacityPercent == cardOpacityPercent

    val description: String get() = "${mode.title} · ${accent.title}"
}

@Composable
private fun RecommendedAppearances(wallpaper: WallpaperState) {
    val systemDark = isSystemInDarkTheme()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppearanceHeading("推荐搭配", "一键应用，保留当前壁纸")
        BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
            val expanded = (maxWidth - 18.dp) / 3 < (62f * LocalDensity.current.fontScale + 22f).dp
            val thumbnailWidth = if (maxWidth < 300.dp) 64.dp else 86.dp
            if (expanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppearanceCombo.entries.forEach {
                    AppearanceComboCard(wallpaper, it, systemDark, true, Modifier.fillMaxWidth(), thumbnailWidth)
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                AppearanceCombo.entries.forEach {
                    AppearanceComboCard(wallpaper, it, systemDark, false, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AppearanceComboCard(
    wallpaper: WallpaperState, combo: AppearanceCombo, systemDark: Boolean, expanded: Boolean, modifier: Modifier,
    thumbnailWidth: Dp = 86.dp,
) {
    val palette = gxz
    val selected = combo.matches(wallpaper.preferences)
    val sample = wallpaper.preferences.copy(themeMode = combo.mode,
        accentPreset = combo.accent, cardOpacityPercent = combo.cardOpacityPercent)
    val previewPalette = appearancePalette(sample, systemDark)
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    val card = modifier.pressScale(interaction, .975f).glass(palette, shape)
        .border(selectionBorderWidth(selected), selectionBorderColor(selected, palette), shape)
        .testTag("appearance_combo_${combo.name}")
        .selectable(selected, interactionSource = interaction, indication = null,
            enabled = !wallpaper.busy, role = Role.RadioButton,
            onClick = { wallpaper.applyAppearance(combo.mode, combo.accent, combo.cardOpacityPercent) })
        .padding(9.dp)
    if (expanded) Row(card, horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AppearancePreview(wallpaper, previewPalette,
            Modifier.width(thumbnailWidth).aspectRatio(160f / 110f).clip(RoundedCornerShape(12.dp)), compact = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(combo.title, style = MaterialTheme.typography.labelLarge, color = palette.ink)
            Text(combo.description, style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
            Text("通透 ${100 - combo.cardOpacityPercent}%", style = MaterialTheme.typography.bodySmall,
                color = palette.inkFaint)
        }
        SelectionMark(selected, Modifier.padding(end = 3.dp))
    } else Column(card, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            AppearancePreview(wallpaper, previewPalette,
                Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)), compact = true)
            if (selected) SelectionMark(true, Modifier.align(Alignment.TopEnd).padding(5.dp))
        }
        Column(Modifier.padding(horizontal = 2.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(combo.title, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), color = palette.ink)
            Text(combo.mode.title, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = palette.inkSoft)
        }
    }
}

@Composable
private fun BackgroundSettings(wallpaper: WallpaperState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppearanceHeading("背景")
        GlassCard(padding = PaddingValues(16.dp), spacing = 16.dp) {
            AppearanceSegments(WallpaperMode.entries.map {
                SegmentOption(it.name, wallpaperModeLabel(it), "wallpaper_mode_${it.name}")
            }, wallpaper.preferences.mode.name, !wallpaper.busy,
                onSelect = { wallpaper.selectMode(WallpaperMode.valueOf(it)) })
            if (LocalReducedMotion.current) WallpaperModeContent(wallpaper, wallpaper.preferences.mode)
            else AnimatedContent(wallpaper.preferences.mode, transitionSpec = {
                (fadeIn(tween(Motion.MEDIUM, 35)) +
                    slideInVertically(tween(Motion.MEDIUM, easing = Motion.Decelerate)) { it / 14 }) togetherWith
                    fadeOut(tween(Motion.SHORT)) using SizeTransform(clip = false) { _, _ ->
                        tween(Motion.MEDIUM, easing = Motion.Emphasized)
                    }
            }, label = "背景选项切换") { mode -> WallpaperModeContent(wallpaper, mode) }
        }
    }
}

private fun wallpaperModeLabel(mode: WallpaperMode): String = when (mode) {
    WallpaperMode.PRESET -> "颜色背景"
    WallpaperMode.SYSTEM_COLORS -> "壁纸取色"
    WallpaperMode.PHOTO -> "自选壁纸"
}

@Composable
private fun WallpaperModeContent(wallpaper: WallpaperState, mode: WallpaperMode) {
    when (mode) {
        WallpaperMode.PRESET -> BackgroundPresetGrid(wallpaper)
        WallpaperMode.SYSTEM_COLORS -> WallpaperColorDetails(wallpaper)
        WallpaperMode.PHOTO -> PhotoBackgroundSettings(wallpaper)
    }
}

@Composable
private fun BackgroundPresetGrid(wallpaper: WallpaperState) {
    val palette = gxz
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        val minimumWidth = (44f * LocalDensity.current.fontScale + 24f).dp
        val columns = when {
            maxWidth >= minimumWidth * 3 + 20.dp -> 3
            maxWidth >= minimumWidth * 2 + 10.dp -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WallpaperPreset.entries.chunked(columns).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { preset ->
                        val selected = wallpaper.preferences.preset == preset
                        val source = wallpaperPresetSwatch(preset)
                        val colors = if (palette.dark) source.map { lerp(it, Color(0xFF17202A), .64f) } else source
                        val shape = RoundedCornerShape(16.dp)
                        val interaction = remember { MutableInteractionSource() }
                        Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 74.dp)
                            .pressScale(interaction, .97f).clip(shape)
                            .background(Brush.linearGradient(listOf(colors[0], colors[1],
                                lerp(colors[2], wallpaperPresetBloom(preset), if (palette.dark) .06f else .4f))))
                            .border(selectionBorderWidth(selected), selectionBorderColor(selected, palette), shape)
                            .testTag("wallpaper_preset_${preset.name}")
                            .selectable(selected, interactionSource = interaction, indication = null,
                                enabled = !wallpaper.busy, role = Role.RadioButton,
                                onClick = { wallpaper.selectPreset(preset) }).padding(11.dp)) {
                            Text(preset.title, Modifier.align(Alignment.BottomStart).padding(top = 22.dp),
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                                color = if (palette.dark) Color.White else palette.ink,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                            if (selected) SelectionMark(true, Modifier.align(Alignment.TopEnd))
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun WallpaperColorDetails(wallpaper: WallpaperState) {
    val palette = gxz
    val colors = wallpaper.systemColors ?: wallpaperPresetSwatch(WallpaperPreset.BLUE_CYAN)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            colors.take(3).forEach { color ->
                Box(Modifier.size(18.dp).clip(CircleShape).background(color).border(.7.dp, palette.hairline, CircleShape))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("跟随桌面壁纸主色", style = MaterialTheme.typography.bodyMedium)
            Text(if (wallpaper.systemColorsUnavailable)
                "暂未取得颜色，${if (wallpaper.systemColors == null) "显示蓝青背景" else "保留上次颜色"}"
                else "随壁纸变化更新柔和背景",
                style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
        }
    }
}

@Composable
private fun PhotoBackgroundSettings(wallpaper: WallpaperState) {
    val palette = gxz
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(palette.well),
                contentAlignment = Alignment.Center) { LineGlyph(AppGlyph.IMAGE, Modifier.size(20.dp), palette.inkSoft) }
            Text("图片模糊", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${wallpaper.preferences.photoBlurDp}", style = MaterialTheme.typography.titleMedium
                .copy(fontFeatureSettings = "tnum"), color = palette.ink)
        }
        QuietSlider(wallpaper.preferences.photoBlurDp.toFloat(), wallpaper.previewBlur, wallpaper.saveBlur,
            0f..24f, 23, !wallpaper.busy && wallpaper.image != null, "wallpaper_blur",
            "图片模糊", wallpaper.preferences.photoBlurDp.toString())
        SliderEndpoints("清晰", "柔和")
        OutlinedButton(onClick = wallpaper.choose, enabled = !wallpaper.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            border = androidx.compose.foundation.BorderStroke(.8.dp, palette.hairline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink)) {
            LineGlyph(AppGlyph.IMAGE, Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("更换壁纸", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ThemeAndAccentSettings(wallpaper: WallpaperState) {
    val palette = gxz
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppearanceHeading("主题与配色")
        GlassCard(padding = PaddingValues(16.dp), spacing = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("界面主题", style = MaterialTheme.typography.bodyMedium, color = palette.inkSoft)
                AppearanceSegments(AppThemeMode.entries.map {
                    SegmentOption(it.name, it.title, "theme_mode_${it.name}", it)
                }, wallpaper.preferences.themeMode.name, !wallpaper.busy,
                    onSelect = { wallpaper.selectThemeMode(AppThemeMode.valueOf(it)) })
            }
            HorizontalDivider(thickness = .7.dp, color = palette.hairline)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("强调色", style = MaterialTheme.typography.bodyMedium, color = palette.inkSoft)
                BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
                    val fontScale = LocalDensity.current.fontScale
                    val expanded = (maxWidth - 18.dp) / 4 < (36f * fontScale + 12f).dp
                    val columns = if ((maxWidth - 8.dp) / 2 < (42f * fontScale + 57f).dp) 1 else 2
                    if (expanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentPreset.entries.chunked(columns).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { AccentChoice(wallpaper, it, true, Modifier.weight(1f)) }
                            }
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AccentPreset.entries.forEach { AccentChoice(wallpaper, it, false, Modifier.weight(1f)) }
                    }
                }
                Text("只改变强调标记，课程颜色保持原样。",
                    style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
            }
        }
    }
}

private data class SegmentOption(val key: String, val title: String, val tag: String, val theme: AppThemeMode? = null)

@Composable
private fun AppearanceSegments(options: List<SegmentOption>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val palette = gxz
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        val vertical = (maxWidth - 14.dp) / 3 < (52f * LocalDensity.current.fontScale + 12f).dp
        if (vertical) Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            options.forEach {
                AppearanceSegment(it, it.key == selected, enabled, true, { onSelect(it.key) }, Modifier.fillMaxWidth())
            }
        } else Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(palette.well).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            options.forEach {
                AppearanceSegment(it, it.key == selected, enabled, false, { onSelect(it.key) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AppearanceSegment(
    option: SegmentOption, selected: Boolean, enabled: Boolean, expanded: Boolean, onClick: () -> Unit, modifier: Modifier,
) {
    val palette = gxz
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    val target = if (selected) palette.glassStrong else Color.Transparent
    val fill = if (LocalReducedMotion.current) target
        else animateColorAsState(target, tween(Motion.SHORT), label = "选项底色").value
    val item = modifier.heightIn(min = 48.dp).pressScale(interaction, .98f).clip(shape).background(fill)
        .border(.7.dp, if (selected) palette.hairline else Color.Transparent, shape).testTag(option.tag)
        .selectable(selected, interactionSource = interaction, indication = null,
            enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 6.dp, vertical = 10.dp)
    val tint = if (!enabled) palette.inkFaint else if (selected) palette.ink else palette.inkSoft
    if (expanded) Row(item, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        option.theme?.let { ThemeModeGlyph(it, Modifier.padding(start = 8.dp).size(18.dp), tint) }
        Text(option.title, Modifier.weight(1f).padding(start = if (option.theme == null) 8.dp else 0.dp),
            style = MaterialTheme.typography.labelLarge, color = tint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
        SelectionMark(selected, Modifier.padding(end = 8.dp))
    } else Column(item, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        option.theme?.let { ThemeModeGlyph(it, Modifier.size(18.dp), tint) }
        Text(option.title, style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp),
            color = tint, textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun AccentChoice(wallpaper: WallpaperState, accent: AccentPreset, expanded: Boolean, modifier: Modifier) {
    val palette = gxz
    val selected = wallpaper.preferences.accentPreset == accent
    val (color, onColor) = accentColors(accent, palette.dark)
    val interaction = remember { MutableInteractionSource() }
    val item = modifier.heightIn(min = if (expanded) 52.dp else 78.dp).pressScale(interaction, .97f)
        .clip(RoundedCornerShape(14.dp)).background(if (selected) palette.well else Color.Transparent)
        .testTag("accent_${accent.name}")
        .selectable(selected, interactionSource = interaction, indication = null, enabled = !wallpaper.busy,
            role = Role.RadioButton, onClick = { wallpaper.selectAccentPreset(accent) }).padding(6.dp)
    if (expanded) Row(item, horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        AccentSwatch(selected, color, onColor, palette)
        Text(accent.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = palette.ink, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    } else Column(item, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        AccentSwatch(selected, color, onColor, palette)
        Text(accent.title, style = MaterialTheme.typography.labelMedium, color = palette.ink, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AccentSwatch(selected: Boolean, color: Color, onColor: Color, palette: GxzPalette) {
    Box(Modifier.size(38.dp).border(if (selected) 1.dp else 0.dp,
        if (selected) palette.ink.copy(alpha = .65f) else Color.Transparent, CircleShape).padding(4.dp),
        contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            if (selected) LineGlyph(AppGlyph.CHECK, Modifier.size(16.dp), onColor)
        }
    }
}

@Composable
private fun CardTransparencySettings(wallpaper: WallpaperState) {
    val palette = gxz
    val value = 100 - wallpaper.preferences.cardOpacityPercent
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text("卡片通透度", Modifier.weight(1f).alignByBaseline(), style = MaterialTheme.typography.titleMedium, color = palette.ink)
            Text("$value%", Modifier.alignByBaseline().testTag("card_transparency_value"),
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = palette.ink)
        }
        GlassCard(padding = PaddingValues(16.dp), spacing = 10.dp) {
            Text("数值越大，卡片越透明；100% 时卡片底色完全透明。", style = MaterialTheme.typography.bodySmall, color = palette.inkSoft)
            QuietSlider(value.toFloat(), { wallpaper.previewCardOpacity(100f - it) }, wallpaper.saveCardOpacity,
                (100 - MAX_CARD_OPACITY_PERCENT).toFloat()..(100 - MIN_CARD_OPACITY_PERCENT).toFloat(),
                48, !wallpaper.busy, "card_transparency_slider", "卡片通透度", "$value%")
            SliderEndpoints("更清晰", "更通透")
            Spacer(Modifier.height(2.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
                val choices = listOf(4 to "默认", 20 to "轻透", 100 to "全透")
                val expanded = (maxWidth - 16.dp) / 3 < (44f * LocalDensity.current.fontScale + 24f).dp
                if (expanded) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { (transparency, title) ->
                        TransparencyChoice(wallpaper, transparency, title, Modifier.fillMaxWidth())
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { (transparency, title) ->
                        TransparencyChoice(wallpaper, transparency, title, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TransparencyChoice(wallpaper: WallpaperState, transparency: Int, title: String, modifier: Modifier) {
    val palette = gxz
    val selected = 100 - wallpaper.preferences.cardOpacityPercent == transparency
    val shape = RoundedCornerShape(13.dp)
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.heightIn(min = 44.dp).pressScale(interaction, .975f).clip(shape)
        .background(if (selected) palette.well else Color.Transparent)
        .border(.7.dp, if (selected) palette.ink.copy(alpha = .18f) else palette.hairline, shape)
        .testTag("card_transparency_$transparency")
        .selectable(selected, interactionSource = interaction, indication = null, enabled = !wallpaper.busy,
            role = Role.RadioButton, onClick = {
                wallpaper.previewCardOpacity((100 - transparency).toFloat())
                wallpaper.saveCardOpacity()
            }).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = palette.ink)
        Text("$transparency%", style = MaterialTheme.typography.labelMedium, color = palette.inkSoft)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietSlider(
    value: Float, onChange: (Float) -> Unit, onFinished: () -> Unit,
    range: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean, tag: String,
    label: String, valueLabel: String,
) {
    val palette = gxz
    Slider(value, onChange, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).semantics {
        contentDescription = label
        stateDescription = valueLabel
    },
        enabled = enabled, onValueChangeFinished = onFinished, valueRange = range, steps = steps,
        thumb = {
            Box(Modifier.size(22.dp).testTag("${tag}_thumb").graphicsLayer { alpha = if (enabled) 1f else .45f }
                .shadow(if (palette.dark) 0.dp else 2.dp, CircleShape).clip(CircleShape).background(palette.glassStrong)
                .border(1.5.dp, palette.ink, CircleShape))
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(4.dp).testTag("${tag}_track")) {
                val fraction = ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                val activeWidth = size.width * fraction
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(palette.well, cornerRadius = radius)
                drawRoundRect(palette.ink.copy(alpha = if (enabled) .8f else .3f),
                    topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - activeWidth else 0f, 0f),
                    size = Size(activeWidth, size.height), cornerRadius = radius)
            }
        })
}

@Composable
private fun SliderEndpoints(start: String, end: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(start, style = MaterialTheme.typography.labelMedium, color = gxz.inkFaint)
        Text(end, style = MaterialTheme.typography.labelMedium, color = gxz.inkFaint)
    }
}

@Composable
private fun AppearanceHeading(title: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = gxz.ink)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = gxz.inkSoft) }
    }
}

@Composable
private fun AppearanceFooter(wallpaper: WallpaperState) {
    val palette = gxz
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (wallpaper.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                color = palette.inkSoft, trackColor = palette.well)
        }
        TextButton(onClick = wallpaper.clear, enabled = !wallpaper.busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("wallpaper_restore"),
            colors = ButtonDefaults.textButtonColors(contentColor = palette.inkSoft)) {
            LineGlyph(AppGlyph.REFRESH, Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text("恢复默认外观", style = MaterialTheme.typography.labelLarge)
        }
        Text("已选图片与模糊强度会保留", style = MaterialTheme.typography.bodySmall,
            color = palette.inkFaint, textAlign = TextAlign.Center)
        wallpaper.message?.let {
            Text(it, Modifier.fillMaxWidth().padding(top = 6.dp, start = 8.dp, end = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = palette.inkSoft, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val palette = gxz
    Box(modifier.size(17.dp).clip(CircleShape).background(if (selected) palette.ink else Color.Transparent),
        contentAlignment = Alignment.Center) {
        if (selected) LineGlyph(AppGlyph.CHECK, Modifier.size(11.dp), palette.onInk)
    }
}

@Composable
private fun ThemeModeGlyph(mode: AppThemeMode, modifier: Modifier, color: Color) {
    if (mode == AppThemeMode.SYSTEM) LineGlyph(AppGlyph.DEVICE, modifier, color)
    else Canvas(modifier) {
        with(GlyphPen(this, color, 1.6f)) {
            if (mode == AppThemeMode.LIGHT) {
                ring(12f, 12f, 4.4f)
                poly(12f, 2f, 12f, 4.1f); poly(12f, 19.9f, 12f, 22f)
                poly(2f, 12f, 4.1f, 12f); poly(19.9f, 12f, 22f, 12f)
                poly(4.9f, 4.9f, 6.4f, 6.4f); poly(17.6f, 17.6f, 19.1f, 19.1f)
                poly(4.9f, 19.1f, 6.4f, 17.6f); poly(17.6f, 6.4f, 19.1f, 4.9f)
            } else path { u ->
                moveTo(20.3f * u, 14.5f * u)
                cubicTo(18.7f * u, 18.5f * u, 14.2f * u, 21.1f * u, 9.9f * u, 19.5f * u)
                cubicTo(5.6f * u, 17.9f * u, 3.3f * u, 13.2f * u, 5f * u, 8.9f * u)
                cubicTo(6.2f * u, 5.9f * u, 9f * u, 4f * u, 12f * u, 3.8f * u)
                cubicTo(9.4f * u, 6.4f * u, 9.3f * u, 10.2f * u, 11.5f * u, 12.9f * u)
                cubicTo(13.7f * u, 15.6f * u, 17.3f * u, 16.2f * u, 20.3f * u, 14.5f * u)
                close()
            }
        }
    }
}

@Composable
private fun selectionBorderWidth(selected: Boolean) =
    if (LocalReducedMotion.current) if (selected) 1.1.dp else .7.dp
    else animateDpAsState(if (selected) 1.1.dp else .7.dp, Motion.snappy(), label = "选中描边").value

@Composable
private fun selectionBorderColor(selected: Boolean, palette: GxzPalette): Color {
    val target = if (selected) palette.ink.copy(alpha = .65f) else palette.hairline
    return if (LocalReducedMotion.current) target
        else animateColorAsState(target, tween(Motion.SHORT), label = "选中边线").value
}
