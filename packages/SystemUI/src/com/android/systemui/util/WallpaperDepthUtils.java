/*
 * Copyright (C) 2026 MistOS
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
package com.android.systemui.util;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.MathUtils;
import android.view.IWallpaperZoomListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.FrameLayout;

import com.android.systemui.Dependency;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.keyguard.ui.view.KeyguardRootView;
import com.android.systemui.media.MediaViewController;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.LightRevealScrim;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ScrimState;
import com.android.systemui.tuner.TunerService;

import java.io.File;


public class WallpaperDepthUtils {

    private static final String WALLPAPER_DEPTH_KEY = "system:depth_wallpaper_subject_image_uri";
    private static final String WALLPAPER_DEPTH_ENABLED_KEY = "system:depth_wallpaper_enabled";
    private static final String WALLPAPER_DEPTH_OPACITY_KEY = "system:depth_wallpaper_opacity";
    private static final String WALLPAPER_DEPTH_OFFSET_X_KEY = "system:depth_wallpaper_offset_x";
    private static final String WALLPAPER_DEPTH_OFFSET_Y_KEY = "system:depth_wallpaper_offset_y";
    private static final String WALLPAPER_DEPTH_BOTTOM_FADE_KEY = "system:depth_wallpaper_bottom_fade";
    private static final String WALLPAPER_DEPTH_FADE_CURVE_KEY = "system:depth_wallpaper_fade_curve";
    private static final String WALLPAPER_DEPTH_BOTTOM_INSET_KEY = "system:depth_wallpaper_bottom_inset";
    private static final String WALLPAPER_DEPTH_SPATIAL_KEY = "system:depth_wallpaper_spatial_effect";

    private static final int DEFAULT_FADE_CURVE_PERCENT = 50;
    private static final int DEFAULT_BOTTOM_FADE_DP = 100;
    private static final int DEFAULT_BOTTOM_INSET_DP = 180;

    private static WallpaperDepthUtils instance;
    private FrameLayout mLockScreenBackground;
    private FrameLayout mLockScreenSubject;
    private final FrameLayout mSubjectContainer;
    private KeyguardRootView mKeyguardRoot;
    private FrameLayout mKeyguardSubjectContainer;

    private final Context mContext;
    private final ScrimController mScrimController;
    private final TunerService mTunerService;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final UserTracker mUserTracker;
    private final SpatialEffectController mSpatialEffectController;
    private boolean mTunableRegistered;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private IWallpaperZoomListener mWallpaperZoomListener;
    private int mWallpaperZoomDisplayId;
    private boolean mHasWallpaperZoom;
    private float mWallpaperZoomScale = 1f;
    private float mWallpaperZoomPivotX;
    private float mWallpaperZoomPivotY;

    private boolean mDWallpaperEnabled;
    private boolean mSpatialEffectEnabled;
    private int mDWallOpacity = 255;
    private String mWallpaperSubjectPath;
    private int mSubjectWallpaperId;
    private int mSubjectUserId;
    private boolean mDozing;
    private boolean mAmbientWallpaperEnabled;
    private float mAmbientWallpaperBlurRadius;
    private float mAppliedAmbientBlurRadius;
    private boolean mShowingAmbientWallpaper;
    private LightRevealScrim mLightRevealScrim;
    private final Runnable mAmbientScrimChangedListener = () -> {
        if (canShowAmbientWallpaper()) {
            mLockScreenSubject.invalidate();
        }
    };
    private boolean mBouncerShowing;
    private boolean mGlanceableHubShowing;
    private boolean mDynamicBarExpanded;
    private boolean mWallpaperLoaded = false;
    private Rect mLoadedBounds;
    private String mLoadedSubjectPath;
    private LoadWallpaperTask mLoadWallpaperTask;
    private int mOffsetX;
    private int mOffsetY;
    private boolean mUnlocking;
    private boolean mGoingToSleep;
    private boolean mHoldSleepDepth;
    private boolean mUnlockFadeRunning;
    private int mVisibilityGeneration;
    private final Runnable mSleepDepthFallback = () -> {
        if (mGoingToSleep) finishSleepDepth();
    };

    private int mBottomFadeDp = DEFAULT_BOTTOM_FADE_DP;
    private int mFadeCurvePercent = DEFAULT_FADE_CURVE_PERCENT;
    private int mBottomInsetDp = DEFAULT_BOTTOM_INSET_DP;

    private WallpaperDepthUtils(Context context, ScrimController scrimController) {
        mContext = context.getApplicationContext();
        mScrimController = scrimController;
        mTunerService = Dependency.get(TunerService.class);
        mBroadcastDispatcher = Dependency.get(BroadcastDispatcher.class);
        mUserTracker = Dependency.get(UserTracker.class);
        mSpatialEffectController = new SpatialEffectController(mContext);

        mLockScreenBackground = new FrameLayout(mContext) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                applyWallpaperZoom();
            }
        };
        FrameLayout.LayoutParams bgLp = new FrameLayout.LayoutParams(-1, -1);
        mLockScreenBackground.setLayoutParams(bgLp);
        mLockScreenBackground.setClickable(false);
        mLockScreenBackground.setFocusable(false);
        mLockScreenBackground.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mLockScreenBackground.setVisibility(View.GONE);

        mSubjectContainer = new FrameLayout(mContext) {
            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                mSpatialEffectController.attachBackgroundView(mLockScreenBackground);
                mSpatialEffectController.attachSubjectView(mLockScreenSubject);
                mLightRevealScrim = getRootView().findViewById(R.id.light_reveal_scrim);
                if (mLightRevealScrim != null) {
                    mLightRevealScrim.setRevealChangedListener(mAmbientScrimChangedListener);
                }
                registerTunable();
                updateWallpaperZoomListener();
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                ViewGroup parent = (ViewGroup) getParent();
                if (parent != null && mLockScreenBackground.getParent() == null) {
                    parent.addView(mLockScreenBackground, 0);
                }
            }

            @Override
            protected void onConfigurationChanged(Configuration configuration) {
                super.onConfigurationChanged(configuration);
                mMainHandler.post(() -> {
                    if (!mTunableRegistered) return;
                    Rect bounds = getDepthWallpaperBounds();
                    if (mWallpaperLoaded && mLoadedBounds != null && !bounds.equals(mLoadedBounds)) {
                        updateDepthWallpaper(true);
                    } else if (mLoadWallpaperTask != null
                            && !bounds.equals(mLoadWallpaperTask.mDisplayBounds)) {
                        updateDepthWallpaper(true);
                    } else {
                        updateDepthWallpaperVisibility();
                    }
                });
            }

            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                if (mLockScreenBackground.getParent() != null) {
                    ((ViewGroup) mLockScreenBackground.getParent())
                            .removeView(mLockScreenBackground);
                }
                WallpaperDepthUtils.this.onDetachedFromWindow();
            }
        };

        mSubjectContainer.setClipChildren(false);
        mSubjectContainer.setClipToPadding(false);
        mSubjectContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mLockScreenSubject = new FrameLayout(mContext) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                applyWallpaperZoom();
            }
        };
        mSubjectContainer.addView(mLockScreenSubject, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mLockScreenSubject.setClickable(false);
        mLockScreenSubject.setFocusable(false);
        mLockScreenSubject.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mLockScreenSubject.setVisibility(View.GONE);
        registerTunable();
    }

    private void registerTunable() {
        if (mTunableRegistered) return;
        mTunableRegistered = true;
        mBroadcastDispatcher.registerReceiver(mWallpaperChangedReceiver,
                new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
                new HandlerExecutor(mMainHandler), UserHandle.ALL);
        clearDepthWallpaperIfChanged();
        mTunerService.addTunable(mTunable, WALLPAPER_DEPTH_KEY,
            WALLPAPER_DEPTH_ENABLED_KEY, WALLPAPER_DEPTH_OPACITY_KEY,
            WALLPAPER_DEPTH_OFFSET_X_KEY, WALLPAPER_DEPTH_OFFSET_Y_KEY,
            WALLPAPER_DEPTH_BOTTOM_FADE_KEY, WALLPAPER_DEPTH_FADE_CURVE_KEY,
            WALLPAPER_DEPTH_BOTTOM_INSET_KEY, WALLPAPER_DEPTH_SPATIAL_KEY);
    }

    private int getLockWallpaperId(int userId) {
        WallpaperManager wm = WallpaperManager.getInstance(mContext);
        int lockId = wm.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, userId);
        return lockId > 0 ? lockId
                : wm.getWallpaperIdForUser(WallpaperManager.FLAG_SYSTEM, userId);
    }

    private final BroadcastReceiver mWallpaperChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mTunableRegistered && Intent.ACTION_WALLPAPER_CHANGED.equals(intent.getAction())) {
                clearDepthWallpaperIfChanged();
            }
        }
    };

    private void clearDepthWallpaperIfChanged() {
        if (mWallpaperSubjectPath == null) {
            return;
        }
        if (mSubjectUserId != mUserTracker.getUserId()) {
            mWallpaperSubjectPath = null;
            clearDepthWallpaper();
            updateWallpaperZoomListener();
            return;
        }
        if (mSubjectWallpaperId == getLockWallpaperId(mSubjectUserId)) {
            return;
        }
        String currentSubjectPath = mTunerService.getValue(WALLPAPER_DEPTH_KEY);
        if (currentSubjectPath != null && !currentSubjectPath.equals(mWallpaperSubjectPath)) {
            mTunable.onTuningChanged(WALLPAPER_DEPTH_KEY, currentSubjectPath);
            return;
        }
        mWallpaperSubjectPath = null;
        clearDepthWallpaper();
        updateWallpaperZoomListener();
        mTunerService.setValue(WALLPAPER_DEPTH_KEY, (String) null);
    }

    public static WallpaperDepthUtils getInstance(
            Context context, ScrimController scrimController) {
        if (instance == null) {
            instance = new WallpaperDepthUtils(context, scrimController);
        }
        return instance;
    }

    public static WallpaperDepthUtils get() {
        return instance;
    }

    public void onStartedGoingToSleep() {
        mMainHandler.removeCallbacks(mSleepDepthFallback);
        mGoingToSleep = true;
        // Retain only an already visible spatial subject. Never introduce a new
        // depth layer on top of an app, bouncer, or the ambient lockscreen.
        mHoldSleepDepth = mSpatialEffectEnabled && canShowDepthWallpaper()
                && mLockScreenSubject.getVisibility() == View.VISIBLE
                && mLockScreenSubject.getParent() == mKeyguardSubjectContainer;
        if (mHoldSleepDepth) {
            mSpatialEffectController.setSleepPaused(true);
        } else {
            updateSpatialEffectActive();
            mSpatialEffectController.setSleepPaused(false);
        }
    }

    public void onFinishedGoingToSleep() {
        // Some AOD transitions never report a separate physical screen-off event.
        mMainHandler.removeCallbacks(mSleepDepthFallback);
        if (mGoingToSleep && mHoldSleepDepth) {
            mMainHandler.postDelayed(mSleepDepthFallback, 300L);
        }
    }

    public void onScreenTurnedOff() {
        if (mGoingToSleep) finishSleepDepth();
    }

    private void finishSleepDepth() {
        mMainHandler.removeCallbacks(mSleepDepthFallback);
        if (!mHoldSleepDepth) return;
        mHoldSleepDepth = false;
        // Stop sensing before releasing the pause; the screen is now covered/off.
        mSpatialEffectController.setActive(false, false);
        mSpatialEffectController.setSleepPaused(false);
        updateDepthWallpaperVisibility();
    }

    public void onStartedWakingUp() {
        mGoingToSleep = false;
        mMainHandler.removeCallbacks(mSleepDepthFallback);
        // If wake interrupted the screen-off animation, retain the same layer
        // until the awake keyguard is eligible again.
        updateDepthWallpaperVisibility();
    }

    public void onFinishedWakingUp() {
        if (!mGoingToSleep) {
            mHoldSleepDepth = false;
            updateDepthWallpaperVisibility();
        }
    }

    public void onUnlockStarted() {
        if (mUnlocking) {
            return;
        }
        mUnlocking = true;
        mHoldSleepDepth = false;
        hideDepthWallpaperImmediate();
        mSpatialEffectController.setSleepPaused(false);
        updateSpatialEffectActive();
    }

    public void onUnlockCancelled() {
        if (!mUnlocking) {
            return;
        }
        mUnlocking = false;
        cancelUnlockFade();
        updateDepthWallpaperVisibility();
        updateSpatialEffectActive();
    }

    public void onUnlockCompleted() {
        mUnlocking = false;
        updateSpatialEffectActive();
    }

    public void onDozingChanged(boolean dozing) {
        if (mDozing == dozing) {
            return;
        }
        mDozing = dozing;
        updateDepthWallpaperVisibility();
        updateSpatialEffectActive();
    }

    public void setAmbientWallpaperState(boolean enabled, float blurRadius) {
        blurRadius = Math.max(0f, blurRadius);
        if (mAmbientWallpaperEnabled == enabled
                && Float.compare(mAmbientWallpaperBlurRadius, blurRadius) == 0) {
            return;
        }
        mAmbientWallpaperEnabled = enabled;
        mAmbientWallpaperBlurRadius = blurRadius;
        updateDepthWallpaperVisibility();
    }

    public void onBouncerShowingChanged(boolean showing) {
        if (mBouncerShowing == showing) {
            return;
        }
        mBouncerShowing = showing;
        if (mBouncerShowing) {
            hideDepthWallpaper();
        } else {
            updateDepthWallpaperVisibility();
        }
        updateSpatialEffectActive();
    }

    public void setDynamicBarExpanded(boolean expanded) {
        if (mDynamicBarExpanded == expanded) {
            return;
        }
        mDynamicBarExpanded = expanded;
        // The full wallpaper already includes the subject. Hide the clock overlay
        // before the panel's first frame, keeping the background at its current tilt.
        if (expanded && mLockScreenSubject != null) {
            mLockScreenSubject.setVisibility(View.GONE);
        }
        updateDepthWallpaperVisibility();
    }

    public void onGlanceableHubShowingChanged(boolean showing) {
        if (mGlanceableHubShowing == showing) {
            return;
        }
        mGlanceableHubShowing = showing;
        if (mGlanceableHubShowing) {
            hideDepthWallpaper();
        } else {
            updateDepthWallpaperVisibility();
        }
        updateSpatialEffectActive();
    }

    private final TunerService.Tunable mTunable = new TunerService.Tunable() {
        @Override
        public void onTuningChanged(String key, String newValue) {
            switch (key) {
                case WALLPAPER_DEPTH_ENABLED_KEY:
                    mDWallpaperEnabled = TunerService.parseIntegerSwitch(newValue, false);
                    updateWallpaperZoomListener();
                    updateDepthWallpaper(true);
                    updateSpatialEffectActive();
                    break;

                case WALLPAPER_DEPTH_KEY:
                    mWallpaperSubjectPath = newValue;
                    mSubjectUserId = mUserTracker.getUserId();
                    mSubjectWallpaperId = getLockWallpaperId(mSubjectUserId);
                    updateWallpaperZoomListener();
                    updateDepthWallpaper(true);
                    break;

                case WALLPAPER_DEPTH_OPACITY_KEY:
                    int opacity = TunerService.parseInteger(newValue, 100);
                    mDWallOpacity = Math.round(opacity * 2.55f);
                    if (mLockScreenSubject.getBackground() != null) {
                        mLockScreenSubject.getBackground().setAlpha(mDWallOpacity);
                    }
                    break;

                case WALLPAPER_DEPTH_OFFSET_X_KEY:
                    mOffsetX = TunerService.parseInteger(newValue, 0);
                    updateDepthWallpaper(true);
                    break;

                case WALLPAPER_DEPTH_OFFSET_Y_KEY:
                    mOffsetY = TunerService.parseInteger(newValue, 0);
                    updateDepthWallpaper(true);
                    break;

                case WALLPAPER_DEPTH_BOTTOM_FADE_KEY:
                    mBottomFadeDp = TunerService.parseInteger(
                            newValue, DEFAULT_BOTTOM_FADE_DP);
                    applyBottomFade();
                    break;

                case WALLPAPER_DEPTH_FADE_CURVE_KEY:
                    mFadeCurvePercent = TunerService.parseInteger(
                            newValue, DEFAULT_FADE_CURVE_PERCENT);
                    applyFadeCurve();
                    break;

                case WALLPAPER_DEPTH_BOTTOM_INSET_KEY:
                    mBottomInsetDp = TunerService.parseInteger(
                            newValue, DEFAULT_BOTTOM_INSET_DP);
                    applyBottomInset();
                    break;

                case WALLPAPER_DEPTH_SPATIAL_KEY:
                    mSpatialEffectEnabled =
                            TunerService.parseIntegerSwitch(newValue, false);
                    updateDepthWallpaper(true);
                    break;

                default:
                    break;
            }
        }
    };

    public void setSubjectAlpha(float subjectAlpha) {
        if (mLockScreenSubject == null) {
            return;
        }
        mLockScreenSubject.post(() -> mLockScreenSubject.setAlpha(
                canShowAmbientWallpaper() ? 1f : subjectAlpha));
    }

    private void applyBottomFade() {
        if (mLockScreenSubject == null) {
            return;
        }
        Drawable background = mLockScreenSubject.getBackground();
        if (background instanceof org.lunaris.clockmaker.spatial.FadeBottomDrawable) {
            ((org.lunaris.clockmaker.spatial.FadeBottomDrawable) background)
                    .setFadeHeightPx(dpToPx(mBottomFadeDp));
        }
    }

    private void applyBottomInset() {
        if (mLockScreenSubject == null) {
            return;
        }
        Drawable background = mLockScreenSubject.getBackground();
        if (background instanceof org.lunaris.clockmaker.spatial.FadeBottomDrawable) {
            ((org.lunaris.clockmaker.spatial.FadeBottomDrawable) background)
                    .setBottomInsetPx(dpToPx(mBottomInsetDp));
        }
    }

    private void applyFadeCurve() {
        if (mLockScreenSubject == null) {
            return;
        }
        Drawable background = mLockScreenSubject.getBackground();
        if (background instanceof org.lunaris.clockmaker.spatial.FadeBottomDrawable) {
            ((org.lunaris.clockmaker.spatial.FadeBottomDrawable) background)
                    .setFadeCurveExponent(computeFadeCurveExponent());
        }
    }

    private float computeFadeCurveExponent() {
        float percent = MathUtils.constrain(mFadeCurvePercent, 0, 100) / 100f;
        return 0.2f + percent * 0.8f;
    }

    private int dpToPx(int dp) {
        DisplayMetrics displayMetrics =
                mContext.getResources().getDisplayMetrics();
        return Math.round(dp * displayMetrics.density);
    }

    public void updateDepthWallpaper() {
        updateDepthWallpaper(false);
    }

    public FrameLayout getDepthWallpaperView() {
        return mSubjectContainer;
    }

    public void setKeyguardRoot(KeyguardRootView root) {
        mKeyguardRoot = root;
        mKeyguardSubjectContainer = root.findViewById(R.id.keyguard_depth_wallpaper_subject);
        updateDepthWallpaperVisibility();
    }

    public void setSubjectBehindShade(boolean behindShade) {
        float translationZ = behindShade ? -100f : 0f;
        if (mSubjectContainer.getTranslationZ() != translationZ) {
            mSubjectContainer.setTranslationZ(translationZ);
            updateDepthWallpaperVisibility();
        }
    }

    private void placeSubject(boolean showDepth) {
        boolean inKeyguard = showDepth && mKeyguardSubjectContainer != null
                && mSubjectContainer.getTranslationZ() == 0f;
        FrameLayout target = inKeyguard ? mKeyguardSubjectContainer : mSubjectContainer;
        if (mLockScreenSubject.getParent() != target) {
            if (mLockScreenSubject.getParent() instanceof ViewGroup) {
                ((ViewGroup) mLockScreenSubject.getParent()).removeView(mLockScreenSubject);
            }
            target.addView(mLockScreenSubject);
            applyWallpaperZoom();
        }
    }

    private void updateWallpaperZoomListener() {
        if (!isDWallpaperEnabled() || !mSubjectContainer.isAttachedToWindow()) {
            unregisterWallpaperZoomListener();
            updateDepthWallpaperVisibility();
            return;
        }
        if (mWallpaperZoomListener != null) return;
        mWallpaperZoomDisplayId = mSubjectContainer.getDisplay().getDisplayId();
        mWallpaperZoomListener = new IWallpaperZoomListener.Stub() {
            @Override
            public void onWallpaperZoomChanged(float scale, float pivotX, float pivotY) {
                mMainHandler.post(() -> {
                    if (mWallpaperZoomListener != this) return;
                    boolean firstUpdate = !mHasWallpaperZoom;
                    mWallpaperZoomScale = scale;
                    mWallpaperZoomPivotX = pivotX;
                    mWallpaperZoomPivotY = pivotY;
                    mHasWallpaperZoom = true;
                    applyWallpaperZoom();
                    if (firstUpdate) {
                        updateDepthWallpaperVisibility();
                    }
                });
            }
        };
        try {
            WindowManagerGlobal.getWindowManagerService().registerWallpaperZoomListener(
                    mWallpaperZoomListener, mWallpaperZoomDisplayId);
        } catch (RemoteException | IllegalArgumentException e) {
            Log.w("WallpaperDepthUtils", "Unable to listen for wallpaper zoom", e);
            mWallpaperZoomListener = null;
        }
    }

    private void unregisterWallpaperZoomListener() {
        if (mWallpaperZoomListener == null) return;
        IWallpaperZoomListener listener = mWallpaperZoomListener;
        mWallpaperZoomListener = null;
        mHasWallpaperZoom = false;
        try {
            WindowManagerGlobal.getWindowManagerService().unregisterWallpaperZoomListener(
                    listener, mWallpaperZoomDisplayId);
        } catch (RemoteException e) {
            Log.w("WallpaperDepthUtils", "Unable to remove wallpaper zoom listener", e);
        }
    }

    private void applyWallpaperZoom() {
        if (!mHasWallpaperZoom) return;
        mSpatialEffectController.setWallpaperZoom(
                mWallpaperZoomScale, mWallpaperZoomPivotX, mWallpaperZoomPivotY);
        if (canShowAmbientWallpaper()) {
            mLockScreenSubject.invalidate();
        }
    }

    public FrameLayout getBackgroundView() {
        return mLockScreenBackground;
    }

    private boolean isDWallpaperEnabled() {
        return mDWallpaperEnabled
                && mWallpaperSubjectPath != null
                && !mWallpaperSubjectPath.isEmpty();
    }

    private boolean canShowWallpaperLayers() {
        MediaViewController mediaViewController =
                MediaViewController.getOrNull();
        boolean albumArtVisible = mediaViewController != null
                && mediaViewController.albumArtVisible();
        return mLockScreenSubject != null
                && isDWallpaperEnabled()
                && mWallpaperLoaded
                && mHasWallpaperZoom
                && !mBouncerShowing
                && !mGlanceableHubShowing
                && (!mDynamicBarExpanded || mSpatialEffectEnabled)
                && !mUnlocking
                && mContext.getResources().getConfiguration().orientation
                        != Configuration.ORIENTATION_LANDSCAPE
                && !albumArtVisible;
    }

    private boolean canShowDepthWallpaper() {
        return canShowWallpaperLayers()
                && !mDozing
                && mScrimController.getState() == ScrimState.KEYGUARD;
    }

    private boolean canShowAmbientWallpaper() {
        ScrimState currentState = mScrimController.getState();
        return canShowWallpaperLayers()
                && mDozing
                && mAmbientWallpaperEnabled
                && (currentState == ScrimState.AOD || currentState == ScrimState.PULSING);
    }

    private boolean canHoldSleepDepth() {
        ScrimState state = mScrimController.getState();
        return mHoldSleepDepth && mSpatialEffectEnabled && canShowWallpaperLayers()
                && !mDynamicBarExpanded && mSubjectContainer.getTranslationZ() == 0f
                && (state == ScrimState.KEYGUARD || state == ScrimState.AOD || state == ScrimState.OFF);
    }

    public void updateDepthWallpaperVisibility() {
        if (mLockScreenSubject == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mMainHandler.post(this::updateDepthWallpaperVisibility);
            return;
        }
        // Resolve visibility and layer ownership together before the next frame.
        // Coalesce layout-time callbacks rather than reparenting inside a layout pass.
        mMainHandler.removeCallbacks(mApplyVisibility);
        if (mSubjectContainer.isInLayout() || (mKeyguardRoot != null && mKeyguardRoot.isInLayout())) {
            mMainHandler.post(mApplyVisibility);
        } else {
            mApplyVisibility.run();
        }
    }

    private final Runnable mApplyVisibility = this::applyDepthWallpaperVisibility;

    private void applyDepthWallpaperVisibility() {
        if (!mSubjectContainer.isAttachedToWindow()) return;
        ViewGroup parent = (ViewGroup) mSubjectContainer.getParent();
        if (parent != null && mLockScreenBackground.getParent() == null) {
            parent.addView(mLockScreenBackground, 0);
        }
        boolean awakeDepth = canShowDepthWallpaper();
        if (mHoldSleepDepth && (!canHoldSleepDepth() || (!mGoingToSleep && awakeDepth))) {
            mHoldSleepDepth = false;
        }
        boolean heldDepth = canHoldSleepDepth();
        boolean showDepth = awakeDepth || heldDepth;
        boolean showAmbient = !heldDepth && canShowAmbientWallpaper();
        if (mUnlockFadeRunning) {
            if ((showDepth || showAmbient) && !mUnlocking && ScrimUtils.get().isKeyguardShowing()) {
                cancelUnlockFade();
            } else if (mUnlocking || !ScrimUtils.get().isKeyguardShowing()) {
                updateSpatialEffectActive();
                return;
            }
        }
        // Hidden layers need not move behind the clock. Reparent only when
        // a visible layer actually needs a different host (AOD or shade).
        if (showDepth || showAmbient || mSubjectContainer.getTranslationZ() != 0f) {
            placeSubject(showDepth);
        }
        if (mShowingAmbientWallpaper != showAmbient) {
            if (showAmbient) {
                mLockScreenSubject.setAlpha(1f);
            }
            mLockScreenSubject.invalidate();
        }
        mShowingAmbientWallpaper = showAmbient;
        float blurRadius = showAmbient ? mAmbientWallpaperBlurRadius : 0f;
        if (Float.compare(mAppliedAmbientBlurRadius, blurRadius) != 0) {
            RenderEffect blurEffect = blurRadius > 0f
                    ? RenderEffect.createBlurEffect(
                            blurRadius, blurRadius, Shader.TileMode.CLAMP)
                    : null;
            mLockScreenBackground.setRenderEffect(blurEffect);
            mLockScreenSubject.setRenderEffect(blurEffect);
            mAppliedAmbientBlurRadius = blurRadius;
        }
        mLockScreenSubject.setVisibility(
                !mDynamicBarExpanded && (showDepth || showAmbient) ? View.VISIBLE : View.GONE);
        mLockScreenBackground.setVisibility(
                showDepth || showAmbient ? View.VISIBLE : View.GONE);
        updateSpatialEffectActive();
        if (!mHoldSleepDepth) mSpatialEffectController.setSleepPaused(false);
    }

    public void hideDepthWallpaper() {
        if (mLockScreenSubject == null
                || (mLockScreenSubject.getVisibility() == View.GONE
                        && mLockScreenBackground.getVisibility() == View.GONE)) {
            return;
        }

        // Bouncer/hub visibility is already reflected in the current state.
        // Do not enqueue an unconditional hide that can outlive a rapid relock.
        updateDepthWallpaperVisibility();
    }

    public void hideDepthWallpaperImmediate() {
        if (mLockScreenSubject == null) {
            return;
        }

        Runnable fade = () -> {
            // A transient keyguard visibility callback during a lock transition
            // must not start an unlock fade on the held subject.
            if (canHoldSleepDepth() && !mUnlocking) return;
            final int generation = ++mVisibilityGeneration;
            mUnlockFadeRunning = true;
            mLockScreenSubject.animate().cancel();

            if (mLockScreenBackground != null) {
                mLockScreenBackground.animate().cancel();
            }

            mLockScreenSubject.animate()
                    .alpha(0f)
                    .setDuration(120)
                    .withEndAction(() -> {
                        if (generation != mVisibilityGeneration) return;
                        mUnlockFadeRunning = false;
                        mLockScreenSubject.setVisibility(View.GONE);
                        mLockScreenSubject.setAlpha(1f);

                        if (mLockScreenBackground != null) {
                            mLockScreenBackground.setVisibility(View.GONE);
                            mLockScreenBackground.setAlpha(1f);
                        }
                    })
                    .start();

            if (mLockScreenBackground != null) {
                mLockScreenBackground.animate()
                        .alpha(0f)
                        .setDuration(120)
                        .start();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            fade.run();
        } else {
            mMainHandler.post(fade);
        }

        updateSpatialEffectActive();
    }

    private void cancelUnlockFade() {
        ++mVisibilityGeneration;
        mUnlockFadeRunning = false;
        mLockScreenSubject.animate().cancel();
        mLockScreenBackground.animate().cancel();
        mLockScreenSubject.setAlpha(1f);
        mLockScreenBackground.setAlpha(1f);
    }

    private void updateSpatialEffectActive() {
        if (mSpatialEffectController == null) {
            return;
        }

        if (canHoldSleepDepth()) return;
        boolean active = mSpatialEffectEnabled && canShowDepthWallpaper() && !mGoingToSleep;
        boolean animate = !mDozing && !mBouncerShowing && !mGlanceableHubShowing
                && !mDynamicBarExpanded && mScrimController.getState() == ScrimState.KEYGUARD
                && mContext.getResources().getConfiguration().orientation
                        != Configuration.ORIENTATION_LANDSCAPE;
        mSpatialEffectController.setActive(active, animate);
    }

    private Rect getDepthWallpaperBounds() {
        Rect bounds = mContext
                .getSystemService(WindowManager.class)
                .getCurrentWindowMetrics()
                .getBounds();
        return new Rect(0, 0,
                Math.min(bounds.width(), bounds.height()),
                Math.max(bounds.width(), bounds.height()));
    }

    public Bitmap getResizedBitmap(
            Bitmap wallpaperBitmap, float xOffsetDp, float yOffsetDp) {
        return getResizedBitmap(wallpaperBitmap, xOffsetDp, yOffsetDp,
                getDepthWallpaperBounds(), mContext.getResources().getDisplayMetrics().density);
    }

    private Bitmap getResizedBitmap(Bitmap wallpaperBitmap, float xOffsetDp, float yOffsetDp,
            Rect displayBounds, float density) {
        int width = displayBounds.width();
        int height = displayBounds.height();
        float scale = Math.max(width / (float) wallpaperBitmap.getWidth(),
                height / (float) wallpaperBitmap.getHeight());
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(
                (width - wallpaperBitmap.getWidth() * scale) / 2f + xOffsetDp * density,
                (height - wallpaperBitmap.getHeight() * scale) / 2f + yOffsetDp * density);
        Bitmap resizedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(resizedBitmap);
        canvas.drawBitmap(wallpaperBitmap, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
        return resizedBitmap;
    }

    public void updateDepthWallpaper(boolean forced) {
        if (!mTunableRegistered || !isDWallpaperEnabled()) {
            clearDepthWallpaper();
            return;
        }

        if (forced || (!mWallpaperLoaded && mLoadWallpaperTask == null)) {
            boolean keepLayers = mWallpaperLoaded && mSubjectUserId == mUserTracker.getUserId()
                    && mSubjectWallpaperId == getLockWallpaperId(mSubjectUserId)
                    && java.util.Objects.equals(mLoadedSubjectPath, mWallpaperSubjectPath);
            if (keepLayers) {
                if (mLoadWallpaperTask != null) mLoadWallpaperTask.cancel(false);
            } else {
                clearDepthWallpaper();
            }
            mLoadWallpaperTask = new LoadWallpaperTask(mWallpaperSubjectPath, mOffsetX, mOffsetY,
                    mSubjectUserId);
            mLoadWallpaperTask.execute();
        }

        updateDepthWallpaperVisibility();
    }

    private void clearDepthWallpaper() {
        if (mLoadWallpaperTask != null) {
            mLoadWallpaperTask.cancel(false);
            mLoadWallpaperTask = null;
        }
        mHoldSleepDepth = false;
        mMainHandler.removeCallbacks(mSleepDepthFallback);
        mSpatialEffectController.setActive(false, false);
        mSpatialEffectController.setSleepPaused(false);
        cancelUnlockFade();
        mWallpaperLoaded = false;
        mLoadedBounds = null;
        mLoadedSubjectPath = null;
        mLockScreenSubject.animate().cancel();
        mLockScreenSubject.setVisibility(View.GONE);
        mLockScreenSubject.setAlpha(1f);
        mLockScreenSubject.setBackground(null);
        mLockScreenSubject.setRenderEffect(null);
        mLockScreenBackground.animate().cancel();
        mLockScreenBackground.setVisibility(View.GONE);
        mLockScreenBackground.setAlpha(1f);
        mLockScreenBackground.setBackground(null);
        mLockScreenBackground.setRenderEffect(null);
        mAppliedAmbientBlurRadius = 0f;
        mShowingAmbientWallpaper = false;
        updateSpatialEffectActive();
    }

    private Bitmap loadSystemWallpaperBitmap(int userId) {
        Context userContext = mContext.createContextAsUser(UserHandle.of(userId), 0);
        WallpaperManager wm = WallpaperManager.getInstance(userContext);

        if (wm == null) {
            return null;
        }

        try (ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)) {
            if (pfd != null) {
                Bitmap bmp = BitmapFactory.decodeFileDescriptor(
                        pfd.getFileDescriptor());

                if (bmp != null) {
                    return bmp;
                }
            }
        } catch (Exception ignored) {
        }

        try (ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)) {
            if (pfd != null) {
                Bitmap bmp = BitmapFactory.decodeFileDescriptor(
                        pfd.getFileDescriptor());

                if (bmp != null) {
                    return bmp;
                }
            }
        } catch (Exception ignored) {
        }

        try {
            Drawable drawable = wm.getDrawable();

            if (drawable instanceof BitmapDrawable
                    && ((BitmapDrawable) drawable).getBitmap() != null) {
                return ((BitmapDrawable) drawable)
                        .getBitmap()
                        .copy(Bitmap.Config.ARGB_8888, false);
            }
        } catch (Exception ignored) {
        }

        try {
            File deFile = new File(
                    userContext.createDeviceProtectedStorageContext()
                            .getFilesDir(),
                    "wallpaper.jpg");

            if (deFile.exists()) {
                return BitmapFactory.decodeFile(
                        deFile.getAbsolutePath());
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static final class WallpaperLayers {
        final Drawable background;
        final Drawable subject;

        WallpaperLayers(Drawable bg, Drawable fg) {
            this.background = bg;
            this.subject = fg;
        }
    }

    private class AmbientSubjectDrawable extends SpatialWallpaperDrawable {
        private final Paint mScrimPaint = new Paint();
        private final Matrix mSubjectMatrix = new Matrix();
        private final Matrix mScrimMatrix = new Matrix();
        private final Matrix mScrimToSubjectMatrix = new Matrix();

        AmbientSubjectDrawable(Bitmap bitmap) {
            this(bitmap, null, 0, 0);
        }

        AmbientSubjectDrawable(Bitmap bitmap, float[] depth, int width, int height) {
            super(bitmap, depth, width, height);
            mScrimPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        }

        @Override
        public void draw(Canvas canvas) {
            if (!canShowAmbientWallpaper() || mLightRevealScrim == null
                    || mLightRevealScrim.getVisibility() != View.VISIBLE
                    || mLightRevealScrim.getAlpha() == 0f) {
                super.draw(canvas);
                return;
            }
            Rect bounds = getBounds();
            int subjectLayer = canvas.saveLayer(
                    bounds.left, bounds.top, bounds.right, bounds.bottom, null);
            super.draw(canvas);
            mSubjectMatrix.reset();
            mScrimMatrix.reset();
            mLockScreenSubject.transformMatrixToGlobal(mSubjectMatrix);
            mLightRevealScrim.transformMatrixToGlobal(mScrimMatrix);
            if (mSubjectMatrix.invert(mScrimToSubjectMatrix)) {
                mScrimToSubjectMatrix.preConcat(mScrimMatrix);
                mScrimPaint.setAlpha(Math.round(mLightRevealScrim.getAlpha() * 255f));
                int scrimLayer = canvas.saveLayer(
                        bounds.left, bounds.top, bounds.right, bounds.bottom, mScrimPaint);
                canvas.concat(mScrimToSubjectMatrix);
                mLightRevealScrim.draw(canvas);
                canvas.restoreToCount(scrimLayer);
            }
            canvas.restoreToCount(subjectLayer);
        }
    }

    private class LoadWallpaperTask
            extends AsyncTask<Void, Void, WallpaperLayers> {

        private final String mSubjectPath;
        private final int mSubjectOffsetX;
        private final int mSubjectOffsetY;
        private final int mUserId;
        private final Rect mDisplayBounds;
        private final float mDensity;
        private final boolean mSpatial;

        LoadWallpaperTask(String subjectPath, int offsetX, int offsetY, int userId) {
            mSubjectPath = subjectPath;
            mSubjectOffsetX = offsetX;
            mSubjectOffsetY = offsetY;
            mUserId = userId;
            mDisplayBounds = getDepthWallpaperBounds();
            mDensity = mContext.getResources().getDisplayMetrics().density;
            mSpatial = mSpatialEffectEnabled;
        }

        @Override
        protected WallpaperLayers doInBackground(Void... voids) {
            try {
                if (isCancelled()) return null;
                Log.d(
                        "LoadWallpaperTask",
                        "Wallpaper path: " + mSubjectPath);

                Bitmap bitmap =
                        BitmapFactory.decodeFile(mSubjectPath);

                if (bitmap == null) {
                    Log.d(
                            "LoadWallpaperTask",
                            "Failed to decode bitmap from file");
                    return null;
                }

                Bitmap resizedBitmap =
                        getResizedBitmap(bitmap, mSubjectOffsetX, mSubjectOffsetY,
                                mDisplayBounds, mDensity);
                if (bitmap != resizedBitmap) bitmap.recycle();

                if (resizedBitmap == null) {
                    Log.d(
                            "LoadWallpaperTask",
                            "Failed to decode resized bitmap from file");
                    return null;
                }

                Drawable bitmapDrawable = new AmbientSubjectDrawable(resizedBitmap);

                if (isCancelled()) return null;
                Drawable backgroundDrawable = null;
                Bitmap bgRaw = loadSystemWallpaperBitmap(mUserId);

                if (bgRaw != null) {
                    Bitmap resizedBg =
                            getResizedBitmap(
                                    bgRaw,
                                    mSpatial ? mSubjectOffsetX : 0,
                                    mSpatial ? mSubjectOffsetY : 0,
                                    mDisplayBounds,
                                    mDensity);

                    if (resizedBg != null) {
                        backgroundDrawable =
                                new BitmapDrawable(
                                        mContext.getResources(),
                                        resizedBg);

                        backgroundDrawable.setAlpha(255);
                    }
                }

                return new WallpaperLayers(
                        backgroundDrawable,
                        bitmapDrawable);

            } catch (OutOfMemoryError e) {
                Log.e(
                        "LoadWallpaperTask",
                        "Out of memory error",
                        e);
                return null;

            } catch (Exception e) {
                Log.e(
                        "LoadWallpaperTask",
                        "Error loading wallpaper",
                        e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(WallpaperLayers layers) {
            if (mLoadWallpaperTask != this || isCancelled()) {
                return;
            }
            clearDepthWallpaperIfChanged();
            if (mLoadWallpaperTask != this) {
                return;
            }
            mLoadWallpaperTask = null;
            if (!isDWallpaperEnabled() || layers == null || layers.subject == null) {
                Log.d(
                        "LoadWallpaperTask",
                        "decodeFile returned nothing, skipping "
                                + "application of subject as background");
                clearDepthWallpaper();
                return;
            }

            mLockScreenBackground.setBackground(layers.background);
            mLockScreenSubject.setBackground(layers.subject);
            layers.subject.setAlpha(mDWallOpacity);
            applyBottomFade();
            applyFadeCurve();
            applyBottomInset();
            mWallpaperLoaded = true;
            mLoadedBounds = new Rect(mDisplayBounds);
            mLoadedSubjectPath = mSubjectPath;
            updateDepthWallpaperVisibility();
            updateSpatialEffectActive();
            mSpatialEffectController.onLayersChanged();
        }
    }

    public void onDetachedFromWindow() {
        mGoingToSleep = mHoldSleepDepth = false;
        mMainHandler.removeCallbacks(mSleepDepthFallback);
        mMainHandler.removeCallbacks(mApplyVisibility);
        if (mLightRevealScrim != null) {
            mLightRevealScrim.setRevealChangedListener(null);
            mLightRevealScrim = null;
        }
        unregisterWallpaperZoomListener();
        clearDepthWallpaper();
        mBroadcastDispatcher.unregisterReceiver(mWallpaperChangedReceiver);
        mTunerService.removeTunable(mTunable);
        mTunableRegistered = false;
        mSpatialEffectController.destroy();
    }
}
