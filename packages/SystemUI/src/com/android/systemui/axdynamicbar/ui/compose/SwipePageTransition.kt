package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/** Swap a single content layer at zero opacity. Keep the surrounding surface in place. */
@Composable
internal fun <T> SwipePageTransition(
    target: T,
    pageKey: Any,
    travel: Dp,
    direction: (T, T) -> Int,
    durationMillis: Int,
    content: @Composable (T, Modifier) -> Unit,
) {
    val next = key(pageKey) { rememberUpdatedState(target) }
    var shown by remember { mutableStateOf(next) }
    val opacity = remember { Animatable(1f) }
    var side by remember { mutableIntStateOf(1) }

    LaunchedEffect(next, durationMillis) {
        if (durationMillis == 0) {
            shown = next
            opacity.snapTo(1f)
            return@LaunchedEffect
        }
        if (shown !== next) {
            val forward = direction(shown.value, next.value)
            // Keep the current offset on interruption. Reverse travel only while invisible.
            if (opacity.value == 1f) side = -forward
            opacity.animateTo(0f, tween(durationMillis / 3, easing = FastOutSlowInEasing))
            shown = next
            side = forward
        }
        opacity.animateTo(1f, tween(durationMillis * 2 / 3, easing = LinearOutSlowInEasing))
    }
    content(shown.value, Modifier.graphicsLayer {
        alpha = opacity.value
        translationX = side * travel.toPx() * (1f - alpha)
    })
}
