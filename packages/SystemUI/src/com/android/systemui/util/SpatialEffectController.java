/*
 * SPDX-FileCopyrightText: 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import com.android.systemui.Dependency;
import com.android.systemui.tuner.TunerService;
import org.lunaris.clockmaker.spatial.SpatialWallpaperMotion;

public final class SpatialEffectController implements ParallaxTiltSensor.Listener {
    // Zoom callbacks report changed transforms only. Two quiet frames plus a small
    // scheduling margin distinguish a settled transform from an in-flight animation.
    private static final long ZOOM_SETTLE_MS = 48L;
    private static final String SENSITIVITY = "system:depth_wallpaper_spatial_sensitivity";
    private final float maxTravelX;
    private final float maxTravelY;
    private float horizontal;
    private float vertical;
    private final SpatialWallpaperMotion motion;
    private final TunerService tuner;
    private final int[] parentLocation = new int[2];
    private View background;
    private View subject;
    private boolean registered;
    private boolean requested;
    private boolean hasZoom;
    private float zoom = 1f;
    private float pivotX;
    private float pivotY;
    private float spatialScale = 1f;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean zoomMoving;
    private final Runnable zoomSettled = () -> {
        zoomMoving = false;
        updateMotionPause();
    };
    private final TunerService.Tunable tunable = (key, value) -> {
        if (SENSITIVITY.equals(key)) setSensitivity(TunerService.parseInteger(value, 100));
    };

    public SpatialEffectController(Context context) {
        tuner = Dependency.get(TunerService.class);
        float density = context.getResources().getDisplayMetrics().density;
        maxTravelX = 12f * density;
        maxTravelY = 9f * density;
        motion = new SpatialWallpaperMotion(context, (x, y, scale) -> {
            horizontal = x;
            vertical = y;
            if (spatialScale != scale) {
                spatialScale = scale;
                applyScale();
            } else {
                applyTranslation(background);
                applyTranslation(subject);
            }
        });
    }

    public void setSensitivity(int percent) {
        motion.setSensitivity(percent);
        setActive(requested, true);
    }

    public void attachBackgroundView(View view) { background = view; applyScale(); }
    public void attachSubjectView(View view) {
        subject = view;
        if (!registered && tuner != null) {
            registered = true;
            tuner.addTunable(tunable, SENSITIVITY);
        }
        applyScale();
    }

    public void setWallpaperZoom(float scale, float x, float y) {
        if (!Float.isFinite(scale) || scale <= 0f || !Float.isFinite(x) || !Float.isFinite(y)) return;
        boolean changed = hasZoom && (Float.compare(zoom, scale) != 0
                || Float.compare(pivotX, x) != 0 || Float.compare(pivotY, y) != 0);
        if (changed) {
            zoomMoving = true;
            updateMotionPause();
            mainHandler.removeCallbacks(zoomSettled);
            mainHandler.postDelayed(zoomSettled, ZOOM_SETTLE_MS);
        }
        hasZoom = true;
        zoom = scale;
        pivotX = x;
        pivotY = y;
        applyScale();
    }

    private void updateMotionPause() {
        motion.setPaused(zoomMoving);
    }

    public void setActive(boolean active) { setActive(active, true); }
    public void setActive(boolean active, boolean animate) {
        requested = active;
        motion.setActive(active && hasLayer(background) && hasLayer(subject), animate);
    }

    public void onLayersChanged() { motion.dispatchPose(); applyScale(); }
    public void onDisplayRotationChanged(int rotation) { motion.onDisplayRotationChanged(rotation); }
    @Override public void onTiltChanged(float x, float y) { motion.onTiltChanged(x, y); }

    public void destroy() {
        requested = false;
        mainHandler.removeCallbacks(zoomSettled);
        zoomMoving = false;
        hasZoom = false;
        zoom = 1f;
        motion.destroy();
        if (registered) tuner.removeTunable(tunable);
        registered = false;
        background = subject = null;
    }

    private static boolean hasLayer(View view) {
        return view != null && view.getBackground() != null;
    }

    private void applyScale() { applyScale(background); applyScale(subject); }
    private void applyScale(View view) {
        if (view == null) return;
        if (hasZoom && view.getParent() instanceof View) {
            ((View) view.getParent()).getLocationOnScreen(parentLocation);
            view.setPivotX(pivotX - parentLocation[0] - view.getLeft());
            view.setPivotY(pivotY - parentLocation[1] - view.getTop());
        } else {
            view.setPivotX(view.getWidth() / 2f);
            view.setPivotY(view.getHeight() / 2f);
        }
        view.setScaleX(zoom * spatialScale);
        view.setScaleY(zoom * spatialScale);
        applyTranslation(view);
    }

    private void applyTranslation(View view) {
        if (view == null) return;
        // The original photo contains the subject, so both layers must track the same pose.
        view.setTranslationX(horizontal * maxTravelX + zoom * (1f - spatialScale)
                * (view.getWidth() / 2f - view.getPivotX()));
        view.setTranslationY(vertical * maxTravelY + zoom * (1f - spatialScale)
                * (view.getHeight() / 2f - view.getPivotY()));
    }
}
