/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.axdynamicbar.domain

import android.content.ContentResolver
import android.provider.Settings
import com.android.systemui.islandevents.model.IslandEvent

/**
 * Independent lock-screen Now Bar mode. The classic Lunaris music pill remains unchanged when
 * [enabled] is false. When true, all seven looks use these same adjustable appearance values.
 * Expanded Lunaris Bar settings are intentionally never touched.
 */
data class AxLockscreenNowBarSettings(
    val enabled: Boolean = false,
    val look: Int = 0,
    val material: Int = 1,
    val color: Int = 1,
    val opacity: Int = 100,
    val outline: Int = 1,
    val shadow: Int = 0,
    val more: Int = 0,
    val height: Int = 58,
    val width: Int = 1,
    val corners: Int = 100,
    val raise: Int = 0,
    val art: Int = 0,
    val buttons: Int = 1,
    val text: Int = 1,
    val textSize: Int = 13,
    val wave: Boolean = false,
    val music: Boolean = true,
    val timers: Boolean = true,
    val calls: Boolean = true,
    val hideCall: Boolean = true,
    val live: Boolean = true,
    val scores: Boolean = true,
    val recording: Boolean = true,
    val charging: Boolean = false,
    val flashlight: Boolean = false,
    val buds: Boolean = true,
    val liveApps: Set<String> = emptySet(),
) {
    /** Only the original Now Bar activity categories are shown when this mode is selected. */
    fun shows(event: IslandEvent): Boolean = when (event) {
        is IslandEvent.Media, is IslandEvent.NowPlaying -> music
        is IslandEvent.Timer, is IslandEvent.Stopwatch, is IslandEvent.Alarm -> timers
        is IslandEvent.Call -> calls
        is IslandEvent.AudioRecording -> recording
        is IslandEvent.Charging -> charging
        is IslandEvent.Torch -> flashlight
        is IslandEvent.Bluetooth -> buds
        is IslandEvent.Sports -> scores
        is IslandEvent.PromotedOngoing -> live &&
            (liveApps.isEmpty() || event.sbn.packageName in liveApps)
        is IslandEvent.AospChip -> live
        else -> false
    }

    companion object {
        val KEYS = arrayOf(
            "now_bar", "now_bar_look", "now_bar_material", "now_bar_color",
            "now_bar_opacity", "now_bar_outline", "now_bar_shadow", "now_bar_more",
            "now_bar_height", "now_bar_width", "now_bar_corners", "now_bar_raise",
            "now_bar_art", "now_bar_buttons", "now_bar_text", "now_bar_text_size",
            "now_bar_wave", "now_bar_music", "now_bar_timers", "now_bar_calls",
            "now_bar_live_updates", "now_bar_live_apps", "now_bar_scores",
            "now_bar_recording", "now_bar_charging", "now_bar_flashlight", "now_bar_buds",
            "now_bar_hide_call",
        )

        fun read(resolver: ContentResolver, userId: Int): AxLockscreenNowBarSettings {
            fun value(key: String, fallback: Int): Int =
                Settings.System.getIntForUser(resolver, key, fallback, userId)
            fun on(key: String, fallback: Boolean): Boolean =
                value(key, if (fallback) 1 else 0) != 0
            val packages = Settings.System.getStringForUser(
                resolver, "now_bar_live_apps", userId
            )?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            return AxLockscreenNowBarSettings(
                enabled = on("now_bar", false),
                look = value("now_bar_look", 0).coerceIn(0, 6),
                material = value("now_bar_material", 1).coerceIn(0, 1),
                color = value("now_bar_color", 1).coerceIn(0, 2),
                opacity = value("now_bar_opacity", 100).coerceIn(0, 100),
                outline = value("now_bar_outline", 1).coerceIn(0, 2),
                shadow = value("now_bar_shadow", 0).coerceIn(0, 2),
                more = value("now_bar_more", 0).coerceIn(0, 2),
                height = value("now_bar_height", 58).coerceIn(48, 96),
                width = value("now_bar_width", 1).coerceIn(0, 2),
                corners = value("now_bar_corners", 100).coerceIn(20, 100),
                raise = value("now_bar_raise", 0).coerceIn(0, 40),
                art = value("now_bar_art", 0).coerceIn(0, 3),
                buttons = value("now_bar_buttons", 1).coerceIn(0, 2),
                text = value("now_bar_text", 1).coerceIn(0, 2),
                textSize = value("now_bar_text_size", 13).coerceIn(11, 17),
                wave = on("now_bar_wave", false),
                music = on("now_bar_music", true),
                timers = on("now_bar_timers", true),
                calls = on("now_bar_calls", true),
                hideCall = on("now_bar_hide_call", true),
                live = on("now_bar_live_updates", true),
                scores = on("now_bar_scores", true),
                recording = on("now_bar_recording", true),
                charging = on("now_bar_charging", false),
                flashlight = on("now_bar_flashlight", false),
                buds = on("now_bar_buds", true),
                liveApps = packages,
            )
        }
    }
}
