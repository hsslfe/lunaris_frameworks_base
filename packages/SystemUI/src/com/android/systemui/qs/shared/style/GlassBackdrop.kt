/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.shared.style

import androidx.compose.runtime.compositionLocalOf

/** The lockscreen wallpaper is in the same window, above the compositor blur. */
val LocalGlassDrawOverArt = compositionLocalOf { false }
