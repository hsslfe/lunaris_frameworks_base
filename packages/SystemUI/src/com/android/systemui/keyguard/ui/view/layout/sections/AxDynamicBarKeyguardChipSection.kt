package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.media.MediaViewController
import com.android.systemui.res.R
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.shared.R as SharedR
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.util.WallpaperDepthUtils
import javax.inject.Inject
import org.lunaris.extrainfowidgets.ExtraInfoFreeformView
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val CHIP_ABOVE_LOCK_MARGIN_DP = 12f
private const val EXPANDED_TOP_PROTECTION_DP = 16f
private const val EXPANDED_BOTTOM_PROTECTION_DP = 16f
private const val UNSET = -1


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
        R.id.dynamic_clock_ls,
        R.id.lockscreen_clock_view,
        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
        ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
        R.id.keyguard_info_widgets,
        R.id.keyguard_widgets,
        R.id.keyguard_app_widgets,
        R.id.keyguard_independent_info_widgets,
        R.id.left_aligned_notification_icon_container,
        R.id.status_view_media_container,
        R.id.bc_smartspace_view,
        SharedR.id.date_smartspace_view,
        SharedR.id.date_smartspace_view_large,
        SharedR.id.weather_smartspace_view,
        SharedR.id.weather_smartspace_view_large,
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
    private val clockInteractor: KeyguardClockInteractor,
) : KeyguardSection() {

    private val chipViewId = R.id.ax_dynamic_bar_keyguard_chip
    private val dismissViewId = R.id.ax_dynamic_bar_keyguard_dismiss_area
    private var bindHandle: DisposableHandle? = null
    private var expansionHandle: DisposableHandle? = null
    private var enforceAction: ViewTreeObserver.OnPreDrawListener? = null
    private var enforceObserver: ViewTreeObserver? = null

    private data class ContentPresentation(val transitionAlpha: Float, val accessibility: Int)

    private val hiddenViews = mutableMapOf<View, ContentPresentation>()
    private var panelOriginalTranslationZ: Float? = null
    private var dismissOriginalTranslationZ: Float? = null
    private var expandedExitPending = false
    private var notificationStackOccluded = false

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
        indicationController.setSuppressIndication(pillVisible || (!nowBarEnabled && viewModel.shouldSuppressKeyguardIndication.value))
        indicationController.setSuppressNowBarIndications(pillVisible)

        composeView.setContent { PlatformTheme { AxDynamicBarKeyguardChip(viewModel = viewModel) } }

        bindHandle =
            composeView.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    launch {
                        combine(viewModel.shouldSuppressKeyguardIndication, viewModel.nowBarAppearance,
                            viewModel.isLockscreenPillActivityVisible) { legacy, style, visible ->
                            visible || (!style.enabled && legacy)
                        }.distinctUntilChanged().collect { indicationController.setSuppressIndication(it) }
                    }
                    viewModel.isLockscreenPillActivityVisible.collect {
                        indicationController.setSuppressNowBarIndications(it)
                    }
                }
            }

        expansionHandle =
            composeView.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    val scope = this
                    scope.launch {
                        val interactor = viewModel.interactor
                        combine(
                            interactor.isOnKeyguard,
                            interactor.isDozing,
                            interactor.qsExpansion.map { it > 0f }.distinctUntilChanged(),
                            interactor.isPanelExpanded,
                            viewModel.isBouncerShowing,
                        ) { onKeyguard, dozing, qsOpen, panelExpanded, bouncer ->
                            Triple(onKeyguard && !dozing, qsOpen || panelExpanded && !bouncer, bouncer)
                        }.combine(viewModel.isKeyguardColorOsActive) { state, colorOs ->
                            state to colorOs
                        }.distinctUntilChanged().collect {
                            updateNotificationStackOcclusion(constraintLayout)
                        }
                    }
                    scope.launch {
                        viewModel.keyguardExpansion.collapseSettled.collect {
                            if (!viewModel.keyguardExpansion.isExpanded.value) {
                                expandedExitPending = false
                                rebindPreDrawAction(constraintLayout, false)
                                MediaViewController.getOrNull()?.setExpandedMusicOpen(false)
                                WallpaperDepthUtils.get()?.setDynamicBarExpanded(false)
                                applyCollapsedLp(composeView, viewModel.isLowUdfps.value)
                                setHiddenViewsVisibility(constraintLayout, View.VISIBLE)
                                updateNotificationStackOcclusion(constraintLayout)
                            }
                        }
                    }
                    scope.launch {
                        viewModel.isKeyguardExpanded.collectLatest { expanded ->
                            if (expanded) {
                                clockInteractor.clockSize.collect { size ->
                                    if (size != ClockSize.SMALL) {
                                        clockInteractor.setClockSize(ClockSize.SMALL)
                                    }
                                }
                            }
                        }
                    }
                    scope.launch {
                        combine(
                            viewModel.isKeyguardExpanded,
                            viewModel.keyguardExpansion.panelEvent,
                        ) { expanded, event -> expanded && event is IslandEvent.Media }
                            .distinctUntilChanged()
                            .collect { expandedMusicOpen ->
                                if (expandedMusicOpen || !expandedExitPending) {
                                    MediaViewController.getOrNull()?.setExpandedMusicOpen(expandedMusicOpen)
                                }
                            }
                    }
                    scope.launch {
                        combine(viewModel.isKeyguardExpanded, viewModel.isLowUdfps, viewModel.nowBarAppearance) { expanded, lowUdfps, style ->
                            Triple(expanded, lowUdfps, if (style.enabled) style.raise else 0)
                        }.distinctUntilChanged().collect { (expanded, lowUdfps, _) ->
                            if (!expanded && !expandedExitPending) applyCollapsedLp(composeView, lowUdfps)
                        }
                    }
                    combine(viewModel.isKeyguardExpanded, viewModel.isLowUdfps) { expanded, lowUdfps
                            ->
                            expanded to lowUdfps
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

    private fun collapsedBottomMarginPx(): Int {
        val style = viewModel.nowBarAppearance.value
        return extraBottomMarginPx(context) + if (style.enabled) style.raise.toFloat().dpToPx(context) else 0
    }

    private fun onExpandedStateChanged(
        constraintLayout: ConstraintLayout,
        composeView: View,
        expanded: Boolean,
        lowUdfps: Boolean,
    ) {
        val scrim = constraintLayout.requireViewById<View>(dismissViewId)
        scrim.animate().cancel()
        scrim.isClickable = expanded
        if (expanded) {
            expandedExitPending = true
            updateNotificationStackOcclusion(constraintLayout)
            WallpaperDepthUtils.get()?.setDynamicBarExpanded(true)
            scrim.visibility = View.VISIBLE
            scrim.animate().alpha(1f).setDuration(200L).withEndAction(null).start()
        } else {
            scrim.animate().alpha(0f).setDuration(200L).withEndAction {
                if (!viewModel.isKeyguardExpanded.value) scrim.visibility = View.GONE
            }.start()
        }
        if (expanded || !expandedExitPending || !viewModel.keyguardExpansion.canShow.value) {
            rebindPreDrawAction(constraintLayout, expanded)
        }
        TransitionManager.endTransitions(constraintLayout)
        if (!expanded && (!expandedExitPending || !viewModel.keyguardExpansion.canShow.value)) {
            expandedExitPending = false
            WallpaperDepthUtils.get()?.setDynamicBarExpanded(false)
            setHiddenViewsVisibility(constraintLayout, View.VISIBLE)
        }
        updateNotificationStackOcclusion(constraintLayout)
        if (expanded) {
            setHiddenViewsVisibility(constraintLayout, View.INVISIBLE)
            applyExpandedLp(composeView)
        }
    }

    private fun updateNotificationStackOcclusion(constraintLayout: ConstraintLayout) {
        val interactor = viewModel.interactor
        val authenticating = viewModel.isBouncerShowing.value
        val lockscreen = interactor.isOnKeyguard.value && !interactor.isDozing.value &&
            interactor.qsExpansion.value <= 0f &&
            (!interactor.isPanelExpanded.value || authenticating)
        // Keep the stack hidden across the activity's exit and the handoff to
        // authentication. A real shade or a different keyguard state owns its
        // own notification visibility.
        notificationStackOccluded = lockscreen &&
            (expandedExitPending || viewModel.isKeyguardExpanded.value ||
                (authenticating && (notificationStackOccluded ||
                    viewModel.isKeyguardColorOsActive.value)))
        constraintLayout.rootView
            .findViewById<SharedNotificationContainer>(R.id.shared_notification_container)
            ?.setKeyguardActivityOccluded(notificationStackOccluded)
    }

    private fun rebindPreDrawAction(constraintLayout: ConstraintLayout, expanded: Boolean) {
        enforceAction?.let { listener ->
            enforceObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        enforceAction = null
        enforceObserver = null
        if (!expanded) return

        // Follow the actual panel host rather than the separately rebound scrim observer.
        val observer = constraintLayout.viewTreeObserver
        enforceObserver = observer
        enforceAction = ViewTreeObserver.OnPreDrawListener {
            if (expandedExitPending || viewModel.isKeyguardExpanded.value) {
                enforceHidden(constraintLayout)
            }
            true
        }.also { observer.addOnPreDrawListener(it) }
    }

    private fun hiddenTargets(constraintLayout: ConstraintLayout): List<View> =
        HIDDEN_VIEW_IDS.mapNotNull { constraintLayout.findViewById<View>(it) }.distinct()

    private fun setIndependentWidgetsSuppressed(constraintLayout: ConstraintLayout, suppressed: Boolean) {
        constraintLayout.findViewById<ExtraInfoFreeformView>(R.id.keyguard_independent_info_widgets)
            ?.suppressed = suppressed
    }

    private fun setHiddenViewsVisibility(constraintLayout: ConstraintLayout, visibility: Int) {
        if (visibility != View.VISIBLE) {
            enforceHidden(constraintLayout)
            return
        }
        // Release only our override, not the visibility or alpha owned by keyguard.
        hiddenViews.forEach { (view, presentation) ->
            view.transitionAlpha = presentation.transitionAlpha
            view.importantForAccessibility = presentation.accessibility
        }
        hiddenViews.clear()
        setIndependentWidgetsSuppressed(constraintLayout, false)
        constraintLayout.findViewById<View>(chipViewId)?.let { view ->
            panelOriginalTranslationZ?.let { view.translationZ = it }
        }
        constraintLayout.findViewById<View>(dismissViewId)?.let { view ->
            dismissOriginalTranslationZ?.let { view.translationZ = it }
        }
        panelOriginalTranslationZ = null
        dismissOriginalTranslationZ = null
    }

    private fun enforceHidden(constraintLayout: ConstraintLayout) {
        setIndependentWidgetsSuppressed(constraintLayout, true)
        val targets = hiddenTargets(constraintLayout)
        targets.forEach { view ->
            hiddenViews.getOrPut(view) {
                ContentPresentation(view.transitionAlpha, view.importantForAccessibility)
            }
            if (view.transitionAlpha != 0f) view.transitionAlpha = 0f
            if (view.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }

        // Elevated clock/widget sections otherwise render over the activity
        // even when it was added later.
        val panel = constraintLayout.findViewById<View>(chipViewId) ?: return
        val dismiss = constraintLayout.findViewById<View>(dismissViewId) ?: return
        if (panelOriginalTranslationZ == null) panelOriginalTranslationZ = panel.translationZ
        if (dismissOriginalTranslationZ == null) dismissOriginalTranslationZ = dismiss.translationZ
        val topContentZ = targets.maxOfOrNull { target ->
            var child = target
            while (child.parent is View && child.parent !== constraintLayout) {
                child = child.parent as View
            }
            if (child.parent === constraintLayout) child.z else 0f
        } ?: 0f
        val dismissZ = maxOf(dismissOriginalTranslationZ ?: 0f, topContentZ + 1f - dismiss.elevation)
        if (dismiss.translationZ != dismissZ) dismiss.translationZ = dismissZ
        val panelZ = maxOf(panelOriginalTranslationZ ?: 0f, dismiss.z + 1f - panel.elevation)
        if (panel.translationZ != panelZ) panel.translationZ = panelZ
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
        TransitionManager.endTransitions(constraintLayout)
        rebindPreDrawAction(constraintLayout, false)
        expandedExitPending = false
        notificationStackOccluded = false
        constraintLayout.rootView
            .findViewById<SharedNotificationContainer>(R.id.shared_notification_container)
            ?.setKeyguardActivityOccluded(false)
        constraintLayout.findViewById<View>(dismissViewId)?.animate()?.cancel()
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
