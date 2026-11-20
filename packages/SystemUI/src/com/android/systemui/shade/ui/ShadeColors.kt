/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shade.ui

import android.content.Context
import android.content.res.Configuration
import android.os.UserHandle
import android.provider.Settings
import com.android.internal.graphics.ColorUtils
import com.android.internal.graphics.cam.Cam
import com.android.systemui.qs.shared.style.LiquidGlass
import com.android.systemui.qs.shared.style.LiquidGlassTune
import kotlin.math.roundToInt
import com.android.systemui.res.R

object ShadeColors {

    const val SHADE_STYLE_SETTING = "shade_color_style"

    @JvmField
    val LIQUID_GLASS_COLOR_KEYS = arrayOf(
        SHADE_STYLE_SETTING,
        "liquid_glass",
        "liquid_glass_style",
        "liquid_glass_dual_tone",
    )

    private data class ShadePreset(
        val panelTone: Float,
        val panelChroma: Float,
        val panelAlpha: Float,
        val fallbackTone: Float,
        val fallbackChroma: Float,
        val cardToneOffset: Float,
        val cardAlpha: Float,
        val useAccent: Boolean,
    )

    private fun shadePreset(context: Context): ShadePreset? {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return when (Settings.Secure.getIntForUser(
            context.contentResolver, SHADE_STYLE_SETTING, 0, UserHandle.USER_CURRENT,
        )) {
            1 -> if (night) ShadePreset(8f, 4f, .40f, 10f, 4f, 8f, .30f, false)
                else ShadePreset(98f, 2f, .55f, 95f, 3f, -5f, .45f, false)
            2 -> if (night) ShadePreset(16f, 26f, .58f, 13f, 16f, 7f, .40f, true)
                else ShadePreset(93f, 22f, .62f, 92f, 14f, -6f, .50f, true)
            else -> null
        }
    }

    private fun presetColor(
        context: Context,
        preset: ShadePreset,
        blurSupported: Boolean,
        notification: Boolean,
    ): Int {
        val hue = Cam.fromInt(context.getColor(
            if (preset.useAccent) android.R.color.system_accent1_500
            else android.R.color.system_neutral1_500
        )).hue
        val baseTone = if (blurSupported) preset.panelTone else preset.fallbackTone
        val tone = if (notification) baseTone + preset.cardToneOffset else baseTone
        val chroma = if (blurSupported) preset.panelChroma else preset.fallbackChroma
        val alpha = if (!blurSupported) 1f else if (notification) preset.cardAlpha else preset.panelAlpha
        return ColorUtils.setAlphaComponent(
            Cam.getInt(hue, chroma, tone.coerceIn(0f, 100f)), (alpha * 255).roundToInt(),
        )
    }

    @JvmStatic
    fun liquidGlassNotificationTint(context: Context): Int? {
        if (!LiquidGlass.isDualToneShade(context)) return null
        val alpha = (LiquidGlassTune.f("dual_tone", 0.12f).coerceIn(0f, 1f) * 255).roundToInt()
        return ColorUtils.setAlphaComponent(context.getColor(R.color.notification_scrim_base), alpha)
    }

    @JvmField
    val CUSTOM_COLOR_KEYS = arrayOf(
        Settings.System.CUSTOM_SHADE_COLOR_ENABLED,
        Settings.System.CUSTOM_SHADE_COLOR,
        Settings.System.CUSTOM_NOTIF_SCRIM_COLOR_ENABLED,
        Settings.System.CUSTOM_NOTIF_SCRIM_COLOR,
    )

    /**
     * Calculate notification shade panel color.
     *
     * @param context Context to resolve colors.
     * @param blurSupported Whether blur is enabled (can be off due to battery saver)
     * @param withScrim Whether to composite a scrim when blur is enabled (used by legacy shade).
     * @return color for the shade panel.
     */
    @JvmStatic
    fun shadePanel(
        context: Context,
        blurSupported: Boolean,
        withScrim: Boolean,
        allowCustomColor: Boolean = true,
    ): Int {
        if (!allowCustomColor || getCustomShadeColor(context) == null) {
            shadePreset(context)?.let { preset ->
                val panel = presetColor(context, preset, blurSupported, notification = false)
                return if (withScrim && blurSupported && preset.useAccent) {
                    ColorUtils.compositeColors(panel, shadePanelScrimBehind(context))
                } else panel
            }
        }
        return if (blurSupported) {
            if (withScrim) {
                ColorUtils.compositeColors(
                    shadePanelStandard(context, allowCustomColor),
                    shadePanelScrimBehind(context),
                )
            } else {
                shadePanelStandard(context, allowCustomColor)
            }
        } else {
            shadePanelFallback(context, allowCustomColor)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun notificationScrim(
        context: Context,
        blurSupported: Boolean,
        allowCustomColor: Boolean = true,
    ): Int {
        if (!allowCustomColor || getCustomNotifScrimColor(context) == null) {
            if (blurSupported) liquidGlassNotificationTint(context)?.let { return it }
            shadePreset(context)?.let { return presetColor(context, it, blurSupported, notification = true) }
        }
        return if (blurSupported) {
            notificationScrimStandard(context, allowCustomColor)
        } else {
            notificationScrimFallback(context, allowCustomColor)
        }
    }

    @JvmStatic
    fun shadePanelScrimBehind(context: Context): Int {
        return context.resources.getColor(
            com.android.internal.R.color.shade_panel_scrim,
            context.theme,
        )
    }

    @JvmStatic
    private fun shadePanelStandard(context: Context, allowCustomColor: Boolean = true): Int {
        val customColor = if (allowCustomColor) getCustomShadeColor(context) else null
        if (customColor != null) {
            return customColor
        }
        val layerAbove =
            context.resources.getColor(com.android.internal.R.color.shade_panel_fg, context.theme)
        val layerBelow =
            context.resources.getColor(com.android.internal.R.color.shade_panel_bg, context.theme)
        return ColorUtils.compositeColors(layerAbove, layerBelow)
    }

    @JvmStatic
    private fun shadePanelFallback(context: Context, allowCustomColor: Boolean = true): Int {
        val customColor = if (allowCustomColor) getCustomShadeColor(context) else null
        if (customColor != null) {
            return customColor
        }
        return ColorUtils.blendARGB(
            context.getColor(R.color.nt_scrim_behind_1),
            context.getColor(R.color.nt_scrim_behind_2),
            0.5f
        )
    }

    @JvmStatic
    private fun notificationScrimStandard(context: Context, allowCustomColor: Boolean = true): Int {
        val customColor = if (allowCustomColor) getCustomNotifScrimColor(context) else null
        if (customColor != null) {
            return ColorUtils.setAlphaComponent(customColor, (0.5f * 255).toInt())
        }
        return ColorUtils.setAlphaComponent(
            context.getColor(R.color.notification_scrim_base),
            (0.5f * 255).toInt(),
        )
    }

    @JvmStatic
    private fun notificationScrimFallback(context: Context, allowCustomColor: Boolean = true): Int {
        val customColor = if (allowCustomColor) getCustomNotifScrimColor(context) else null
        if (customColor != null) {
            return ColorUtils.setAlphaComponent(customColor, (0.2f * 255).toInt())
        }
        return ColorUtils.blendARGB(
            context.getColor(R.color.nt_notification_behind_1),
            context.getColor(R.color.nt_notification_behind_2),
            0.2f
        )
    }

    @JvmStatic
    private fun getCustomShadeColor(context: Context): Int? {
        val enabled = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.CUSTOM_SHADE_COLOR_ENABLED,
            0,
            UserHandle.USER_CURRENT,
        ) == 1
        if (!enabled) return null

        val color = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.CUSTOM_SHADE_COLOR,
            Int.MIN_VALUE,
            UserHandle.USER_CURRENT,
        )
        return if (color == Int.MIN_VALUE) null else color
    }

    @JvmStatic
    private fun getCustomNotifScrimColor(context: Context): Int? {
        val enabled = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.CUSTOM_NOTIF_SCRIM_COLOR_ENABLED,
            0,
            UserHandle.USER_CURRENT,
        ) == 1
        if (!enabled) return null

        val color = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.CUSTOM_NOTIF_SCRIM_COLOR,
            Int.MIN_VALUE,
            UserHandle.USER_CURRENT,
        )
        return if (color == Int.MIN_VALUE) null else color
    }
}
