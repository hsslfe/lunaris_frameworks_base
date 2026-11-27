package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.axion.compose.host.AxComposeView
import com.android.compose.theme.PlatformTheme
import com.android.internal.policy.SystemBarUtils
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.axdynamicbar.ui.compose.AxDynamicBarKeyguardChip
import com.android.systemui.islandevents.model.IslandEvent
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.media.MediaViewController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.util.ScrimUtils
import com.android.systemui.util.WallpaperDepthUtils
import javax.inject.Inject
import org.lunaris.extrainfowidgets.ExtraInfoFreeformView
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val CHIP_ABOVE_LOCK_MARGIN_DP = 12f
private const val EXPANDED_TOP_PROTECTION_DP = 16f
private const val EXPANDED_BOTTOM_PROTECTION_DP = 16f
private const val UNSET = -1

private const val HIDDEN_VIEWS_FADE_DURATION_START_MS = 145L

private fun extraBottomMarginPx(context: Context): Int =
    context.resources.getDimensionPixelSize(
        R.dimen.ax_dynamic_bar_keyguard_chip_extra_bottom_margin
    )

private val HIDDEN_VIEW_IDS =
    listOf(
        R.id.keyguard_weather,
        R.id.default_weather_image,
        R.id.default_weather_text,
        R.id.clock_ls,
        R.id.lockscreen_clock_view,
        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
        R.id.keyguard_independent_info_widgets,
        R.id.keyguard_info_widgets,
        R.id.keyguard_widgets,
        R.id.shared_notification_container,
        R.id.notificationShelf,
        R.id.bc_smartspace_view,
        R.id.smartspace_card_pager,
        R.id.smartspace_page_indicator,
        R.id.keyguard_slice_view,
        R.id.keyguard_weather_area,
    )

private fun Float.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density + 0.5f).toInt()

private fun expandedTopMarginPx(context: Context): Int =
    SystemBarUtils.getStatusBarHeight(context) + EXPANDED_TOP_PROTECTION_DP.dpToPx(context)

class AxDynamicBarKeyguardChipSection
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val viewModel: AxDynamicBarChipViewModel,
    private val indicationController: KeyguardIndicationController,
) : KeyguardSection() {

    private val chipViewId = R.id.ax_dynamic_bar_keyguard_chip
    private val dismissViewId = R.id.ax_dynamic_bar_keyguard_dismiss_area
    private var bindHandle: DisposableHandle? = null
    private var expansionHandle: DisposableHandle? = null
    private var enforceAction: Runnable? = null
    private val hiddenViews = mutableSetOf<View>()
    // While the collapsed Now Bar's expanded content fades away, keep the
    // native host anchored in its expanded slot. Reposition only once Compose
    // signals that the exit transition has fully settled.
    private var expandedExitPending = false

    override fun addViews(constraintLayout: ConstraintLayout) {
        val dismissView =
            View(context).apply {
                setBackgroundColor(0x66000000)
                alpha = 0f
                id = dismissViewId
                visibility = View.GONE
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isSoundEffectsEnabled = false
                setOnClickListener { viewModel.keyguardExpansion.collapse() }
            }
        constraintLayout.clipChildren = false
        constraintLayout.addView(dismissView, 0)
        val composeView =
            AxComposeView(context).apply {
                id = chipViewId
                clipChildren = false
                clipToPadding = false
            }
        constraintLayout.addView(composeView)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val composeView: AxComposeView = constraintLayout.requireViewById(chipViewId)

        val nowBarEnabled = viewModel.nowBarAppearance.value.enabled
        val pillVisible = viewModel.isLockscreenPillActivityVisible.value
        // Now Bar hides bottom text only when there is a real live activity.
        // The optional battery-only fallback must leave lockscreen hints visible.
        // If enabled but empty, restore every standard keyguard hint even when
        // the separately configured Dynamic Bar is enabled.
        indicationController.setSuppressIndication(
            pillVisible || (!nowBarEnabled && viewModel.shouldSuppressKeyguardIndication.value)
        )
        indicationController.setSuppressNowBarIndications(pillVisible)

        composeView.setContent { PlatformTheme { AxDynamicBarKeyguardChip(viewModel = viewModel) } }

        bindHandle =
            composeView.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    launch {
                        // With Now Bar enabled, its actual pill visibility alone
                        // determines text suppression. The classic Dynamic Bar
                        // rule applies only if Now Bar itself is switched off.
                        combine(
                            viewModel.shouldSuppressKeyguardIndication,
                            viewModel.nowBarAppearance,
                            viewModel.isLockscreenPillActivityVisible,
                        ) { legacySuppress, nowBar, visible ->
                            visible || (!nowBar.enabled && legacySuppress)
                        }
                            .distinctUntilChanged()
                            .collect { indicationController.setSuppressIndication(it) }
                    }
                    viewModel.isLockscreenPillActivityVisible
                        .collect { indicationController.setSuppressNowBarIndications(it) }
                }
            }

        expansionHandle =
            composeView.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    val scope = this
                    scope.launch {
                        viewModel.keyguardExpansion.collapseSettled.collect {
                            if (!viewModel.keyguardExpansion.isExpanded.value) {
                                // Complete the exit atomically: restoring the
                                // wallpaper or other lockscreen layers earlier
                                // makes the whole display flash while music fades.
                                if (expandedExitPending) {
                                    expandedExitPending = false
                                    rebindPreDrawAction(constraintLayout, false)
                                    WallpaperDepthUtils.get()?.setDynamicBarExpanded(false)
                                    constraintLayout.requireViewById<View>(dismissViewId)
                                        .visibility = View.GONE
                                    MediaViewController.getOrNull()
                                        ?.setExpandedMusicOpen(false)
                                }
                                applyCollapsedLp(composeView, viewModel.isLowUdfps.value)
                                setHiddenViewsVisibility(constraintLayout, View.VISIBLE)
                            }
                        }
                    }
                    scope.launch {
                        combine(
                            viewModel.isKeyguardExpanded,
                            viewModel.keyguardExpansion.panelEvent,
                            viewModel.chipState,
                            viewModel.nowBarAppearance,
                        ) { expanded, event, chipState, nowBar ->
                            expanded && (if (nowBar.enabled) chipState?.event else event) is IslandEvent.Media
                        }
                            .distinctUntilChanged()
                            .collect { expandedMusicOpen ->
                                // A track change may briefly clear chipState.
                                // Keep the media backdrop stable until the
                                // actual expanded exit has finished.
                                if (expandedMusicOpen || !expandedExitPending) {
                                    MediaViewController.getOrNull()
                                        ?.setExpandedMusicOpen(expandedMusicOpen)
                                }
                            }
                    }
                    scope.launch {
                        // Raise updates the native layout, not a child offset. This
                        // also preserves pointer hit-testing and the blur background.
                        combine(
                            viewModel.isKeyguardExpanded,
                            viewModel.isLowUdfps,
                            viewModel.nowBarAppearance,
                        ) { expanded, lowUdfps, style ->
                            Triple(expanded, lowUdfps, if (style.enabled) style.raise else 0)
                        }
                            .distinctUntilChanged()
                            .collect { (expanded, lowUdfps, _) ->
                                if (!expanded && !expandedExitPending) {
                                    // Raise/UD FPS updates remain immediate when
                                    // fully collapsed, but must not move an exiting
                                    // expanded panel before its fade is complete.
                                    applyCollapsedLp(composeView, lowUdfps)
                                }
                            }
                    }
                    combine(viewModel.isKeyguardExpanded, viewModel.isLowUdfps) {
                            expanded, lowUdfps -> expanded to lowUdfps
                        }
                        .collect { (expanded, lowUdfps) ->
                            onExpandedStateChanged(
                                constraintLayout,
                                composeView,
                                expanded,
                                lowUdfps,
                            )
                        }
                }
            }
    }

    /** Raise the host view itself so its measured content and touch region move together. */
    private fun collapsedBottomMarginPx(): Int {
        val style = viewModel.nowBarAppearance.value
        val raisedPx = if (style.enabled) style.raise.toFloat().dpToPx(context) else 0
        return extraBottomMarginPx(context) + raisedPx
    }

    private fun onExpandedStateChanged(
        constraintLayout: ConstraintLayout,
        composeView: View,
        expanded: Boolean,
        lowUdfps: Boolean,
    ) {
        val scrim = constraintLayout.requireViewById<View>(dismissViewId)
        scrim.animate().alpha(if (expanded) 1f else 0f).setDuration(200L).start()
        if (expanded) {
            expandedExitPending = true
        } else if (!expanded && expandedExitPending) {
            // The Compose expanded panel is still fading away. Do not change
            // wallpaper depth, pre-draw suppression or parent constraints yet.
            // The collapseSettled callback restores all of them together.
            return
        }
        // Hide all competing lockscreen layers before the expanded host is
        // resized. A deferred 250 ms fade lets the card overlap visible widgets.
        if (expanded) setHiddenViewsVisibility(constraintLayout, View.INVISIBLE)
        WallpaperDepthUtils.get()?.setDynamicBarExpanded(expanded)
        rebindPreDrawAction(constraintLayout, expanded)
        TransitionManager.endTransitions(constraintLayout)
        constraintLayout.requireViewById<View>(dismissViewId).visibility =
            if (expanded) View.VISIBLE else View.GONE
        if (!expanded && !viewModel.keyguardExpansion.canShow.value &&
            !expandedExitPending) {
            setHiddenViewsVisibility(constraintLayout, View.VISIBLE)
        }
        if (expanded) applyExpandedLp(composeView)
    }

    private fun rebindPreDrawAction(constraintLayout: ConstraintLayout, expanded: Boolean) {
        enforceAction?.let { ScrimUtils.get().removeKeyguardPreDrawAction(it) }
        enforceAction =
            if (expanded) {
                Runnable { enforceHidden(constraintLayout) }
                    .also { ScrimUtils.get().addKeyguardPreDrawAction(it) }
            } else null
    }

    private fun hiddenTargets(constraintLayout: ConstraintLayout): List<View> =
        HIDDEN_VIEW_IDS.mapNotNull { constraintLayout.rootView.findViewById<View>(it) }
            .distinct()

    private fun setIndependentWidgetsSuppressed(constraintLayout: ConstraintLayout, suppressed: Boolean) {
        constraintLayout.findViewById<ExtraInfoFreeformView>(R.id.keyguard_independent_info_widgets)
            ?.suppressed = suppressed
    }

    private fun setHiddenViewsVisibility(constraintLayout: ConstraintLayout, visibility: Int) {
        setIndependentWidgetsSuppressed(constraintLayout, visibility != View.VISIBLE)
        if (visibility == View.VISIBLE) {
            hiddenViews.forEach { v ->
                v.animate().cancel()
                if (v.visibility == View.GONE) {
                    v.alpha = 1f
                } else {
                    if (v.visibility != View.VISIBLE) {
                        v.alpha = 0f
                        v.visibility = View.VISIBLE
                    }
                    v.animate()
                        .alpha(1f)
                        .setDuration(HIDDEN_VIEWS_FADE_DURATION_START_MS)
                        .withEndAction(null)
                        .start()
                }
            }
            hiddenViews.clear()
        } else {
            // No fading live clock or widget can paint over the expanding card.
            // Restore them with a short fade only after collapse has settled.
            hiddenTargets(constraintLayout).filter { it.visibility == View.VISIBLE }.forEach { v ->
                hiddenViews.add(v)
                v.animate().cancel()
                v.alpha = 0f
                v.visibility = visibility
            }
        }

    }

    private fun enforceHidden(constraintLayout: ConstraintLayout) {
        setIndependentWidgetsSuppressed(constraintLayout, true)
        hiddenTargets(constraintLayout).forEach { v ->
            if (v.visibility == View.VISIBLE) {
                hiddenViews.add(v)
                v.animate().cancel()
                v.alpha = 0f
                v.visibility = View.INVISIBLE
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val expanded = viewModel.isKeyguardExpanded.value || expandedExitPending
        val lowUdfps = viewModel.isLowUdfps.value
        val bottomProtectionPx = EXPANDED_BOTTOM_PROTECTION_DP.dpToPx(context)
        val chipAboveLockPx = CHIP_ABOVE_LOCK_MARGIN_DP.dpToPx(context)
        val extraBottomPx = collapsedBottomMarginPx()
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        constraintSet.apply {
            constrainWidth(dismissViewId, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(dismissViewId, ConstraintSet.MATCH_CONSTRAINT)
            connect(dismissViewId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(
                dismissViewId,
                ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM,
            )
            connect(
                dismissViewId,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
            )
            connect(dismissViewId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            setVisibilityMode(dismissViewId, ConstraintSet.VISIBILITY_MODE_IGNORE)
            when {
                expanded -> {
                    constrainWidth(chipViewId, ConstraintSet.MATCH_CONSTRAINT)
                    constrainHeight(chipViewId, ConstraintSet.MATCH_CONSTRAINT)
                    // The small clock may be hidden or absent with a custom clock style.
                    connect(
                        chipViewId,
                        ConstraintSet.TOP,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.TOP,
                        expandedTopMarginPx(context),
                    )
                    connect(
                        chipViewId,
                        ConstraintSet.BOTTOM,
                        R.id.device_entry_icon_view,
                        ConstraintSet.TOP,
                        bottomProtectionPx,
                    )
                    connect(
                        chipViewId,
                        ConstraintSet.START,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.START,
                    )
                    connect(
                        chipViewId,
                        ConstraintSet.END,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.END,
                    )
                }
                lowUdfps -> {
                    constrainWidth(chipViewId, wrap)
                    constrainHeight(chipViewId, wrap)
                    connect(
                        chipViewId,
                        ConstraintSet.BOTTOM,
                        R.id.device_entry_icon_view,
                        ConstraintSet.TOP,
                        chipAboveLockPx + extraBottomPx,
                    )
                    connect(
                        chipViewId,
                        ConstraintSet.START,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.START,
                    )
                    connect(
                        chipViewId,
                        ConstraintSet.END,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.END,
                    )
                }
                else -> {
                    constrainWidth(chipViewId, wrap)
                    constrainHeight(chipViewId, wrap)
                    connect(
                        chipViewId,
                        ConstraintSet.BOTTOM,
                        R.id.start_button,
                        ConstraintSet.BOTTOM,
                        extraBottomPx,
                    )
                    connect(chipViewId, ConstraintSet.START, R.id.start_button, ConstraintSet.END)
                    connect(chipViewId, ConstraintSet.END, R.id.end_button, ConstraintSet.START)
                }
            }
        }
    }

    private fun applyExpandedLp(composeView: View) {
        val lp = composeView.layoutParams as ConstraintLayout.LayoutParams
        val bottomProtectionPx = EXPANDED_BOTTOM_PROTECTION_DP.dpToPx(context)
        lp.width = ConstraintLayout.LayoutParams.MATCH_PARENT
        lp.height = 0
        lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        lp.bottomToTop = R.id.device_entry_icon_view
        lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        lp.topMargin = expandedTopMarginPx(context)
        lp.bottomMargin = bottomProtectionPx
        lp.topToBottom = UNSET
        lp.bottomToBottom = UNSET
        lp.startToEnd = UNSET
        lp.endToStart = UNSET
        composeView.layoutParams = lp
    }

    private fun applyCollapsedLp(composeView: View, lowUdfps: Boolean) {
        val lp = composeView.layoutParams as ConstraintLayout.LayoutParams
        val extraBottomPx = collapsedBottomMarginPx()
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.topMargin = 0
        lp.topToTop = UNSET
        lp.topToBottom = UNSET
        if (lowUdfps) {
            lp.bottomToTop = R.id.device_entry_icon_view
            lp.bottomToBottom = UNSET
            lp.bottomMargin = CHIP_ABOVE_LOCK_MARGIN_DP.dpToPx(context) + extraBottomPx
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            lp.startToEnd = UNSET
            lp.endToStart = UNSET
        } else {
            lp.bottomToBottom = R.id.start_button
            lp.bottomToTop = UNSET
            lp.bottomMargin = extraBottomPx
            lp.startToEnd = R.id.start_button
            lp.endToStart = R.id.end_button
            lp.startToStart = UNSET
            lp.endToEnd = UNSET
        }
        composeView.layoutParams = lp
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        expandedExitPending = false
        TransitionManager.endTransitions(constraintLayout)
        enforceAction?.let { ScrimUtils.get().removeKeyguardPreDrawAction(it) }
        enforceAction = null
        WallpaperDepthUtils.get()?.setDynamicBarExpanded(false)
        MediaViewController.getOrNull()?.setExpandedMusicOpen(false)
        expansionHandle?.dispose()
        expansionHandle = null
        bindHandle?.dispose()
        bindHandle = null
        indicationController.setSuppressNowBarIndications(false)
        indicationController.setSuppressIndication(false)
        setHiddenViewsVisibility(constraintLayout, View.VISIBLE)
        constraintLayout.removeView(dismissViewId)
        constraintLayout.removeView(chipViewId)
    }
}
