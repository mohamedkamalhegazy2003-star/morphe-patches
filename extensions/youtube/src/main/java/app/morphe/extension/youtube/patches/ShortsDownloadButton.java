package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference;
import app.morphe.extension.youtube.shared.ShortsPlayerState;
import kotlin.Unit;

/**
 * A small download button that is shown on top of the Shorts player.
 * It downloads the Short with the selected download mode (Cobalt or the external downloader).
 */
@SuppressWarnings("unused")
public final class ShortsDownloadButton {

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static TextView button;
    private static boolean observerAdded;

    /**
     * Injection point. Called from the main activity onCreate.
     */
    public static void initialize(Activity activity) {
        try {
            activityRef = new WeakReference<>(activity);
            button = null; // A new activity needs a new view.
            LensSearchButton.initialize(activity);
            FastAnimationsPatch.apply();
            if (!observerAdded) {
                observerAdded = true;
                ShortsPlayerState.getOnChange().addObserver((Boolean open) -> {
                    Utils.runOnMainThread(() -> update(open));
                    return Unit.INSTANCE;
                });
            }
        } catch (Exception ex) {
            Logger.printException(() -> "ShortsDownloadButton initialize failure", ex);
        }
    }

    private static final long VISIBLE_MILLIS = 5000;
    private static long hideAtMillis;
    private static String lastVideoId = "";
    private static boolean polling;

    private static void update(boolean open) {
        try {
            Activity activity = activityRef.get();
            if (activity == null) return;

            if (button == null) {
                if (!open) return;
                button = createButton(activity);
                activity.addContentView(button, buttonLayoutParams(activity));
            }
            if (open) {
                showFor5Seconds();
                startPolling();
            } else {
                button.setVisibility(View.GONE);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "ShortsDownloadButton update failure", ex);
        }
    }

    private static void showFor5Seconds() {
        if (button == null) return;
        button.setVisibility(View.VISIBLE);
        button.bringToFront();
        hideAtMillis = System.currentTimeMillis() + VISIBLE_MILLIS;
    }

    /** Shows the button again for 5 seconds each time a new Short starts. */
    private static void startPolling() {
        if (polling) return;
        polling = true;
        Utils.runOnMainThreadDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!ShortsPlayerState.isOpen() || button == null) {
                        polling = false;
                        return;
                    }
                    String id = VideoInformation.getVideoId();
                    if (id != null && !id.equals(lastVideoId)) {
                        lastVideoId = id;
                        showFor5Seconds();
                    }
                    if (System.currentTimeMillis() >= hideAtMillis) {
                        button.setVisibility(View.GONE);
                    }
                    Utils.runOnMainThreadDelayed(this, 500);
                } catch (Exception ex) {
                    polling = false;
                    Logger.printException(() -> "ShortsDownloadButton polling failure", ex);
                }
            }
        }, 500);
    }

    private static TextView createButton(Activity activity) {
        TextView tv = new TextView(activity);
        tv.setText("⬇");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(20);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x99000000);
        tv.setBackground(bg);
        tv.setOnClickListener(v -> {
            hideAtMillis = System.currentTimeMillis() + VISIBLE_MILLIS;
            download(activity);
        });
        return tv;
    }

    private static FrameLayout.LayoutParams buttonLayoutParams(Activity activity) {
        float d = activity.getResources().getDisplayMetrics().density;
        int size = (int) (44 * d);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = (int) (110 * d);
        lp.setMarginEnd((int) (12 * d));
        return lp;
    }

    private static void download(Activity activity) {
        try {
            String id = VideoInformation.getVideoId();
            if (id == null || id.isEmpty()) {
                Utils.showToastShort("No Short found");
                return;
            }
            DownloadMode mode = SharedYouTubeSettings.DOWNLOAD_MODE.get();
            if (mode != DownloadMode.EXTERNAL) {
                Utils.showToastLong("Set Download mode to External downloader");
                return;
            }
            ExternalDownloaderPreference.launchExternalDownloader(
                    id, activity, "https://www.youtube.com/shorts/" + id);
        } catch (Exception ex) {
            Logger.printException(() -> "ShortsDownloadButton download failure", ex);
        }
    }

    private ShortsDownloadButton() {}
}
