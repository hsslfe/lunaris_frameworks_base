/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import com.android.internal.util.lunaris.LunarisBarSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LunarisBarContentSettingsTest {
    @Test
    fun media_usesDayAndNightOverrides() {
        val source =
            LunarisBarSettings.parse(
                """{"media_palette":4,"media_palette_day":5,"media_palette_night":6,
                "media_background_color_day":123,"media_background_color_night":456,
                "media_haptics":false}"""
            )

        val day = resolve(source, media = true)
        val night = resolve(source, media = true, dark = true)

        assertEquals(5, day.integer("media_palette", 0, 0, 20))
        assertEquals(6, night.integer("media_palette", 0, 0, 20))
        assertEquals(123, day.integer("media_background_color", 0, Int.MIN_VALUE, Int.MAX_VALUE))
        assertEquals(456, night.integer("media_background_color", 0, Int.MIN_VALUE, Int.MAX_VALUE))
        assertFalse(day.isHapticsEnabled)
    }

    @Test
    fun feature_rightShapeInheritsResolvedMiniShape() {
        val source =
            LunarisBarSettings.parse("""{"mini_shape":2,"feature_flashlight_mini_shape":7}""")

        val result = resolve(source, feature = "feature_flashlight")

        assertEquals(6, result.integer("mini_shape", 0, 0, 31))
        assertEquals(6, result.integer("right_shape", 0, 0, 31))
    }

    @Test
    fun feature_usesInheritedMiniColorsAndPaletteSentinel() {
        val result = resolve(LunarisBarSettings(), feature = "feature_flashlight")

        assertEquals(-1, result.integer("left_palette", 0, -1, 20))
        assertEquals(-1, result.integer("right_palette", 0, -1, 20))
        assertEquals(30, result.integer("left_background", 0, Int.MIN_VALUE, Int.MAX_VALUE))
        assertEquals(40, result.integer("right_foreground", 0, Int.MIN_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun card_keepsShapeAndHapticOverrides() {
        val card =
            LunarisBarSettings.Card.create("clock", "Clock")
                .with("icon_shape", 8)
                .with("toggle_shape", 9)
                .with("card_haptics", false)

        val result = resolve(LunarisBarSettings(), card = card)

        assertEquals(8, result.integer("mini_shape", 0, 0, 31))
        assertEquals(8, result.integer("card_icon_shape", 0, 0, 31))
        assertEquals(9, result.integer("card_button_shape", 0, 0, 31))
        assertFalse(result.isHapticsEnabled)
    }

    @Test
    fun resolvingFeature_preservesSourceAndAllMotionSettings() {
        val source =
            LunarisBarSettings.parse(
                """{"animation_speed":175,"animation_style":2,"animation_bounce":2,
                "reduce_motion":true,"mini_shape":3,"companion":true}"""
            )
        val original = source.toString()

        val result = resolve(source, feature = "feature_flashlight")

        assertEquals(original, source.toString())
        assertEquals(source.animationSpeed, result.animationSpeed, 0f)
        assertEquals(source.animationStyle, result.animationStyle)
        assertEquals(source.bounce, result.bounce)
        assertTrue(result.isReducedMotion)
        assertTrue(result.isCompanionEnabled)
    }

    private fun resolve(
        settings: LunarisBarSettings,
        media: Boolean = false,
        feature: String? = null,
        card: LunarisBarSettings.Card? = null,
        dark: Boolean = false,
    ) =
        lunarisBarContentSettings(
            settings,
            media,
            feature,
            card,
            dark,
            surface = 10,
            content = 20,
            miniSurface = 30,
            miniContent = 40,
        )
}
