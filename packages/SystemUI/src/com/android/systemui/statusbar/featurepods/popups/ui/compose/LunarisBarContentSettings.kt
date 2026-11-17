/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import com.android.internal.util.lunaris.LunarisBarSettings

/** Resolves the existing chip overrides without copying the full configuration for every key. */
internal fun lunarisBarContentSettings(
    settings: LunarisBarSettings,
    media: Boolean,
    feature: String?,
    card: LunarisBarSettings.Card?,
    dark: Boolean,
    surface: Int,
    content: Int,
    miniSurface: Int,
    miniContent: Int,
): LunarisBarSettings {
    val suffix = if (dark) "night" else "day"
    val overrides = linkedMapOf<String, Any>()

    fun integer(key: String, fallback: Int, minimum: Int, maximum: Int): Int =
        (overrides[key] as? Int)?.coerceIn(minimum, maximum)
            ?: settings.integer(key, fallback, minimum, maximum)

    fun hapticsEnabled(): Boolean = overrides["haptics"] as? Boolean ?: settings.isHapticsEnabled

    if (media) {
        for (key in
            listOf("media_palette", "media_mini_left_palette", "media_mini_right_palette")) {
            overrides[key] =
                integer(
                    "${key}_$suffix",
                    integer(key, if (key == "media_palette") 4 else 0, 0, 20),
                    0,
                    20,
                )
        }
        for (key in
            listOf(
                "media_background_color",
                "media_foreground_color",
                "media_accent_color",
                "media_mini_left_background",
                "media_mini_left_foreground",
                "media_mini_right_background",
                "media_mini_right_foreground",
            )) {
            val fallback =
                if (key.endsWith("background") || key == "media_background_color") surface
                else content
            overrides[key] =
                integer(
                    "${key}_$suffix",
                    integer(key, fallback, Int.MIN_VALUE, Int.MAX_VALUE),
                    Int.MIN_VALUE,
                    Int.MAX_VALUE,
                )
        }
    }
    if (feature != null) {
        for (key in
            listOf(
                "mini_shape",
                "right_shape",
                "card_icon_shape",
                "card_button_shape",
                "left_content",
                "right_content",
                "left_palette",
                "right_palette",
                "progress_palette",
                "track_palette",
            )) {
            val fallback =
                when (key) {
                    "mini_shape",
                    "right_shape" -> integer("mini_shape", 2, 0, 31)
                    "card_icon_shape" -> integer(key, 2, 0, 31)
                    "card_button_shape" -> integer(key, 20, 0, 31)
                    "left_palette",
                    "right_palette" -> -1
                    "progress_palette" -> 5
                    "track_palette" -> 2
                    else -> 0
                }
            overrides[key] =
                if (key in listOf("left_palette", "right_palette"))
                    integer(
                        "${feature}_${key}_$suffix",
                        integer("${feature}_$key", 0, 0, 21),
                        0,
                        21,
                    ) - 1
                else if (
                    key in
                        listOf("mini_shape", "right_shape", "card_icon_shape", "card_button_shape")
                )
                    integer("${feature}_$key", 0, 0, 32).let { if (it == 0) fallback else it - 1 }
                else if (key.endsWith("palette"))
                    integer(
                        "${feature}_${key}_$suffix",
                        integer("${feature}_$key", fallback, 0, 20),
                        0,
                        20,
                    )
                else integer("${feature}_$key", fallback, 0, 31)
        }
        for (part in listOf("left", "right", "progress", "track")) {
            for (role in listOf("background", "foreground")) {
                val key = "${part}_$role"
                val fallback = if (role == "background") miniSurface else miniContent
                overrides[key] =
                    integer("${feature}_${key}_$suffix", fallback, Int.MIN_VALUE, Int.MAX_VALUE)
            }
        }
        overrides["haptics"] = hapticsEnabled() && settings.enabled("${feature}_haptics", true)
    }
    if (card != null) {
        overrides["card_icon_shape"] =
            card.integer("icon_shape", integer("card_icon_shape", 2, 0, 31), 0, 31)
        overrides["card_button_shape"] =
            card.integer("toggle_shape", integer("card_button_shape", 20, 0, 31), 0, 31)
        overrides["mini_shape"] = card.integer("icon_shape", integer("mini_shape", 2, 0, 31), 0, 31)
        overrides["haptics"] = hapticsEnabled() && card.enabled("card_haptics", true)
    }
    if (media) {
        overrides["haptics"] = hapticsEnabled() && settings.enabled("media_haptics", true)
    }
    return settings.withValues(overrides)
}
