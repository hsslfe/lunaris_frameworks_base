/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.coordinator

import android.app.Notification
import com.android.systemui.axdynamicbar.domain.AxDynamicBarSettings
import com.android.systemui.axdynamicbar.ui.AxDynamicBarColorOsController
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.provider.SectionHeaderVisibilityProvider
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Filters low priority and privacy-sensitive notifications from the lockscreen, and hides section
 * headers on the lockscreen. If enabled, it will also track and hide seen notifications on the
 * lockscreen.
 */
@CoordinatorScope
class KeyguardCoordinator
@Inject
constructor(
    private val keyguardNotificationVisibilityProvider: KeyguardNotificationVisibilityProvider,
    private val sectionHeaderVisibilityProvider: SectionHeaderVisibilityProvider,
    private val statusBarStateController: StatusBarStateController,
    private val nowBarSettings: AxDynamicBarSettings,
    @Application private val applicationScope: CoroutineScope,
) : Coordinator {

    private var colorOsStackRevealed = false
    private val colorOsRevealListener = AxDynamicBarColorOsController.Listener { active, revealed ->
        val show = active && revealed
        if (colorOsStackRevealed != show) {
            colorOsStackRevealed = show
            val nowBar = nowBarSettings.nowBarAppearance.value
            if (nowBar.enabled && nowBar.notifications && nowBar.notificationsWhenHidden) {
                invalidateListFromFilter("Now Bar ColorOS reveal changed")
            }
        }
    }

    override fun attach(pipeline: NotifPipeline) {
        setupInvalidateNotifListCallbacks()
        // Filter at the "finalize" stage so that views remain bound by PreparationCoordinator
        pipeline.addFinalizeFilter(notifFilter)
        keyguardNotificationVisibilityProvider.addOnStateChangedListener(::invalidateListFromFilter)
        // Rebuild the lock-screen list when Now Bar's call visibility option changes.
        nowBarSettings.nowBarAppearance
            .map { (it.enabled && it.calls && it.hideCall) to
                (it.enabled && it.notifications && it.notificationsWhenHidden) }
            .distinctUntilChanged()
            .onEach { invalidateListFromFilter("Now Bar notification visibility changed") }
            .launchIn(applicationScope)
        AxDynamicBarColorOsController.registerListener(colorOsRevealListener)
        updateSectionHeadersVisibility()
    }

    private val notifFilter: NotifFilter =
        object : NotifFilter(TAG) {
            override fun shouldFilterOut(entry: NotificationEntry, now: Long): Boolean {
                val nowBar = nowBarSettings.nowBarAppearance.value
                val explicitReveal = statusBarStateController.state == StatusBarState.KEYGUARD &&
                    nowBar.enabled && nowBar.notifications && nowBar.notificationsWhenHidden &&
                    AxDynamicBarColorOsController.getOrNull()?.isStackRevealed() == true
                val hidden = if (explicitReveal) {
                    keyguardNotificationVisibilityProvider.shouldHideNotificationForNowBar(entry)
                } else {
                    keyguardNotificationVisibilityProvider.shouldHideNotification(entry)
                }
                return hidden || shouldHideNowBarCall(entry)
            }
        }

    private fun shouldHideNowBarCall(entry: NotificationEntry): Boolean {
        if (statusBarStateController.state != StatusBarState.KEYGUARD) return false
        val nowBar = nowBarSettings.nowBarAppearance.value
        if (!nowBar.enabled || !nowBar.calls || !nowBar.hideCall) return false
        val notification = entry.sbn.notification
        if (notification.category != Notification.CATEGORY_CALL) return false
        val extras = notification.extras
        // Mirror the call classification used by Lunaris's live-event source:
        // only suppress a call that the Now Bar is capable of displaying.
        return extras.containsKey(Notification.EXTRA_ANSWER_INTENT) ||
            extras.containsKey(Notification.EXTRA_DECLINE_INTENT) ||
            extras.containsKey(Notification.EXTRA_HANG_UP_INTENT)
    }

    // TODO(b/206118999): merge this class with SensitiveContentCoordinator which also depends on
    //  these same updates
    private fun setupInvalidateNotifListCallbacks() {}

    private fun invalidateListFromFilter(reason: String) {
        updateSectionHeadersVisibility()
        notifFilter.invalidateList(reason)
    }

    private fun updateSectionHeadersVisibility() {
        val onKeyguard = statusBarStateController.state == StatusBarState.KEYGUARD
        val neverShowSections = sectionHeaderVisibilityProvider.neverShowSectionHeaders
        val showSections = !onKeyguard && !neverShowSections
        sectionHeaderVisibilityProvider.sectionHeadersVisible = showSections
    }

    companion object {
        private const val TAG = "KeyguardCoordinator"
    }
}
