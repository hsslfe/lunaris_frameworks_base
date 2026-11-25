/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.statusbar.pipeline.battery.ui.composable

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import com.android.systemui.statusbar.pipeline.battery.data.repository.NeonRingOptions
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryGlyph
import kotlinx.coroutines.delay

internal const val NEON_RING_SCALE = 1.35f

/**
 * RR neon ring: the level as a glowing arc, with a spark circling while charging. By level it's the
 * accent, red at 20% and under (pulsing under 10%) and amber in Battery Saver; it can also stay in
 * the accent or follow the status bar like the stock icon.
 */
@Composable
internal fun NeonRingBattery(
    prefs: NeonRingOptions,
    attr: BatteryGlyph?,
    levelProvider: () -> Int?,
    showLevelProvider: () -> Boolean,
    colorsProvider: () -> BatteryColors,
    modifier: Modifier = Modifier,
    contentDescription: String = "",
) {
    val context = LocalContext.current
    val accentDark = Color(context.getColor(android.R.color.system_accent1_300))
    val accentLight = Color(context.getColor(android.R.color.system_accent1_600))
    val low = Color(0xFFFF3030)
    val saver = Color(0xFFFFB02E)
    val textMeasurer = rememberTextMeasurer()
    val charging = attr is BatteryGlyph.Bolt || attr is BatteryGlyph.Defend
    // Each runs only while it shows
    val spin = if (prefs.alwaysShowSpark || (charging && prefs.chargingSpark)) neonRingSpin() else null
    val pulse = if (!charging && (levelProvider() ?: 100) < 10) neonRingPulse() else null
    Canvas(modifier = modifier, contentDescription = contentDescription) {
        val level = levelProvider()
        val colors = colorsProvider()
        // Dark icons mean a light status bar: a deeper accent and a darker track.
        val lightBar = colors is BatteryColors.LightTheme
        val accent = if (lightBar) accentLight else accentDark
        // Thin enough that the glow leaves room for the level inside.
        val stroke = size.height / 9f
        val radius = size.height / 2f - stroke * 1.1f
        val center = Offset(size.width / 2, size.height / 2)
        val color = when (prefs.colorMode) {
            3 -> Color(prefs.customRingColor)
            2 -> colors.attribution
            1 -> accent
            else -> when {
                attr is BatteryGlyph.Plus -> saver
                level != null && level <= 20 -> low
                else -> accent
            }
        }
        val alpha = pulse?.value ?: 1f
        val track = if (lightBar) colors.attribution.copy(alpha = 0.28f) else colors.backgroundOnly
        drawCircle(track, radius, center, style = Stroke(stroke))
        if (level != null && level > 0) {
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arc = Size(radius * 2, radius * 2)
            drawArc(color.copy(alpha = (if (lightBar) 0.2f else 0.35f) * alpha), 270f,
                3.6f * level, false, topLeft, arc,
                style = Stroke(stroke * 2f, cap = StrokeCap.Round))
            drawArc(color.copy(alpha = alpha), 270f, 3.6f * level, false, topLeft, arc,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (showLevelProvider() && level != null) {
            drawRingLevel(textMeasurer, level, colors.attribution, size.height - stroke * 4.4f)
        }
        if (spin != null) {
            val a = Math.toRadians((spin.value - 90f).toDouble())
            val sparkColor = when {
                prefs.sparkColor != 0 -> Color(prefs.sparkColor)
                lightBar -> colors.attribution
                else -> Color.White
            }
            drawCircle(sparkColor, stroke * 0.75f,
                Offset(center.x + radius * kotlin.math.cos(a).toFloat(),
                    center.y + radius * kotlin.math.sin(a).toFloat()))
        }
    }
}

/**
 * Where the neon ring's spark is around the ring, in degrees. It moves in steps, 30 a second:
 * every new value draws the status bar again, and with it whatever glass is on screen, which on
 * every frame was most of what the phone drew while it charged.
 */
@Composable
private fun neonRingSpin(): State<Float> {
    val spin = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(33)
            spin.floatValue = (spin.floatValue + SPARK_STEP) % 360f
        }
    }
    return spin
}

/** How bright the neon ring is as it pulses on a low battery, a step a second. */
@Composable
private fun neonRingPulse(): State<Float> {
    val pulse = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            pulse.floatValue = if (pulse.floatValue == 1f) 0.4f else 1f
        }
    }
    return pulse
}

// 48 steps to a turn of 1.6 s: 30 a second
private const val SPARK_STEP = 7.5f

/** The level centered in a ring whose inside is [inner] px across, shrunk to fit "100". */
private fun DrawScope.drawRingLevel(
    measurer: TextMeasurer,
    level: Int,
    color: Color,
    inner: Float,
) {
    val text = level.toString()
    fun measure(px: Float) =
        measurer.measure(
            text = text,
            style =
                TextStyle(
                    color = color,
                    fontSize = px.toSp(),
                    fontWeight = FontWeight.Bold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle =
                        LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                ),
        )
    val px = inner * 0.6f
    var layout = measure(px)
    val maxWidth = inner * 0.9f
    if (layout.size.width > maxWidth) {
        layout = measure(px * maxWidth / layout.size.width)
    }
    drawText(
        layout,
        topLeft =
            Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f),
    )
}
