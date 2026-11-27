@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axdynamicbar.ui.compose

import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.HapticFeedbackConstants
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import com.android.axion.blur.AxBlurBackgroundRenderer
import com.android.axion.blur.AxBlurColors
import com.android.compose.animation.Expandable
import com.android.compose.animation.rememberExpandableController
import com.android.systemui.animation.Expandable as SystemUiExpandable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlin.math.abs
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.islandevents.model.IslandEvent
import com.android.systemui.islandevents.model.RecordingState
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.axdynamicbar.domain.AxLockscreenNowBarSettings
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipState
import com.android.systemui.axdynamicbar.ui.KeyguardBatteryInfo
import com.android.systemui.res.R
import kotlinx.coroutines.delay
import android.content.Context
import android.graphics.drawable.Drawable
import java.util.Calendar

private val ChipHeight = 36.dp
private val ChipShape = ShapeChip
private val ChipIconSize = ChipHeight - SpaceLg
private val MusicChipHeight = 58.dp
private val MusicChipMinWidth = 130.dp

// Scoped to the collapsed pill only. Expanded Lunaris content keeps its own text behavior.
private val LocalNowBarMarqueeEnabled = compositionLocalOf { true }
// Null preserves the original Dynamic Bar typography and its compact text cap.
private val LocalNowBarTextMaxWidth = compositionLocalOf<Dp?> { null }
private val LocalNowBarTextFontSp = compositionLocalOf<Float?> { null }
private val MusicActionSize = MusicChipHeight - 16.dp
private val MusicActionIconSize = MusicChipHeight - 26.dp
private val MusicPlayPauseSize = MusicChipHeight - 24.dp
private val MusicPlayPauseIconSize = MusicChipHeight - 34.dp
private val ActionSize = SpacePanel
private val ActionIconSize = SizeBadge
private val BatteryIconSize = ChipHeight - SpaceXxl
private val CountBadgeHeight = ChipHeight / 2

private class NowBarMusicPillBlurHost(context: Context) : View(context) {
    private val blur = AxBlurBackgroundRenderer(this)
    private val overlayColor = AxBlurColors.surfaceLightTint(context)

    private val bgDrawable: GradientDrawable = GradientDrawable().also { d ->
        d.setColor(0x00000000)
        d.cornerRadius = context.resources.displayMetrics.density * 50f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        blur.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        blur.onDetachedFromWindow()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        blur.onVisibilityAggregated(isVisible)
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        blur.verifyDrawable(who) || super.verifyDrawable(who)

    override fun draw(canvas: Canvas) {
        if (width > 0 && height > 0) {
            bgDrawable.setBounds(0, 0, width, height)
            if (!blur.drawBackgroundWithOverlayColor(canvas, bgDrawable, overlayColor)) {
                bgDrawable.setColor(overlayColor and 0x00FFFFFF or (0xCC shl 24))
                bgDrawable.draw(canvas)
                bgDrawable.setColor(0x00000000)
            }
        }
    }
}

@Composable
private fun rememberChargingParts(batteryString: String): List<String> {
    return remember(batteryString) {
        batteryString.split("\n", limit = 3).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

@Composable
fun IndependentNowBarChip(
    viewModel: AxDynamicBarChipViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.chipState.collectAsStateWithLifecycle()
    val isKeyguardChipVisible by viewModel.isKeyguardChipVisible.collectAsStateWithLifecycle()
    val idleBatteryWakeReady by viewModel.idleBatteryWakeReady.collectAsStateWithLifecycle()
    val hasVisibleKeyguardText by viewModel.hasVisibleKeyguardText.collectAsStateWithLifecycle()
    val isEnabled by viewModel.isEnabled.collectAsStateWithLifecycle()
    val isKeyguardEnabled by viewModel.isKeyguardEnabled.collectAsStateWithLifecycle()
    val musicPillEnabled by viewModel.isKeyguardMusicPillEnabled.collectAsStateWithLifecycle()
    val nowBarStyle by viewModel.nowBarAppearance.collectAsStateWithLifecycle()
    val isDozing by viewModel.interactor.isDozing.collectAsStateWithLifecycle()
    val keyguardBatteryChipMode by viewModel.keyguardBatteryChipMode.collectAsStateWithLifecycle()
    val batteryInfo by viewModel.keyguardBatteryInfo.collectAsStateWithLifecycle()
    val isKeyguardExpanded by viewModel.isKeyguardExpanded.collectAsStateWithLifecycle()
    val isKeyguardColorOsActive by viewModel.isKeyguardColorOsActive.collectAsStateWithLifecycle()
    val isKeyguardColorOsRevealed by viewModel.isKeyguardColorOsRevealed.collectAsStateWithLifecycle()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val batteryString by viewModel.batteryString.collectAsStateWithLifecycle()
    val isKeyguardDynamicBarEnabled = isEnabled && isKeyguardEnabled
    val hasNowBarActivity = state?.allEvents?.any {
        it !is IslandEvent.Charging || batteryInfo.isCharging
    } == true
    val showIdleBatteryOnly = nowBarStyle.enabled && nowBarStyle.charging &&
        nowBarStyle.batteryMode == 1 && idleBatteryWakeReady &&
        !batteryInfo.isCharging && !hasNowBarActivity && !hasVisibleKeyguardText

    val expandableController = rememberExpandableController(color = Color.Transparent, shape = ChipShape)

    Box(modifier = modifier) {

        // If the currently expanded activity is removed, chipState can turn
        // null immediately. Keep the last expanded content through the exit
        // animation rather than collapsing an empty panel down to the pill.
        var lastExpandedState by remember { mutableStateOf<AxDynamicBarChipState?>(null) }
        var collapsedHostReady by remember { mutableStateOf(true) }
        LaunchedEffect(isKeyguardExpanded) {
            if (isKeyguardExpanded) collapsedHostReady = false
        }
        if (isKeyguardChipVisible && isKeyguardExpanded && state != null) {
            lastExpandedState = state
        }
        val expandedVisibleState = remember { MutableTransitionState(false) }
        expandedVisibleState.targetState =
            isKeyguardExpanded && (state != null || lastExpandedState != null)
        LaunchedEffect(isKeyguardExpanded, expandedVisibleState.isIdle, expandedVisibleState.currentState) {
            if (!isKeyguardExpanded && expandedVisibleState.isIdle && !expandedVisibleState.currentState) {
                lastExpandedState = null
                viewModel.keyguardExpansion.notifyCollapseSettled()
                // Wait for the collapsed native anchor before drawing the pill.
                withFrameNanos { }
                if (!viewModel.keyguardExpansion.isExpanded.value) {
                    collapsedHostReady = true
                }
            }
        }
        AnimatedVisibility(
            visibleState = expandedVisibleState,
            // The card owns its spring, fade and slide. Fading the entire
            // full-screen host a second time causes a dim flash and lag.
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
        ) {
            (state ?: lastExpandedState)?.let {
                KeyguardExpandedContent(
                    event = it.event,
                    allEvents = it.allEvents,
                    interactor = viewModel.interactor,
                    onCollapse = { viewModel.keyguardExpansion.collapse() },
                    hapticsViewModelFactory = viewModel.interactor.sliderHapticsViewModelFactory,
                )
            }
        }

        val impactScaleX = remember { Animatable(1f) }
        val impactScaleY = remember { Animatable(1f) }
        val impactOffsetYDp = remember { Animatable(0f) }
        var wasColorOsRevealed by remember { mutableStateOf(false) }

        LaunchedEffect(isKeyguardColorOsRevealed, isKeyguardColorOsActive, isKeyguardChipVisible) {
            val justCollapsed = wasColorOsRevealed && !isKeyguardColorOsRevealed
            wasColorOsRevealed = isKeyguardColorOsRevealed
            if (justCollapsed && isKeyguardColorOsActive && isKeyguardChipVisible) {
                delay(155L)
                coroutineScope {
                    launch {
                        impactScaleX.animateTo(
                            targetValue = 1.09f,
                            animationSpec = tween(durationMillis = 75, easing = FastOutSlowInEasing),
                        )
                        impactScaleX.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(dampingRatio = 0.44f, stiffness = 420f),
                        )
                    }
                    launch {
                        impactScaleY.animateTo(
                            targetValue = 0.87f,
                            animationSpec = tween(durationMillis = 75, easing = FastOutSlowInEasing),
                        )
                        impactScaleY.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(dampingRatio = 0.44f, stiffness = 420f),
                        )
                    }
                    launch {
                        impactOffsetYDp.animateTo(
                            targetValue = 3f,
                            animationSpec = tween(durationMillis = 75, easing = FastOutSlowInEasing),
                        )
                        impactOffsetYDp.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.46f, stiffness = 400f),
                        )
                    }
                }
            } else if (isKeyguardColorOsRevealed || !isKeyguardColorOsActive || !isKeyguardChipVisible) {
                impactScaleX.snapTo(1f)
                impactScaleY.snapTo(1f)
                impactOffsetYDp.snapTo(0f)
            }
        }

        // Remove the pill from composition before its native host
        // changes from wrap-content to full height. AnimatedVisibility with
        // ExitTransition.None alone can retain a frame in the wrong location.
        if (!isKeyguardExpanded && collapsedHostReady && expandedVisibleState.isIdle &&
            !expandedVisibleState.currentState) {
        val swipe = remember(viewModel) { PillSwipeMotion(viewModel) }
        val pillVisibility = remember { MutableTransitionState(false) }
        pillVisibility.targetState = isKeyguardChipVisible && !isKeyguardColorOsRevealed
        AnimatedVisibility(
            visibleState = pillVisibility,
            enter = when {
                showIdleBatteryOnly -> EnterTransition.None
                isKeyguardColorOsActive -> fadeIn(tween(140, delayMillis = 130)) +
                    scaleIn(initialScale = 0.92f, animationSpec = tween(130, delayMillis = 130))
                else -> fadeIn(tween(110)) + scaleIn(initialScale = 0.86f,
                    animationSpec = spring(dampingRatio = 0.69f, stiffness = 700f))
            },
            exit = if (isKeyguardColorOsActive && isKeyguardColorOsRevealed)
                fadeOut(tween(90)) + scaleOut(targetScale = 0.94f, animationSpec = tween(110))
            else ExitTransition.None,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onGloballyPositioned { coords ->
                    if (isKeyguardColorOsActive && !isKeyguardColorOsRevealed && hasNowBarActivity) {
                        val bounds = coords.boundsInWindow()
                        if (bounds.width > 0f && bounds.height > 0f) {
                            viewModel.colorOsController.updatePillAnchorRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = impactScaleX.value
                    scaleY = impactScaleY.value
                    translationY = impactOffsetYDp.value.dp.toPx()
                }
                .pointerInput(viewModel, state?.event?.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        val start = down.position
                        var dragging = false
                        var totalDx = 0f
                        var canceled = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val dx = change.position.x - start.x
                                val dy = change.position.y - start.y
                                if (!change.pressed) {
                                    if (dragging && !canceled && !change.isConsumed) {
                                        change.consume()
                                        swipe.cycle(previous = totalDx > 0f)
                                    }
                                    break
                                }
                                if (change.isConsumed && !dragging) { canceled = true; break }
                                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                                    if (abs(dy) >= abs(dx)) break
                                    dragging = true
                                }
                                if (dragging) {
                                    totalDx = dx
                                    swipe.drag(dx, touchSlop)
                                    change.consume()
                                }
                            }
                        } finally {
                            swipe.release()
                        }
                    }
                }
                .pillSwipeMotion(swipe),
        ) {
            val chipState = state
            if (nowBarStyle.enabled && !hasNowBarActivity) {
                // The compact pill is mutually exclusive with lockscreen
                // indication text. Neither alters Dynamic Bar's battery UI.
                if (showIdleBatteryOnly) {
                    NowBarIdleBatteryChip(info = batteryInfo)
                }
            } else if (chipState != null) {
                val rawEvent = chipState.notificationAlert ?: chipState.event
                val displayEvent: com.android.systemui.islandevents.model.IslandEvent? =
                    when {
                        // Swipe navigation updates pinnedIndex/event in the interactor;
                        // do not force media to the front when another Now Bar event is selected.
                        nowBarStyle.enabled -> if (
                            chipState.event is IslandEvent.Charging && !batteryInfo.isCharging
                        ) chipState.allEvents.firstOrNull { it !is IslandEvent.Charging }
                          else chipState.event
                        musicPillEnabled && !isKeyguardDynamicBarEnabled ->
                            chipState.allEvents.firstOrNull { it is IslandEvent.Media }
                        musicPillEnabled -> rawEvent
                        !isKeyguardDynamicBarEnabled -> null
                        rawEvent is com.android.systemui.islandevents.model.IslandEvent.Media -> {
                            chipState.allEvents.firstOrNull { it !is com.android.systemui.islandevents.model.IslandEvent.Media }
                        }
                        else -> rawEvent
                    }

                if (displayEvent == null) {
                    if (isKeyguardDynamicBarEnabled && !nowBarStyle.enabled) {
                        KeyguardBatteryChip(
                            batteryInfo,
                            keyguardBatteryChipMode,
                            batteryString,
                            modifier,
                        )
                    }
                    return@AnimatedVisibility
                }

                SwipePageTransition(
                    target = displayEvent,
                    pageKey = displayEvent.id,
                    travel = { 14.dp },
                    direction = { from, to -> swipe.direction(from, to, chipState.allEvents) },
                    durationMillis = 245,
                ) { event, pageMotion ->
                    val rawAccent = chipAccentColorFor(event)
                    val accent by animateColorAsState(
                        rawAccent,
                        MaterialTheme.motionScheme.fastEffectsSpec(),
                        label = "kg_accent",
                    )
                    val isDark = isSystemInDarkTheme()
                    val contentColor by animateColorAsState(
                        if (isDark) Color.White else Color.Black,
                        MaterialTheme.motionScheme.fastEffectsSpec(),
                        label = "kg_content",
                    )
                    val rawProgress = chipProgressFor(event)
                    val progressTarget = rawProgress ?: 0f
                    val progressAnim = remember { Animatable(progressTarget) }
                    LaunchedEffect(progressTarget) {
                        if (abs(progressTarget - progressAnim.value) > 0.05f) {
                            progressAnim.animateTo(progressTarget, tween(300, easing = FastOutSlowInEasing))
                        } else {
                            progressAnim.snapTo(progressTarget)
                        }
                    }
                    val progress = if (rawProgress != null) progressAnim.value else null

                    Expandable(
                        controller = expandableController,
                        onClick = null,
                        defaultMinSize = false,
                    ) { expandable ->
                        val keyguardEventCount = remember(
                            chipState.allEvents, nowBarStyle.enabled, musicPillEnabled,
                        ) {
                            if (nowBarStyle.enabled || musicPillEnabled)
                                chipState.eventCount
                            else chipState.allEvents.count { it !is IslandEvent.Media }
                        }
                        CompositionLocalProvider(
                            LocalNowBarMarqueeEnabled provides
                                (!nowBarStyle.enabled || nowBarStyle.scrollText),
                            LocalNowBarTextMaxWidth provides
                                if (nowBarStyle.enabled)
                                    (nowBarStyle.width - 56).coerceAtLeast(40).dp
                                else null,
                            LocalNowBarTextFontSp provides
                                if (nowBarStyle.enabled)
                                    (nowBarStyle.height * 0.26f).coerceIn(
                                        11f, nowBarStyle.textSize.toFloat().coerceAtLeast(11f)
                                    ) + when (event) {
                                        is IslandEvent.Media,
                                        is IslandEvent.NowPlaying,
                                        is IslandEvent.Charging -> 0f
                                        else -> 1f
                                    }
                                else null,
                        ) {
                            KeyguardChipBody(
                                event = event,
                                accent = accent,
                                contentColor = contentColor,
                                progress = progress,
                                eventCount = keyguardEventCount,
                                selectedEventIndex = chipState.pinnedIndex,
                                viewModel = viewModel,
                                nowBarStyle = nowBarStyle.takeIf { it.enabled },
                                isDozing = isDozing,
                                batteryString = batteryString,
                                aospChipExpandable = expandable,
                                pageMotion = pageMotion,
                            )
                        }
                    }
                }
            } else if (isKeyguardDynamicBarEnabled && !nowBarStyle.enabled) {
                KeyguardBatteryChip(
                    batteryInfo,
                    keyguardBatteryChipMode,
                    batteryString,
                    modifier,
                )
            }
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyguardChipBody(
    event: IslandEvent,
    accent: Color,
    contentColor: Color,
    progress: Float?,
    eventCount: Int,
    selectedEventIndex: Int,
    viewModel: AxDynamicBarChipViewModel,
    nowBarStyle: AxLockscreenNowBarSettings? = null,
    isDozing: Boolean = false,
    batteryString: String = "",
    aospChipExpandable: SystemUiExpandable,
    pageMotion: Modifier,
) {
    val context = LocalContext.current
    val popupAnchor = LocalView.current
    val motionScheme = MaterialTheme.motionScheme
    val marqueeEnabled = LocalNowBarMarqueeEnabled.current
    val pressInteractionSource = remember { MutableInteractionSource() }
    val isPillPressed by pressInteractionSource.collectIsPressedAsState()
    val onPillClick: () -> Unit = {
        if (viewModel.colorOsController.shouldUseColorOsRevealFor(event)) {
            popupAnchor.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            viewModel.colorOsController.toggleReveal()
        } else when (event) {
            is IslandEvent.Notification ->
                viewModel.launchNotificationFromKeyguard(event)
            is IslandEvent.AospChip ->
                viewModel.handleAospChipTap(event, aospChipExpandable)
            is IslandEvent.KeyguardIndication, is IslandEvent.AppSwitch -> Unit
            else -> {
                viewModel.keyguardExpansion.toggle()
            }
        }
    }
    val onPillLongClick: () -> Unit = {
        if (nowBarStyle != null) {
            val menu = android.widget.PopupMenu(context, popupAnchor)
            menu.menu.add("Hide until activity ends").setOnMenuItemClickListener {
                viewModel.interactor.hideNowBarEvent(event)
                true
            }
            menu.show()
        }
    }
    val isMedia = event is IslandEvent.Media
    val parts = rememberChargingParts(batteryString)
    val isMultiLineCharging = event is IslandEvent.Charging && parts.size >= 2
    val dynamicHeight = when {
        isMedia -> MusicChipHeight
        isMultiLineCharging -> 48.dp
        else -> ChipHeight
    }
    val dynamicMinWidth = if (isMedia) MusicChipMinWidth else 48.dp
    val styledHeight = if (nowBarStyle != null) nowBarStyle.height.dp else dynamicHeight
    // Width is a continuous dp setting for Now Bar only. Keep the original
    // dynamic music-pill measurements when Now Bar is not selected.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val availableWidthDp = (screenWidthDp - 24).coerceAtLeast(120)
    val styledWidth = nowBarStyle?.width?.coerceIn(120, availableWidthDp)?.dp
    val chipWidthModifier = if (styledWidth != null) Modifier.width(styledWidth)
        else Modifier.widthIn(min = dynamicMinWidth, max = 180.dp)
    // The real pill width limits text, while fixed icons and optional buttons
    // retain their space. Dynamic Bar continues using its original sizing.
    val nowBarActionSize = if (nowBarStyle != null)
        (nowBarStyle.height * 0.44f).coerceIn(20f, 32f).dp else ActionSize
    val nowBarActionIconSize = if (nowBarStyle != null)
        (nowBarStyle.height * 0.26f).coerceIn(13f, 20f).dp else ActionIconSize
    // The lockscreen Now Bar uses larger system-event icons than Dynamic Bar.
    // Scale with the selected bar height and preserve the existing look elsewhere.
    val nowBarStatusIconSize = if (nowBarStyle != null)
        (nowBarStyle.height * 0.32f).coerceIn(16f, 20f).dp else SizeBadge
    val actionCount = if (nowBarStyle != null && !isMedia)
        actionsFor(event, context).size else 0
    val nowBarTextMaxWidth = if (styledWidth != null) {
        val actionsWidthDp = actionCount *
            (nowBarActionSize.value + SpaceXs.value + SpaceXxs.value)
        (styledWidth.value - 48f - actionsWidthDp).coerceAtLeast(32f).dp
    } else 110.dp
    val styledShape = if (nowBarStyle != null)
        RoundedCornerShape((nowBarStyle.height * nowBarStyle.corners / 200f).dp)
        else ChipShape
    val styledColor = if (isDozing && nowBarStyle != null) Color.Black
        else when (nowBarStyle?.color) {
        0 -> (event as? IslandEvent.Media)?.mediaColor?.takeIf { it != 0 }?.let(::Color) ?: accent
        1 -> Color(context.getColor(android.R.color.system_accent1_700))
        2 -> Color(0xFF18191B)
        else -> accent
    }
    val styledContentColor = when {
        isDozing && nowBarStyle != null -> Color.White
        nowBarStyle == null -> contentColor
        nowBarStyle.color == 2 -> Color.White
        nowBarStyle.material == 0 && styledColor.luminance() > 0.4f -> Color.Black
        nowBarStyle.material == 0 -> Color.White
        else -> contentColor
    }
    val styledStroke = when (nowBarStyle?.outline) {
        0 -> Color.Transparent
        2 -> accent
        null -> chipStrokeColor()
        else -> styledContentColor.copy(alpha = 0.3f)
    }
    val styledStrokeWidth = if (nowBarStyle?.outline == 0) 0.dp
        else if (nowBarStyle != null) 1.dp else 0.3.dp
    val styledShadow = if (isDozing) 0.dp else when (nowBarStyle?.shadow) {
        1 -> 6.dp
        2 -> 12.dp
        else -> 0.dp
    }
    val styledArtworkSize = if (nowBarStyle != null)
        (nowBarStyle.height - 16).coerceIn(30, 78).dp else MusicActionSize
    val mediaButtonSize = if (nowBarStyle != null)
        (nowBarStyle.height - 24).coerceIn(24, 48).dp else MusicPlayPauseSize
    val mediaButtonIconSize = if (nowBarStyle != null)
        (mediaButtonSize.value - 9f).coerceIn(14f, 30f).dp
        else MusicPlayPauseIconSize
    val skipButtonIconSize = if (nowBarStyle != null)
        (nowBarStyle.height * 0.42f).coerceIn(18f, 28f).dp else 24.dp
    val sportsBadgeSize = if (nowBarStyle != null)
        (nowBarStyle.height * 0.40f).coerceIn(18f, 32f).dp else ChipIconSize
    val styledArtShape = when (nowBarStyle?.art) {
        1 -> RoundedCornerShape(10.dp)
        2 -> RoundedCornerShape(3.dp)
        else -> CircleShape
    }

    // The host ConstraintLayout positions the entire collapsed Now Bar for
    // Raise. Offsetting only this child could draw outside the ComposeView and
    // clip its background, artwork and touch target.
    Box(
        modifier = Modifier.pillPressMotion(isPillPressed),
        contentAlignment = Alignment.Center,
    ) {
        if (!isDozing && (nowBarStyle == null || nowBarStyle.material == 1)) {
            Box(modifier = Modifier.matchParentSize()) {
                AndroidView(
                    factory = { ctx -> NowBarMusicPillBlurHost(ctx) },
                    modifier = Modifier.fillMaxSize().clip(styledShape),
                )
            }
        }
        if (nowBarStyle?.more == 2 && eventCount > 1 && !isDozing) {
            Box(
                modifier = Modifier.matchParentSize()
                    .offset(y = (-5).dp)
                    .clip(styledShape)
                    .background(styledColor.copy(alpha = 0.45f)),
            )
        }
        Row(
            modifier = Modifier
                .height(styledHeight)
                .then(chipWidthModifier)
                .shadow(styledShadow, styledShape)
                .border(BorderStroke(styledStrokeWidth, styledStroke), styledShape)
                .clip(styledShape)
                // Decoration remains clipped to the shape. The full classic
                // pill, including blur and border, is animated at its parent.
                .background(if (nowBarStyle == null) Color.Transparent else
                    styledColor.copy(alpha = nowBarStyle.opacity / 100f *
                        (if (nowBarStyle.material == 1) 0.24f else 1f)))
                .animateContentSize(motionScheme.defaultSpatialSpec())
                .then(
                    if (progress != null) {
                        val trackColor = lerp(accent, styledContentColor, 0.2f)
                        val fillColor = lerp(accent, styledContentColor, 0.6f)
                        Modifier.drawWithContent {
                            drawContent()
                            val barH = SizeStrokeWidth.toPx()
                            val y = size.height - barH
                            drawRect(trackColor, Offset(0f, y), Size(size.width, barH))
                            drawRect(fillColor, Offset(0f, y), Size(size.width * progress, barH))
                        }
                    } else Modifier
                )
                .then(
                    if (nowBarStyle != null) Modifier.combinedClickable(
                        interactionSource = pressInteractionSource,
                        indication = null,
                        onClick = onPillClick,
                        onLongClick = onPillLongClick,
                    ) else Modifier.clickable(
                        interactionSource = pressInteractionSource,
                        indication = null,
                        onClick = onPillClick,
                    )
                )
                .padding(start = if (isMedia) SpaceMd else SpaceSm, end = SpaceMd)
                .then(pageMotion),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (nowBarStyle != null) Arrangement.Center else Arrangement.Start,
        ) {
            key(event.id) {
                if (nowBarStyle != null && event is IslandEvent.Charging) {
                    // Use the exact width of Android's native charging text.
                    // Center the whole icon + text group if it fits; otherwise the
                    // bolt remains at the left inset and text gets all remaining space.
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val iconSize = (nowBarStyle.height * 0.38f).coerceIn(16f, 28f).dp
                        val iconGap = SpaceXs
                        val textWidth = (maxWidth - iconSize - iconGap).coerceAtLeast(32.dp)
                        val chargingLines = rememberChargingParts(batteryString)
                        val title = chargingLines.firstOrNull() ?: "${event.level}%"
                        val metrics = chargingLines.drop(1).joinToString(" · ")
                        val textMeasurer = rememberTextMeasurer()
                        val density = LocalDensity.current
                        // PillPrimary reads Compose state. Resolve it in the
                        // composable scope, not inside remember's non-composable
                        // calculation lambda.
                        val titleSize = (nowBarStyle.height * 0.24f).coerceIn(10f, 14f)
                        val detailSize = (nowBarStyle.height * 0.19f).coerceIn(9f, 12f)
                        val baseTextStyle = PillPrimary
                        val titleStyle = baseTextStyle.copy(fontSize = titleSize.sp)
                        val detailStyle = baseTextStyle.copy(fontSize = detailSize.sp)
                        val requiredTextWidthPx = remember(
                            title, metrics, textMeasurer, titleStyle, detailStyle,
                        ) {
                            maxOf(
                                textMeasurer.measure(
                                    text = title,
                                    style = titleStyle,
                                    maxLines = 1,
                                ).size.width,
                                if (metrics.isNotEmpty()) textMeasurer.measure(
                                    text = metrics,
                                    style = detailStyle,
                                    maxLines = 1,
                                ).size.width else 0,
                            )
                        }
                        val requiredTextWidth = with(density) {
                            (requiredTextWidthPx + 4).toDp()
                        }
                        val groupWidth = minOf(maxWidth,
                            iconSize + iconGap + requiredTextWidth.coerceAtLeast(32.dp))
                        Row(
                            modifier = Modifier.width(groupWidth),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            AnimatedChargingBoltIcon(styledContentColor, iconSize)
                            Spacer(Modifier.width(iconGap))
                            NowBarResponsiveChargingText(
                                event = event,
                                batteryString = batteryString,
                                color = styledContentColor,
                                heightDp = nowBarStyle.height,
                                availableWidth = textWidth,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else if (event is IslandEvent.Media) {
                    if (nowBarStyle?.art != 3) {
                    val artworkFrame = rememberKeyguardMediaArtwork(event)
                    AnimatedContent(
                        targetState = artworkFrame,
                        transitionSpec = {
                            (fadeIn(motionScheme.defaultEffectsSpec()) +
                                scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                                (fadeOut(motionScheme.fastEffectsSpec()) +
                                    scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                                SizeTransform(clip = false)
                        },
                        contentKey = { "${it.trackId}|${it.drawable?.hashCode() ?: 0}" },
                        label = "kg_media_icon",
                    ) { frame ->
                        val art = frame.drawable
                        if (art != null) {
                            Image(
                                bitmap = art.toScaledBitmap(styledArtworkSize),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(styledArtworkSize)
                                    .clip(styledArtShape),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            PillEventIcon(event, tint = styledContentColor, animated = false)
                        }
                    }
                    Spacer(Modifier.width(SpaceXs))
                    }
                    AnimatedContent(
                        targetState = event,
                        transitionSpec = {
                            (fadeIn(motionScheme.defaultEffectsSpec()) +
                                scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                                (fadeOut(motionScheme.fastEffectsSpec()) +
                                    scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                                SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                        },
                        contentKey = { "${it.track}|${it.artist}" },
                        label = "kg_media_text",
                        modifier = Modifier.weight(1f, fill = false),
                    ) { ev ->
                        Column(
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.widthIn(max = nowBarTextMaxWidth).fillMaxWidth(),
                        ) {
                            Text(
                                if (nowBarStyle?.text == 0)
                                    listOf(ev.track, ev.artist).filter { it.isNotBlank() }.joinToString(" · ")
                                else ev.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_music) },
                                style = PillPrimary.copy(
                                    fontSize = (LocalNowBarTextFontSp.current
                                        ?: (nowBarStyle?.textSize ?: 13).toFloat()).sp,
                                ),
                                color = styledContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().then(
                                    if (marqueeEnabled) Modifier.basicMarquee(iterations = 1)
                                    else Modifier
                                ),
                            )
                            if (ev.artist.isNotBlank() &&
                                (nowBarStyle == null || nowBarStyle.text == 1)) {
                                Text(
                                    ev.artist,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = ((LocalNowBarTextFontSp.current
                                            ?: (nowBarStyle?.textSize ?: 13).toFloat()) - 1f).sp),
                                    color = styledContentColor.copy(alpha = AlphaSecondary),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(SpaceXs))
                    if (nowBarStyle != null && nowBarStyle.buttons == 0) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            tint = styledContentColor,
                            modifier = Modifier.size(skipButtonIconSize).clickable { viewModel.skipPrev() },
                        )
                    }
                    if (nowBarStyle == null || nowBarStyle.buttons != 2) {
                    Surface(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier.size(mediaButtonSize),
                        shape = CircleShape,
                        color = lerp(accent, styledContentColor, AlphaSubtle).copy(alpha = 0.4f),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(mediaButtonSize)) {
                            Icon(
                                if (event.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = stringResource(
                                    if (event.isPlaying) R.string.ax_dynamic_bar_pause
                                    else R.string.ax_dynamic_bar_play,
                                ),
                                tint = styledContentColor,
                                modifier = Modifier.size(mediaButtonIconSize),
                            )
                        }
                    }
                    }
                    if (nowBarStyle != null && nowBarStyle.buttons == 0) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            tint = styledContentColor,
                            modifier = Modifier.size(skipButtonIconSize).clickable { viewModel.skipNext() },
                        )
                    }
                    if (nowBarStyle?.wave == true && event.isPlaying && !isDozing) {
                        val waveTransition = rememberInfiniteTransition(label = "nowbar_music_wave")
                        val waveAmp by waveTransition.animateFloat(
                            initialValue = 0.4f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
                            label = "nowbar_wave_amplitude",
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            for (i in 0..2) {
                                Spacer(
                                    Modifier.width(3.dp).height(
                                        (8f + (i + 1) * 5f * waveAmp).dp)
                                        .padding(horizontal = 0.5.dp)
                                        .background(styledContentColor, RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }
                } else if (event is IslandEvent.Sports && event.team2Name.isNotEmpty()) {
                    SportsChipTeamBadge(event.team1Name, event.team1Icon, styledContentColor, sportsBadgeSize)
                    Spacer(Modifier.width(SpaceXs))
                    Text(
                        if (event.score1.isNotEmpty()) "${event.score1} - ${event.score2}"
                            else stringResource(R.string.ax_dynamic_bar_sports_vs),
                        style = if (nowBarStyle != null)
                            PillPrimary.copy(fontSize = (LocalNowBarTextFontSp.current ?: 13f).sp)
                            else PillPrimary,
                        color = styledContentColor,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(SpaceXs))
                    SportsChipTeamBadge(event.team2Name, event.team2Icon, styledContentColor, sportsBadgeSize)
                } else {
                    AnimatedContent(
                        targetState = event,
                        transitionSpec = {
                            (fadeIn(motionScheme.defaultEffectsSpec()) +
                                scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                                (fadeOut(motionScheme.fastEffectsSpec()) +
                                    scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                                SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                        },
                        contentKey = { iconKeyFor(it) },
                        label = "kg_chip_icon",
                        modifier = if (nowBarStyle != null) Modifier.offset(y = (-1).dp)
                            else Modifier,
                    ) { ev ->
                        if (nowBarStyle != null) {
                            // Use a consistent icon slot for hotspot, Bluetooth, and
                            // the other non-media activities; keep glyphs optically centered.
                            Box(
                                modifier = Modifier.size(nowBarStatusIconSize + 2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                PillEventIcon(
                                    ev, tint = styledContentColor, animated = false,
                                    staticIconSize = nowBarStatusIconSize,
                                )
                            }
                        } else {
                            PillEventIcon(ev, tint = styledContentColor, animated = false)
                        }
                    }
                    Spacer(Modifier.width(SpaceXs))
                    AnimatedContent(
                        targetState = event,
                        transitionSpec = {
                            (fadeIn(motionScheme.defaultEffectsSpec()) +
                                scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                                (fadeOut(motionScheme.fastEffectsSpec()) +
                                    scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                                SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                        },
                        contentKey = { textKeyFor(it) },
                        label = "kg_chip_text",
                        modifier = Modifier.weight(1f, fill = false).then(
                            if (nowBarStyle != null) Modifier.offset(y = (-1).dp) else Modifier
                        ),
                    ) { ev ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KeyguardPrimaryText(
                                ev, styledContentColor,
                                Modifier.weight(1f, fill = false).widthIn(max = nowBarTextMaxWidth),
                                batteryString,
                            )
                            val secondary = secondaryTextFor(ev)
                                if (secondary != null) {
                                    Text(
                                        " · ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = styledContentColor.copy(alpha = AlphaTertiary),
                                    )
                                    Text(
                                        secondary,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = styledContentColor.copy(alpha = AlphaSecondary),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(
                                            max = if (nowBarStyle != null)
                                                (nowBarTextMaxWidth * 0.45f).coerceAtLeast(32.dp)
                                            else 60.dp,
                                        ).then(
                                            if (marqueeEnabled) Modifier.basicMarquee(iterations = 1)
                                            else Modifier
                                        ),
                                    )
                                }
                            }
                    }
                }

                if (event !is IslandEvent.Media && event !is IslandEvent.Charging) {
                    val actionsCtx = LocalContext.current
                    val actions = actionsFor(event, actionsCtx)
                    if (actions.isNotEmpty()) {
                        Spacer(Modifier.width(SpaceXs))
                        actions.forEach { action ->
                            Spacer(Modifier.width(SpaceXxs))
                            ActionButton(
                                icon = action.icon,
                                color = styledContentColor,
                                bgColor = accent.copy(alpha = 0.5f),
                                onClick = { action.perform(viewModel, event, context) },
                                size = nowBarActionSize,
                                iconSize = nowBarActionIconSize,
                            )
                        }
                    }
                }

                // The original Dynamic Bar retains its compact right-side count badge.
                // Now Bar's dots are overlaid at the bottom, without consuming width.
                if (nowBarStyle == null && eventCount > 1 && event !is IslandEvent.Media) {
                    Spacer(Modifier.width(SpaceXs))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(CountBadgeHeight)
                            .widthIn(min = CountBadgeHeight)
                            .background(lerp(accent, styledContentColor, AlphaDisabled), ShapeChip)
                            .padding(horizontal = SpaceXxs),
                    ) {
                        Text(
                            "$eventCount",
                            style = TsBadge,
                            color = styledContentColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (nowBarStyle?.more == 1 && eventCount > 1 && !isDozing) {
            // Center the activity indicators just above the lower pill outline,
            // inside the collapsed Now Bar. This is not a right-side badge.
            // Keep a sliding window for large activity counts so the active dot
            // remains visible even when the user swipes beyond the first seven.
            val visibleDots = eventCount.coerceAtMost(7)
            val windowStart = (selectedEventIndex - visibleDots / 2)
                .coerceIn(0, eventCount - visibleDots)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(visibleDots) { dot ->
                    val selected = windowStart + dot == selectedEventIndex
                    Box(
                        modifier = Modifier
                            .size(if (selected) 5.dp else 4.dp)
                            .background(
                                styledContentColor.copy(alpha = if (selected) 0.95f else 0.35f),
                                CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

/**
 * Now Bar's independent idle battery display, used ONLY when its own "Always"
 * mode is selected and there are no other activities. Do not apply the
 * configurable Now Bar width/height to this small 36dp battery-only pill.
 * The original Dynamic Bar battery implementation below stays unchanged.
 */
@Composable
private fun NowBarIdleBatteryChip(info: KeyguardBatteryInfo) {
    val accent = if (info.isPowerSave) BatteryPowerSaveColor else BatteryNeutralColor
    val contentColor = chipContentColorOn(accent)
    Box(contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.matchParentSize().clip(ChipShape)) {
            AndroidView(
                factory = { ctx -> NowBarMusicPillBlurHost(ctx) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = Modifier
                .height(ChipHeight)
                .widthIn(min = 72.dp)
                .border(BorderStroke(0.3.dp, chipStrokeColor()), ChipShape)
                .clip(ChipShape)
                .background(Color.Transparent)
                .padding(horizontal = SpaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            AnimatedBatteryFillIcon(info.level, contentColor, BatteryIconSize)
            Spacer(Modifier.width(SpaceXs))
            Text(
                text = "${info.level}%",
                style = PillPrimary,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun KeyguardBatteryChip(
    info: KeyguardBatteryInfo,
    keyguardBatteryChipMode: Int,
    batteryString: String,
    modifier: Modifier,
) {
    if (keyguardBatteryChipMode <= 0) return

    if (keyguardBatteryChipMode == 1 && !info.isCharging) return

    val accent = when {
        info.isCharging -> BatteryChargingColor
        info.isPowerSave -> BatteryPowerSaveColor
        else -> BatteryNeutralColor
    }
    val contentColor = chipContentColorOn(accent)

    val parts = rememberChargingParts(batteryString)
    val isMultiLine = info.isCharging && parts.size >= 2
    val dynamicHeight = if (isMultiLine) 48.dp else ChipHeight

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(ChipShape),
        ) {
            AndroidView(
                factory = { ctx -> NowBarMusicPillBlurHost(ctx) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = modifier
                .height(dynamicHeight)
                .widthIn(min = 48.dp, max = 180.dp)
                .border(BorderStroke(0.3.dp, chipStrokeColor()), ChipShape)
                .clip(ChipShape)
                .background(Color.Transparent)
                .padding(horizontal = SpaceMd)
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            
            if (info.isCharging) {
                AnimatedChargingBoltIcon(contentColor, BatteryIconSize)
            } else {
                AnimatedBatteryFillIcon(info.level, contentColor, BatteryIconSize)
            }
            Spacer(Modifier.width(SpaceXs))
            if (info.isCharging) {
                if (isMultiLine) {
                    Column(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            parts[0],
                            style = PillPrimary,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            parts[1],
                            style = PillPrimary.copy(fontSize = 10.sp),
                            color = contentColor.copy(alpha = AlphaSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        if (parts.isNotEmpty()) parts[0] else batteryString,
                        style = PillPrimary,
                        color = contentColor.copy(alpha = AlphaSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.basicMarquee(),
                    )
                }
                return
            }
            val secondaryLabel = when {
                info.isCharging && info.isWireless -> stringResource(R.string.ax_dynamic_bar_wireless)
                info.isCharging -> stringResource(R.string.ax_dynamic_bar_charging)
                info.isPowerSave -> stringResource(R.string.ax_dynamic_bar_saver)
                else -> null
            }
            val timeRemaining = info.timeRemaining
            val tertiaryColor = contentColor.copy(alpha = AlphaTertiary)
            val secondaryColor = contentColor.copy(alpha = AlphaSecondary)
            val labelSmallFontSize = MaterialTheme.typography.labelSmall.fontSize
            val pillPrimaryFontSize = PillPrimary.fontSize
            val infoLine = remember(info.level, secondaryLabel, timeRemaining, contentColor, labelSmallFontSize, pillPrimaryFontSize) {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = contentColor, fontSize = pillPrimaryFontSize)) {
                        append("${info.level}%")
                    }
                    if (secondaryLabel != null) {
                        withStyle(SpanStyle(color = tertiaryColor, fontSize = labelSmallFontSize)) {
                            append(" · ")
                        }
                        withStyle(SpanStyle(color = secondaryColor, fontSize = labelSmallFontSize)) {
                            append(secondaryLabel)
                        }
                    }
                    if (timeRemaining != null) {
                        withStyle(SpanStyle(color = tertiaryColor, fontSize = labelSmallFontSize)) {
                            append(" · ")
                        }
                        withStyle(SpanStyle(color = secondaryColor, fontSize = labelSmallFontSize)) {
                            append(timeRemaining)
                        }
                    }
                }
            }
            Text(
                infoLine,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.widthIn(max = 130.dp).basicMarquee(iterations = 1),
            )
        }
    }
}

@Composable
private fun AnimatedChargingBoltIcon(color: Color, iconSize: Dp = BatteryIconSize) {
    val transition = rememberInfiniteTransition(label = "kg_bolt")
    val glow by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "kg_bolt_glow",
    )
    val boltPath = remember { Path() }
    Canvas(modifier = Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        boltPath.rewind()
        boltPath.moveTo(w * 0.55f, h * 0.05f)
        boltPath.lineTo(w * 0.25f, h * 0.50f)
        boltPath.lineTo(w * 0.45f, h * 0.50f)
        boltPath.lineTo(w * 0.40f, h * 0.95f)
        boltPath.lineTo(w * 0.75f, h * 0.42f)
        boltPath.lineTo(w * 0.55f, h * 0.42f)
        boltPath.close()
        drawPath(boltPath, color.copy(alpha = glow))
    }
}

@Composable
private fun AnimatedBatteryFillIcon(level: Int, color: Color, iconSize: Dp = BatteryIconSize) {
    val fillFraction by animateFloatAsState(
        targetValue = (level / 100f).coerceIn(0f, 1f),
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "kg_battery_fill",
    )
    Canvas(modifier = Modifier.size(iconSize * 1.6f, iconSize)) {
        val w = size.width
        val h = size.height
        val cornerRadius = h * 0.18f

        val bodyW = w * 0.88f
        val bodyH = h * 0.62f
        val bodyX = 0f
        val bodyY = (h - bodyH) / 2f

        val nubW = w * 0.07f
        val nubH = bodyH * 0.38f
        val nubX = bodyW
        val nubY = bodyY + (bodyH - nubH) / 2f
        val nubRadius = nubW * 0.45f

        val inset = h * 0.05f
        val innerRadius = (cornerRadius - inset).coerceAtLeast(2f)

        drawRoundRect(
            color = color.copy(alpha = 0.75f),
            topLeft = Offset(nubX, nubY),
            size = Size(nubW, nubH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(nubRadius, nubRadius),
        )

        drawRoundRect(
            color = color.copy(alpha = 0.35f),
            topLeft = Offset(bodyX, bodyY),
            size = Size(bodyW, bodyH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.09f),
        )

        drawRoundRect(
            color = color.copy(alpha = 0.12f),
            topLeft = Offset(bodyX + inset, bodyY + inset),
            size = Size(bodyW - inset * 2, bodyH - inset * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(innerRadius, innerRadius),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.03f),
        )

        if (fillFraction > 0f) {
            val fillPad = h * 0.10f
            val fillAreaX = bodyX + fillPad
            val fillAreaY = bodyY + fillPad
            val fillAreaW = bodyW - fillPad * 2
            val fillAreaH = bodyH - fillPad * 2
            val fillRadius = (cornerRadius - fillPad).coerceAtLeast(2f)

            val clipPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = fillAreaX,
                        top = fillAreaY,
                        right = fillAreaX + fillAreaW,
                        bottom = fillAreaY + fillAreaH,
                        radiusX = fillRadius,
                        radiusY = fillRadius,
                    )
                )
            }

            withTransform({ clipPath(clipPath) }) {
                drawRect(
                    color = color,
                    topLeft = Offset(fillAreaX, fillAreaY),
                    size = Size(fillAreaW * fillFraction, fillAreaH),
                )
            }
        }
    }
}

/**
 * The collapsed Now Bar's charging activity uses the entire measured text area.
 * Its width comes from the live Now Bar slider rather than Dynamic Bar's 110dp
 * text cap, and its typography/line count also responds to the chosen height.
 * No change is made to the original Dynamic Bar battery fallback or expanded UI.
 */
@Composable
private fun NowBarResponsiveChargingText(
    event: IslandEvent.Charging,
    batteryString: String,
    color: Color,
    heightDp: Int,
    availableWidth: Dp,
    modifier: Modifier = Modifier,
) {
    // Display the same unmodified charging indication as the normal lockscreen:
    // first line: battery percentage and Android's charging-speed phrase;
    // second line (if enabled): real current, wattage, voltage, temperature.
    val lines = rememberChargingParts(batteryString)
    val mainText = lines.firstOrNull() ?: "${event.level}%"
    val details = lines.drop(1).joinToString(" · ")
    val marqueeEnabled = LocalNowBarMarqueeEnabled.current
    val freeWidth = availableWidth.value.coerceAtLeast(24f)

    // Change appearance only, never the underlying text or measurements.
    val titleHeightSp = (heightDp * 0.24f).coerceIn(10f, 14f)
    val titleWidthSp = freeWidth / (mainText.length.coerceAtLeast(1) * 0.53f)
    val titleFontSp = minOf(titleHeightSp, titleWidthSp.coerceAtLeast(9f))
    val detailHeightSp = (heightDp * 0.19f).coerceIn(9f, 12f)
    val detailWidthSp = freeWidth / (details.length.coerceAtLeast(1) * 0.52f)
    val detailFontSp = minOf(detailHeightSp, detailWidthSp.coerceAtLeast(9f))

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = mainText,
            style = PillPrimary.copy(fontSize = titleFontSp.sp),
            color = color,
            maxLines = 1,
            overflow = if (marqueeEnabled) TextOverflow.Clip else TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth().then(
                if (marqueeEnabled) Modifier.basicMarquee(iterations = 1) else Modifier
            ),
        )
        if (details.isNotEmpty()) {
            Text(
                text = details,
                style = PillPrimary.copy(fontSize = detailFontSp.sp),
                color = color.copy(alpha = AlphaSecondary),
                maxLines = 1,
                overflow = if (marqueeEnabled) TextOverflow.Clip else TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().then(
                    if (marqueeEnabled) Modifier.basicMarquee(iterations = 1) else Modifier
                ),
            )
        }
    }
}

@Composable
private fun KeyguardPrimaryText(event: IslandEvent, color: Color, modifier: Modifier, batteryString: String = "") {
    when (event) {
        is IslandEvent.AudioRecording -> when (event.state) {
            RecordingState.RECORDING -> ElapsedTimeText(
                event.startTimeMs, color, modifier, event.pausedDurationMs,
            )
            RecordingState.PAUSED -> MarqueeText(stringResource(R.string.ax_dynamic_bar_paused), color, modifier)
            RecordingState.SAVED -> MarqueeText(stringResource(R.string.ax_dynamic_bar_saved), color, modifier)
        }
        is IslandEvent.Media -> MarqueeText(event.track, color, modifier)
        is IslandEvent.Timer -> {
            if (event.endTimeMs > 0L) CountdownText(event, color, modifier)
            else MarqueeText(event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_timer) }, color, modifier)
        }
        is IslandEvent.Stopwatch -> StopwatchTimeText(event, color, modifier)
        is IslandEvent.Notification -> MarqueeText(
            if (event.isRedacted) event.appName else event.title ?: event.appName,
            color, modifier,
        )
        is IslandEvent.Charging -> {
            val parts = rememberChargingParts(batteryString)
            if (parts.size >= 2) {
                Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
                    Text(
                        parts[0],
                        style = PillPrimary,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        parts[1],
                        style = PillPrimary.copy(fontSize = 10.sp),
                        color = color.copy(alpha = AlphaSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                MarqueeText(if (parts.isNotEmpty()) parts[0] else "${event.level}%", color, modifier)
            }
        }
        is IslandEvent.Bluetooth -> MarqueeText(
            if (LocalNowBarTextMaxWidth.current != null) event.deviceName
            else event.deviceName.take(12),
            color, modifier,
        )
        is IslandEvent.Hotspot -> {
            val hotspotLabel = stringResource(R.string.ax_dynamic_bar_hotspot)
            MarqueeText(
                "$hotspotLabel · ${event.numDevices}",
                color, modifier,
            )
        }
        is IslandEvent.Alarm -> MarqueeText(event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_alarm) }, color, modifier)
        is IslandEvent.Call -> CallTimerText(event, modifier, color)
        is IslandEvent.Torch -> MarqueeText(
            if (event.supportsLevel) "${(event.level.toFloat() / event.maxLevel * 100).toInt()}%"
            else stringResource(R.string.ax_dynamic_bar_flashlight),
            color, modifier,
        )
        is IslandEvent.RingerMode -> MarqueeText(event.label, color, modifier)
        is IslandEvent.Vpn -> MarqueeText(stringResource(R.string.ax_dynamic_bar_vpn_active), color, modifier)
        is IslandEvent.Clipboard -> MarqueeText(
            event.preview.ifEmpty { stringResource(R.string.ax_dynamic_bar_copied) }, color, modifier,
        )
        is IslandEvent.BiometricUnlock -> MarqueeText(stringResource(R.string.ax_dynamic_bar_unlocked), color, modifier)
        is IslandEvent.AppSwitch -> MarqueeText(stringResource(R.string.ax_dynamic_bar_recents), color, modifier)
        is IslandEvent.PromotedOngoing -> MarqueeText(
            event.shortText.ifEmpty { event.title.ifEmpty { event.appName } }, color, modifier,
        )
        is IslandEvent.Sports -> MarqueeText(
            "${event.score1}-${event.score2}", color, modifier,
        )
        is IslandEvent.NowPlaying -> MarqueeText(
            "${event.songTitle} · ${event.artist}".trimEnd(' ', '·', ' '), color, modifier,
        )
        is IslandEvent.KeyguardIndication -> MarqueeText(event.text, color, modifier)
        is IslandEvent.AospChip -> AospKeyguardChipText(event, color, modifier)
    }
}

@Composable
private fun AospKeyguardChipText(
    event: IslandEvent.AospChip,
    color: Color,
    modifier: Modifier,
) {
    when (val content = event.active.content) {
        is OngoingActivityChipModel.Content.Text -> {
            if (content.text.isNotBlank()) MarqueeText(content.text, color, modifier)
        }
        is OngoingActivityChipModel.Content.Timer -> AospKeyguardTimerText(content, color, modifier)
        is OngoingActivityChipModel.Content.ShortTimeDelta -> AospKeyguardDeltaText(content, color, modifier)
        is OngoingActivityChipModel.Content.Countdown -> Text(
            formatCountdownLong(content.secondsUntilStarted * 1000L),
            color = color,
            style = adaptiveNowBarMonoStyle(),
            modifier = modifier,
        )
        is OngoingActivityChipModel.Content.IconOnly -> Unit
    }
}

@Composable
private fun AospKeyguardTimerText(
    content: OngoingActivityChipModel.Content.Timer,
    color: Color,
    modifier: Modifier,
) {
    var elapsedMs by remember(content.startTimeMs, content.isEventInFuture, content.timeSource) {
        mutableLongStateOf(aospTimerElapsedMs(content))
    }
    LaunchedEffect(content.startTimeMs, content.isEventInFuture, content.timeSource) {
        while (true) {
            elapsedMs = aospTimerElapsedMs(content)
            delay(1000L - abs(content.startTimeMs - content.timeSource.getCurrentTime()) % 1000L)
        }
    }
    Text(formatCountdownLong(elapsedMs), color = color, style = adaptiveNowBarMonoStyle(), modifier = modifier)
}

private fun aospTimerElapsedMs(content: OngoingActivityChipModel.Content.Timer): Long {
    val now = content.timeSource.getCurrentTime()
    return if (content.isEventInFuture) {
        (content.startTimeMs - now).coerceAtLeast(0L)
    } else {
        (now - content.startTimeMs).coerceAtLeast(0L)
    }
}

@Composable
private fun AospKeyguardDeltaText(
    content: OngoingActivityChipModel.Content.ShortTimeDelta,
    color: Color,
    modifier: Modifier,
) {
    var deltaMs by remember(content.time) {
        mutableLongStateOf(System.currentTimeMillis() - content.time)
    }
    LaunchedEffect(content.time) {
        while (true) {
            deltaMs = System.currentTimeMillis() - content.time
            delay(30_000)
        }
    }
    MarqueeText(aospShortDeltaText(deltaMs), color, modifier)
}

@Composable
private fun aospShortDeltaText(deltaMs: Long): String {
    val mins = abs(deltaMs) / 60_000L
    return when {
        mins < 1L -> stringResource(R.string.ax_dynamic_bar_just_now)
        mins < 60L -> stringResource(R.string.ax_dynamic_bar_mins_ago, mins.toInt())
        mins < 1440L -> stringResource(R.string.ax_dynamic_bar_hours_ago, (mins / 60L).toInt())
        else -> stringResource(R.string.ax_dynamic_bar_days_ago, (mins / 1440L).toInt())
    }
}

@Composable
private fun secondaryTextFor(event: IslandEvent): String? = when (event) {
    is IslandEvent.Media -> event.artist.takeIf { it.isNotBlank() }
    is IslandEvent.AudioRecording -> event.appName.takeIf { it.isNotBlank() }
    is IslandEvent.Timer,
    is IslandEvent.Stopwatch,
    is IslandEvent.Hotspot,
    is IslandEvent.Vpn,
    is IslandEvent.AppSwitch -> null
    is IslandEvent.Charging -> event.timeRemaining
    is IslandEvent.Bluetooth -> if (event.batteryLevel >= 0) "${event.batteryLevel}%" else null
    is IslandEvent.Alarm -> {
        if (event.triggerTimeMs > 0) {
            val cal = Calendar.getInstance().apply { timeInMillis = event.triggerTimeMs }
            val triggerTime = "%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            triggerTime.takeIfDistinctFrom(event.label)
        } else null
    }
    is IslandEvent.Call -> event.callerName
    is IslandEvent.BiometricUnlock -> event.sourceName
    is IslandEvent.Notification ->
        event.text?.takeUnless { event.isRedacted }
            ?.takeIf { it.isNotBlank() }
            ?.takeIfDistinctFrom(event.title, event.appName)
            ?.take(30)
    is IslandEvent.PromotedOngoing ->
        event.text
            .takeIf { it.isNotBlank() }
            ?.takeIfDistinctFrom(event.shortText, event.title, event.appName)
            ?.take(20)
    is IslandEvent.Sports -> "${event.team1Name} ${stringResource(R.string.ax_dynamic_bar_sports_vs)} ${event.team2Name}"
    is IslandEvent.KeyguardIndication -> when (event.indicationType) {
        IslandEvent.KeyguardIndication.IndicationType.BIOMETRIC -> stringResource(R.string.ax_dynamic_bar_biometric)
        IslandEvent.KeyguardIndication.IndicationType.TRUST -> stringResource(R.string.ax_dynamic_bar_trust)
        IslandEvent.KeyguardIndication.IndicationType.ALIGNMENT -> stringResource(R.string.ax_dynamic_bar_alignment)
        else -> null
    }
    else -> null
}

private fun String.takeIfDistinctFrom(vararg others: String?): String? {
    val value = trim()
    if (value.isEmpty()) return null
    return value.takeIf { candidate ->
        others.none { other -> candidate.equals(other?.trim(), ignoreCase = true) }
    }
}

@Composable
private fun CallTimerText(event: IslandEvent.Call, modifier: Modifier, overrideColor: Color? = null) {
    val isActive = event.callType == "Phone:active"
    if (isActive && event.callStartTimeMs > 0L) {
        var elapsedMs by remember(event.callStartTimeMs) {
            mutableLongStateOf((System.currentTimeMillis() - event.callStartTimeMs).coerceAtLeast(0L))
        }
        LaunchedEffect(event.callStartTimeMs) {
            while (true) {
                delay(1000)
                elapsedMs = (System.currentTimeMillis() - event.callStartTimeMs).coerceAtLeast(0L)
            }
        }
        val color = overrideColor ?: GreenAccent
        Text(formatElapsedTime(elapsedMs), color = color, style = adaptiveNowBarMonoStyle(), modifier = modifier)
    } else {
        MarqueeText(
            stringResource(if (isActive) R.string.ax_dynamic_bar_call else R.string.ax_dynamic_bar_incoming_call),
            overrideColor ?: if (isActive) GreenAccent else BlueAccent,
            modifier,
        )
    }
}

@Composable
private fun SportsChipTeamBadge(
    name: String,
    icon: Drawable?,
    contentColor: Color,
    iconSize: Dp = ChipIconSize,
) {
    if (icon != null) {
        Image(
            bitmap = icon.toScaledBitmap(iconSize),
            contentDescription = name,
            modifier = Modifier.size(iconSize).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.size(iconSize).clip(CircleShape)
                .background(contentColor.copy(alpha = AlphaIconBg)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(2).uppercase(),
                style = TsBadge,
                color = contentColor,
            )
        }
    }
}

private enum class ActionIcon { PLAY, PAUSE, STOP, SKIP_PREV, SKIP_NEXT }

private data class NowBarChipAction(
    val icon: ActionIcon,
    val perform: (AxDynamicBarChipViewModel, IslandEvent, Context) -> Unit,
)

private fun actionsFor(event: IslandEvent, context: Context): List<NowBarChipAction> = when (event) {
    is IslandEvent.Media -> listOf(
        NowBarChipAction(ActionIcon.SKIP_PREV) { vm, _, _ -> vm.skipPrev() },
        NowBarChipAction(if (event.isPlaying) ActionIcon.PAUSE else ActionIcon.PLAY) { vm, _, _ ->
            vm.togglePlayPause()
        },
        NowBarChipAction(ActionIcon.SKIP_NEXT) { vm, _, _ -> vm.skipNext() },
    )
    is IslandEvent.AudioRecording -> {
        val classified = event.actions.map { it to it.action.classify(context, it.action.actionIntent?.creatorPackage ?: context.packageName) }
        val pauseResume = classified.firstOrNull { (_, k) -> k == NotificationActionType.PAUSE || k == NotificationActionType.RESUME }?.first
        val stop = classified.firstOrNull { (_, k) -> k == NotificationActionType.STOP || k == NotificationActionType.DELETE }?.first
        listOfNotNull(
            pauseResume?.let { action ->
                NowBarChipAction(
                    if (event.state == RecordingState.RECORDING) ActionIcon.PAUSE else ActionIcon.PLAY,
                ) { _, _, ctx -> action.action.actionIntent?.sendWithBal(ctx) }
            },
            stop?.let { action ->
                NowBarChipAction(ActionIcon.STOP) { _, _, ctx ->
                    action.action.actionIntent?.sendWithBal(ctx)
                }
            },
        )
    }
    is IslandEvent.Timer -> {
        val toggleAction = event.actions.firstOrNull()
        listOfNotNull(
            toggleAction?.let { action ->
                NowBarChipAction(
                    if (event.isPaused) ActionIcon.PLAY else ActionIcon.PAUSE,
                ) { _, _, ctx -> action.action.actionIntent?.sendWithBal(ctx) }
            },
        )
    }
    is IslandEvent.Stopwatch -> {
        val toggleAction = event.actions.firstOrNull()
        listOfNotNull(
            toggleAction?.let { action ->
                NowBarChipAction(
                    if (event.isRunning) ActionIcon.PAUSE else ActionIcon.PLAY,
                ) { _, _, ctx -> action.action.actionIntent?.sendWithBal(ctx) }
            },
        )
    }
    is IslandEvent.Torch -> listOf(
        NowBarChipAction(ActionIcon.STOP) { vm, event, _ -> vm.dismissEvent(event) },
    )
    else -> emptyList()
}

@Composable
private fun ActionButton(
    icon: ActionIcon,
    color: Color,
    bgColor: Color,
    onClick: () -> Unit,
    size: Dp = ActionSize,
    iconSize: Dp = ActionIconSize,
) {
    val imageVector = when (icon) {
        ActionIcon.PLAY -> Icons.Filled.PlayArrow
        ActionIcon.PAUSE -> Icons.Filled.Pause
        ActionIcon.STOP -> Icons.Filled.Stop
        ActionIcon.SKIP_PREV -> Icons.Filled.SkipPrevious
        ActionIcon.SKIP_NEXT -> Icons.Filled.SkipNext
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = bgColor,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
            Icon(
                imageVector,
                contentDescription = icon.name,
                tint = color,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun chipStrokeColor(): Color {
    val base = if (isSystemInDarkTheme()) Color.White else Color.Black
    return base.copy(alpha = 0.3f)
}

@Composable
private fun adaptiveNowBarMonoStyle(): TextStyle {
    val customFontSp = LocalNowBarTextFontSp.current
    return if (customFontSp != null) PillMono.copy(fontSize = customFontSp.sp) else PillMono
}

@Composable
private fun MarqueeText(
    text: String,
    color: Color,
    modifier: Modifier,
) {
    val marqueeEnabled = LocalNowBarMarqueeEnabled.current
    val customMaxWidth = LocalNowBarTextMaxWidth.current
    val customFontSp = LocalNowBarTextFontSp.current
    Text(
        text,
        color = color,
        style = if (customFontSp != null)
            PillPrimary.copy(fontSize = customFontSp.sp)
            else PillPrimary,
        maxLines = 1,
        overflow = if (marqueeEnabled) TextOverflow.Clip else TextOverflow.Ellipsis,
        textAlign = if (customMaxWidth != null) TextAlign.Center else TextAlign.Unspecified,
        modifier = modifier.widthIn(max = customMaxWidth ?: 120.dp).then(
            if (marqueeEnabled) Modifier.basicMarquee(iterations = 1) else Modifier
        ),
    )
}

@Composable
private fun ElapsedTimeText(
    startTimeMs: Long,
    color: Color,
    modifier: Modifier,
    pausedDurationMs: Long = 0L,
) {
    var elapsedMs by remember(startTimeMs, pausedDurationMs) {
        mutableLongStateOf(
            (System.currentTimeMillis() - startTimeMs - pausedDurationMs).coerceAtLeast(0L)
        )
    }
    LaunchedEffect(startTimeMs, pausedDurationMs) {
        while (true) {
            delay(1000)
            elapsedMs = (System.currentTimeMillis() - startTimeMs - pausedDurationMs)
                .coerceAtLeast(0L)
        }
    }
    Text(formatCountdownLong(elapsedMs), color = color, style = adaptiveNowBarMonoStyle(), modifier = modifier)
}

@Composable
private fun CountdownText(event: IslandEvent.Timer, color: Color, modifier: Modifier) {
    if (event.isPaused) {
        Text(stringResource(R.string.ax_dynamic_bar_paused), color = color, style = adaptiveNowBarMonoStyle(), modifier = modifier)
    } else {
        var remainingMs by remember(event.endTimeMs) {
            mutableLongStateOf((event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L))
        }
        LaunchedEffect(event.endTimeMs) {
            while (remainingMs > 0L) {
                delay(500)
                remainingMs = (event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
            }
        }
        Text(formatCountdownLong(remainingMs), color = color, style = adaptiveNowBarMonoStyle(), modifier = modifier)
    }
}

@Composable
private fun StopwatchTimeText(event: IslandEvent.Stopwatch, color: Color, modifier: Modifier) {
    if (!event.isRunning) {
        Text(stringResource(R.string.ax_dynamic_bar_paused), color = color, style = adaptiveNowBarMonoStyle(), modifier = modifier)
    } else {
        var elapsedMs by remember(event.startTimeMs) {
            mutableLongStateOf((System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L))
        }
        LaunchedEffect(event.startTimeMs) {
            while (true) {
                delay(200)
                elapsedMs = (System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L)
            }
        }
        Text(formatStopwatch(elapsedMs), color = color, style = adaptiveNowBarMonoStyle(), modifier = modifier)
    }
}
