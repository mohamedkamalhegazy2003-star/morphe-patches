package app.morphe.extension.youtube.patches;

import android.animation.ValueAnimator;

import java.lang.reflect.Method;

import app.morphe.extension.shared.Logger;

/**
 * Speeds up the animations of the app by shortening every value animator.
 * 0.5 runs the animations twice as fast. Called from {@link ShortsDownloadButton#initialize}.
 */
@SuppressWarnings("unused")
public final class FastAnimationsPatch {

    private static final float DURATION_SCALE = 0.5f;

    public static void apply() {
        try {
            Method setDurationScale = ValueAnimator.class.getDeclaredMethod("setDurationScale", float.class);
            setDurationScale.setAccessible(true);
            setDurationScale.invoke(null, DURATION_SCALE);
        } catch (Throwable ex) {
            Logger.printDebug(() -> "Could not change the animation speed: " + ex);
        }
    }

    private FastAnimationsPatch() {}
}
