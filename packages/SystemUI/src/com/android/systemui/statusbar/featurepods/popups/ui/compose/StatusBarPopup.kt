/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import android.content.Context
import android.view.ViewTreeObserver
import android.view.WindowManager
import com.android.systemui.axdynamicbar.ui.compose.SwipePageTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.flashlight.ui.compose.FlashlightPopup
import com.android.systemui.statusbar.featurepods.media.ui.compose.LyricsCard
import com.android.systemui.statusbar.featurepods.media.ui.compose.MediaControlPopup
import com.android.systemui.statusbar.featurepods.media.ui.compose.RedesignedMediaPopup
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.SystemEventKind
import com.android.systemui.statusbar.featurepods.popups.ui.model.isNotificationPopup
import kotlinx.coroutines.launch

// Rest-to-rest curves keep both the fade and its associated slide free of abrupt
// starts or stops. The shared pager's compact callers keep their existing curves.
private val ExpandedPageExitEasing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)
private val ExpandedPageEnterEasing = CubicBezierEasing(0.2f, 0f, 0.2f, 1f)

/** Displays an island card that grows from, and returns to, the tapped capsule. */
@Composable
fun StatusBarPopup(
    viewModel: PopupChipModel.Shown,
    isVisible: Boolean,
    chipBoundsInScreen: Rect? = null,
    canPage: Boolean = false,
    pageDirection: Int = 1,
    onPage: (Int) -> Unit = {},
    onHidden: () -> Unit = {},
) {
    val settings = LocalLunarisBarState.current.settings
    val incomingMusic = viewModel.popupContent is PopupContentModel.Media
    // Keep music's extra pre-sizing window, but bring ordinary paging closer
    // to the same relaxed rhythm without premeasuring any other card.
    val baseDuration = if (incomingMusic) 420 else 360
    val duration = if (settings.isReducedMotion || !isVisible || viewModel.popupContent.isNotificationPopup) 0
        else (baseDuration / settings.animationSpeed).toInt().coerceIn(1, 480)
    val fadeOutMillis = duration * 2 / 5
    val fadeInMillis = duration - fadeOutMillis
    SwipePageTransition(
        target = viewModel,
        pageKey = viewModel.chipId,
        travel = { 14.dp },
        direction = { _, _ -> if (pageDirection >= 0) 1 else -1 },
        durationMillis = duration,
        // Keep a short directional glide for music after its bounds are prepared.
        // A smaller distance avoids pushing player controls against the outline.
        enterTravel = { if (it.popupContent is PopupContentModel.Media) 8.dp else 14.dp },
        fadeOutMillis = fadeOutMillis,
        fadeInMillis = fadeInMillis,
        exitEasing = ExpandedPageExitEasing,
        enterEasing = ExpandedPageEnterEasing,
        retargetFromCurrent = true,
        preserveExitDuration = incomingMusic,
    ) { page, motion ->
        LunarisBarContentTheme(page.popupContent, page.chipId) {
            LunarisBarPopupWindow(
                viewModel, page, motion, fadeOutMillis, isVisible, chipBoundsInScreen, canPage, onPage, onHidden,
            )
        }
    }
}

@Composable
private fun LunarisBarPopupWindow(
    viewModel: PopupChipModel.Shown,
    page: PopupChipModel.Shown,
    pageMotion: Modifier,
    fadeOutMillis: Int,
    isVisible: Boolean,
    chipBoundsInScreen: Rect?,
    canPage: Boolean,
    onPage: (Int) -> Unit,
    onHidden: () -> Unit,
) {
    val density = LocalDensity.current
    val settings = LocalLunarisBarState.current.settings
    val notification = viewModel.popupContent.isNotificationPopup
    val currentNotification by rememberUpdatedState(notification)
    val topInsetPx =
        with(density) {
            dimensionResource(R.dimen.status_bar_height).roundToPx() + 8.dp.roundToPx()
        }
    val currentHidePopup by rememberUpdatedState(viewModel.hidePopup)
    // Swipe squish is gesture-driven, not tied to page changes or metadata refreshes.
    // Keeping the release animator in the popup host makes rapid reversals interruptible.
    val swipeSettle = remember { Animatable(0f) }
    val swipeSettings by rememberUpdatedState(settings)
    var swipeCompression by remember { mutableFloatStateOf(0f) }
    var swipeDragging by remember { mutableStateOf(false) }
    var swipeSettleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    Popup(
        popupPositionProvider =
            remember {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ) = IntOffset.Zero
                }
            },
        properties =
            PopupProperties(
                flags =
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        if (notification) {
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        } else {
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        },
                dismissOnBackPress = !notification,
                dismissOnClickOutside = !notification,
            ),
        onDismissRequest = { currentHidePopup() },
    ) {
        val popupView = LocalView.current
        DisposableEffect(popupView, notification) {
            val windowView = popupView.rootView
            val params = windowView.layoutParams as? WindowManager.LayoutParams
            val trustedOverlayFlag = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
            val originalTrustedOverlay = (params?.privateFlags ?: 0) and trustedOverlayFlag
            val manager =
                windowView.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (params != null && windowView.isAttachedToWindow) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                params.setFitInsetsTypes(0)
                params.windowAnimations = 0
                if (notification) {
                    params.privateFlags = params.privateFlags or trustedOverlayFlag
                }
                manager.updateViewLayout(windowView, params)
            }
            val observer = popupView.viewTreeObserver
            val listener =
                ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                    if (!hasFocus && !currentNotification) currentHidePopup()
                }
            observer.addOnWindowFocusChangeListener(listener)
            onDispose {
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
                if (notification && params != null && windowView.isAttachedToWindow) {
                    params.privateFlags =
                        (params.privateFlags and trustedOverlayFlag.inv()) or originalTrustedOverlay
                    manager.updateViewLayout(windowView, params)
                }
            }
        }
        LunarisBarPopupMotion(
            isVisible = isVisible,
            chipBoundsInScreen = chipBoundsInScreen,
            topInsetPx = topInsetPx,
            onDismissRequest = { currentHidePopup() },
            onHidden = onHidden,
            allowOutsideInteraction = notification,
            pageScale = {
                if (isVisible && !notification && !settings.isReducedMotion) {
                    val amount = if (swipeDragging) swipeCompression else swipeSettle.value
                    // The outline and content share this transform. Permit a small
                    // elastic overshoot without clipping the first spring rebound.
                    1f - 0.04f * amount.coerceIn(-0.25f, 1f)
                } else 1f
            },
            fastEnter =
                (viewModel.popupContent as? PopupContentModel.SystemEvent)?.kind ==
                    SystemEventKind.Unlock,
            useRedesignedMediaBackground =
                LocalLunarisBarState.current.alternativeMedia &&
                    (page.popupContent as? PopupContentModel.Media)?.useRedesignedMedia ==
                        true,
            secondaryContent = {
                val media = page.popupContent as? PopupContentModel.Media
                if (
                    media != null &&
                        (!media.model.lyrics.isNullOrBlank() ||
                            !media.model.syncedLyrics.isNullOrBlank())
                ) {
                    LyricsCard(model = media.model, useRedesignedMedia = false)
                }
            },
        ) {
            val pagerScope = rememberCoroutineScope()
            val pageFollow = remember { Animatable(0f) }
            var pageAccumulator by remember { mutableFloatStateOf(0f) }
            var followJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            val currentDismissNotification by rememberUpdatedState(viewModel.dismissNotification)
            val dismissLabel = stringResource(R.string.lunaris_bar_swipe_dismiss)
            val pageThresholdPx = with(density) { 56.dp.toPx() }
            val maxFollowPx = with(density) { 40.dp.toPx() }
            val currentOnPage by rememberUpdatedState(onPage)

            fun settleSwipeSquish(committed: Boolean) {
                if (!swipeDragging) return
                val compression = swipeCompression
                swipeSettleJob?.cancel()
                swipeSettleJob =
                    pagerScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        swipeSettle.snapTo(compression)
                        swipeDragging = false
                        val bounce = swipeSettings.bounce
                        val elastic = committed && bounce != 0
                        val speed = swipeSettings.animationSpeed
                        val damping = when {
                            !elastic -> Spring.DampingRatioNoBouncy
                            bounce == 2 -> 0.46f
                            else -> 0.55f
                        }
                        // A lower natural frequency makes the rebound relaxed rather
                        // than a sharp kick. One continuous spring handles both the
                        // release and settling, with no timed second scale animation.
                        swipeSettle.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = damping,
                                stiffness = (if (elastic) 120f else 180f) * speed * speed,
                                visibilityThreshold = 0.008f,
                            ),
                        )
                    }
            }

            fun resetNotificationDrag() {
                followJob?.cancel()
                followJob =
                    pagerScope.launch {
                        pageFollow.animateTo(
                            0f,
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        )
                    }
            }

            Box(modifier = Modifier.wrapContentSize()) {
                Box(
                    modifier =
                        Modifier.then(
                                if (notification || canPage) {
                                    Modifier.pointerInput(
                                        notification,
                                        canPage,
                                        settings.isReducedMotion,
                                    ) {
                                        var initialCompression = 0f
                                        detectHorizontalDragGestures(
                                            onDragStart = {
                                                pageAccumulator = 0f
                                                if (!notification && !settings.isReducedMotion) {
                                                    // Sample the currently displayed scale before
                                                    // interrupting a pending or running release.
                                                    initialCompression =
                                                        if (swipeDragging) swipeCompression
                                                        else swipeSettle.value
                                                    swipeSettleJob?.cancel()
                                                    swipeCompression = initialCompression
                                                    swipeDragging = true
                                                }
                                            },
                                            onDragEnd = {
                                                val direction =
                                                    when {
                                                        pageAccumulator <= -pageThresholdPx -> 1
                                                        pageAccumulator >= pageThresholdPx -> -1
                                                        else -> 0
                                                    }
                                                pageAccumulator = 0f
                                                settleSwipeSquish(committed = direction != 0)
                                                if (direction != 0) {
                                                    if (notification) {
                                                        currentHidePopup()
                                                        currentDismissNotification?.invoke()
                                                    } else currentOnPage(direction)
                                                } else if (notification) {
                                                    resetNotificationDrag()
                                                }
                                            },
                                            onDragCancel = {
                                                pageAccumulator = 0f
                                                settleSwipeSquish(committed = false)
                                                if (notification) resetNotificationDrag()
                                            },
                                            onHorizontalDrag = { change, dragAmount ->
                                                pageAccumulator += dragAmount
                                                if (swipeDragging) {
                                                    val distance = kotlin.math.abs(pageAccumulator)
                                                    val fraction = distance / (distance + pageThresholdPx)
                                                    swipeCompression = initialCompression +
                                                        (1f - initialCompression) * fraction
                                                }
                                                if (notification) {
                                                    val target =
                                                        (pageAccumulator * 0.5f).coerceIn(
                                                            -maxFollowPx,
                                                            maxFollowPx,
                                                        )
                                                    followJob?.cancel()
                                                    followJob = pagerScope.launch {
                                                        pageFollow.snapTo(target)
                                                    }
                                                }
                                                change.consume()
                                            },
                                        )
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .then(
                                if (notification)
                                    Modifier.semantics {
                                        stateDescription = dismissLabel
                                        dismiss {
                                            currentHidePopup()
                                            currentDismissNotification?.invoke()
                                            true
                                        }
                                    }
                                else Modifier
                            )
                            .graphicsLayer { translationX = pageFollow.value }
                ) {
                    // Only an incoming player needs its final bounds before the page swap.
                    // Regular pages must keep the original visible-page spring resize.
                    val preparingMusic =
                        isVisible && !settings.isReducedMotion && fadeOutMillis > 0 &&
                            viewModel.chipId != page.chipId &&
                            viewModel.popupContent is PopupContentModel.Media
                    SubcomposeLayout(
                        modifier = Modifier.animateContentSize(
                            animationSpec = when {
                                settings.isReducedMotion -> tween(0)
                                preparingMusic -> tween(
                                    // Allow a frame for the measured size to reach the surface
                                    // before the incoming player starts becoming visible.
                                    durationMillis = (fadeOutMillis - 16).coerceAtLeast(1),
                                    easing = FastOutSlowInEasing,
                                )
                                else -> spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = 240f * settings.animationSpeed * settings.animationSpeed,
                                    visibilityThreshold = IntSize(1, 1),
                                )
                            },
                            alignment = Alignment.TopCenter,
                        ),
                    ) { constraints ->
                        // Keep the same slot host across pages so a rapid reversal does not
                        // recreate the visible player. Only precompose a pending music page.
                        fun measurePage(model: PopupChipModel.Shown) =
                            subcompose(model.chipId) {
                                Box(pageMotion, propagateMinConstraints = true) { IslandPopupContent(model) }
                            }.single().measure(constraints)

                        val visible = measurePage(page)
                        val target = if (preparingMusic) measurePage(viewModel) else visible
                        layout(target.width, target.height) {
                            visible.place((target.width - visible.width) / 2, 0)
                        }
                    }
                }
            }
        }
    }
}

/** Renders the per-feature card for [viewModel]; the swipe pager slides between these. */
@Composable
private fun IslandPopupContent(viewModel: PopupChipModel.Shown) {
    when (val popupContent = viewModel.popupContent) {
        is PopupContentModel.Utility ->
            LunarisBarUtilityCard(popupContent.card, viewModel.hidePopup)
        is PopupContentModel.Media -> {
            val model = popupContent.model
            val seekBarStyle = popupContent.seekBarStyle
            if (!LocalLunarisBarState.current.alternativeMedia) {
                LunarisBarMediaPopup(model = model, seekBarStyle = seekBarStyle)
            } else if (popupContent.useRedesignedMedia) {
                RedesignedMediaPopup(model = model, seekBarStyle = seekBarStyle)
            } else {
                MediaControlPopup(model = model, seekBarStyle = seekBarStyle)
            }
        }
        is PopupContentModel.ScreenRecord -> LunarisBarScreenRecordPopup(model = popupContent.model)
        is PopupContentModel.LiveScore -> LunarisBarLiveScorePopup(model = popupContent.model)
        is PopupContentModel.Flashlight -> {
            if (LocalLunarisBarState.current.alternativeFlashlight) {
                FlashlightPopup(
                    model = popupContent.model,
                    hapticsViewModelFactory = popupContent.hapticsViewModelFactory,
                    modifier =
                        if (LocalLunarisBarState.current.settings.isFullWidth)
                            Modifier.fillMaxWidth()
                        else Modifier,
                )
            } else {
                LunarisBarFlashlightPopup(model = popupContent.model)
            }
        }
        is PopupContentModel.Stopwatch -> LunarisBarStopwatchPopup(model = popupContent.model)
        is PopupContentModel.Alarm -> LunarisBarAlarmPopup(model = popupContent.model)
        is PopupContentModel.SystemEvent ->
            SystemEventPopup(
                model = popupContent,
                onDismissNotification = {
                    viewModel.hidePopup()
                    viewModel.dismissNotification?.invoke()
                },
            )
        PopupContentModel.None -> LunarisBarPrivacyPopup(viewModel)
    }
}
