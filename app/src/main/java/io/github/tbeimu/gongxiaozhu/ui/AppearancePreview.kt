package io.github.tbeimu.gongxiaozhu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.min

/**
 * A small visual sample of the existing timetable, paper cards and capsule dock.
 * Uses the real background and palette. Geometry stays legible at any font scale;
 * the surrounding labels describe the sample, rather than inventing user data.
 */
@Composable
internal fun AppearancePreview(
    wallpaper: WallpaperState,
    palette: GxzPalette,
    modifier: Modifier,
    compact: Boolean = false,
) {
    // Previewing another theme must never change the window's system bars.
    CompositionLocalProvider(LocalGxzPalette provides palette) {
        Box(modifier) {
            WallpaperBackground(wallpaper, Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val logicalWidth = if (compact) 160f else 320f
                val logicalHeight = if (compact) 110f else 176f
                val unit = min(size.width / logicalWidth, size.height / logicalHeight)
                withTransform({
                    translate((size.width - logicalWidth * unit) / 2f,
                        (size.height - logicalHeight * unit) / 2f)
                    scale(unit, unit, Offset.Zero)
                }) {
                    if (compact) drawCompactAppearance(palette) else drawFullAppearance(palette)
                }
            }
        }
    }
}

private fun DrawScope.sampleRect(color: Color, x: Float, y: Float, width: Float, height: Float, radius: Float) =
    drawRoundRect(color, Offset(x, y), Size(width, height), CornerRadius(radius))

private fun DrawScope.sampleCard(palette: GxzPalette, x: Float, y: Float, width: Float, height: Float, radius: Float) {
    sampleRect(palette.glass, x, y, width, height, radius)
    drawRoundRect(palette.glassEdge, Offset(x, y), Size(width, height), CornerRadius(radius),
        style = Stroke(.8f))
}

private fun DrawScope.drawFullAppearance(palette: GxzPalette) {
    // Header and weekday marks, kept separate from the course palette.
    sampleRect(palette.ink, 18f, 16f, 53f, 7f, 3.5f)
    sampleRect(palette.inkSoft.copy(alpha = .46f), 18f, 29f, 34f, 3f, 1.5f)
    drawCircle(palette.accent, 3.5f, Offset(82f, 19.5f))
    repeat(7) { day ->
        val x = 177f + day * 19f
        sampleRect(if (day == 2) palette.accent else palette.inkFaint.copy(alpha = .4f),
            x, 21f, if (day == 2) 10f else 5f, 4f, 2f)
    }

    val gridX = 18f
    val gridY = 47f
    val cellWidth = 17f
    val gap = 4f
    val rowHeight = 20f
    repeat(7) { day ->
        repeat(4) { row ->
            sampleRect(palette.well, gridX + day * (cellWidth + gap), gridY + row * (rowHeight + gap),
                cellWidth, rowHeight, 4f)
        }
    }
    val courses = listOf(
        intArrayOf(0, 0, 2, 0), intArrayOf(0, 3, 1, 4),
        intArrayOf(1, 1, 2, 2), intArrayOf(2, 0, 1, 7),
        intArrayOf(2, 2, 2, 9), intArrayOf(3, 1, 1, 5),
        intArrayOf(4, 0, 2, 3), intArrayOf(4, 3, 1, 8),
        intArrayOf(5, 2, 1, 1),
    )
    courses.forEach { (day, row, span, index) ->
        val tone = courseTone(index, palette.dark)
        val x = gridX + day * (cellWidth + gap)
        val y = gridY + row * (rowHeight + gap)
        sampleRect(tone.fill, x, y, cellWidth, rowHeight * span + gap * (span - 1), 4f)
        sampleRect(tone.text.copy(alpha = .36f), x + 4f, y + 6f, 9f, 2f, 1f)
    }

    // These two cards expose the chosen opacity against the actual wallpaper.
    sampleCard(palette, 177f, 47f, 126f, 39f, 10f)
    drawCircle(palette.accent.copy(alpha = .12f), 9f, Offset(192f, 65f))
    drawCircle(palette.accent, 3.5f, Offset(192f, 65f))
    sampleRect(palette.ink, 209f, 58f, 40f, 4f, 2f)
    sampleRect(palette.inkSoft.copy(alpha = .5f), 209f, 69f, 61f, 3f, 1.5f)

    sampleCard(palette, 177f, 96f, 126f, 43f, 10f)
    sampleRect(palette.ink, 190f, 107f, 45f, 4f, 2f)
    sampleRect(palette.inkSoft.copy(alpha = .5f), 190f, 118f, 64f, 3f, 1.5f)
    sampleRect(palette.accent.copy(alpha = .13f), 264f, 106f, 26f, 22f, 6f)
    sampleRect(palette.accent, 271f, 114f, 12f, 5f, 2.5f)

    // The existing white capsule and its selected dot, not a new navigation design.
    sampleRect(palette.glassStrong, 106f, 149f, 108f, 22f, 11f)
    drawRoundRect(palette.glassEdge, Offset(106f, 149f), Size(108f, 22f),
        CornerRadius(11f), style = Stroke(.6f))
    sampleRect(palette.ink, 122f, 156f, 15f, 3f, 1.5f)
    sampleRect(palette.inkFaint.copy(alpha = .6f), 153f, 156f, 15f, 3f, 1.5f)
    sampleRect(palette.inkFaint.copy(alpha = .6f), 184f, 156f, 15f, 3f, 1.5f)
    drawCircle(palette.accent, 1.8f, Offset(129.5f, 163.5f))
}

private fun DrawScope.drawCompactAppearance(palette: GxzPalette) {
    sampleRect(palette.ink, 14f, 13f, 38f, 4.5f, 2.25f)
    sampleRect(palette.inkSoft.copy(alpha = .4f), 14f, 23f, 25f, 2.5f, 1.25f)
    drawCircle(palette.accent, 3f, Offset(63f, 15.5f))
    sampleCard(palette, 12f, 35f, 136f, 28f, 8f)
    sampleRect(palette.ink, 22f, 44f, 42f, 3.5f, 1.75f)
    sampleRect(palette.inkSoft.copy(alpha = .45f), 22f, 53f, 61f, 2.5f, 1.25f)
    sampleRect(palette.accent.copy(alpha = .12f), 115f, 42f, 22f, 15f, 4f)
    sampleRect(palette.accent, 121f, 47f, 10f, 4f, 2f)
    listOf(0, 2, 3).forEachIndexed { index, hue ->
        val tone = courseTone(hue, palette.dark)
        val x = 12f + 47f * index
        sampleRect(tone.fill, x, 72f, 42f, 22f, 5f)
        sampleRect(tone.text.copy(alpha = .32f), x + 7f, 79f, 21f, 2.5f, 1.25f)
    }
    sampleRect(palette.inkFaint.copy(alpha = .22f), 62f, 103f, 36f, 2.5f, 1.25f)
}
