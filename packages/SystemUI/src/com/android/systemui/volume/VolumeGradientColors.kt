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

package com.android.systemui.volume

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import com.android.internal.R

/** The two ends of a volume fill, ordered from minimum to maximum volume. */
data class VolumeGradientColors(val start: Int, val end: Int) {
    companion object {
        @JvmStatic
        fun read(context: Context): VolumeGradientColors? {
            fun setting(key: String) = Settings.System.getIntForUser(
                context.contentResolver, key, 0, UserHandle.USER_CURRENT
            )
            if (setting(Settings.System.VOLUME_SLIDER_GRADIENT) == 0) return null
            val custom = setting(Settings.System.CUSTOM_GRADIENT_COLOR_MODE) == 1
            fun color(key: String, fallback: Int): Int {
                val value = if (custom) setting(key) else 0
                return if (value == 0) context.getColor(fallback) else value
            }
            return VolumeGradientColors(
                color(Settings.System.CUSTOM_GRADIENT_START_COLOR, R.color.materialColorPrimary),
                color(Settings.System.CUSTOM_GRADIENT_END_COLOR, R.color.materialColorSecondary),
            )
        }
    }
}
