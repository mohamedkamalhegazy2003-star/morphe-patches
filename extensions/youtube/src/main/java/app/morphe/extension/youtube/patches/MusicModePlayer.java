package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/**
 * A floating music note shown while a video plays. Tapping it opens a music player screen
 * with the cover, title, progress bar and play/pause and skip buttons. The video keeps playing under it.
 */
@SuppressWarnings("unused")
public final class MusicModePlayer {

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static TextView floatingButton;
    private static FrameLayout screen;
    private static ImageView cover;
    private static TextView titleView;
    private static TextView timeView;
    private static SeekBar seekBar;
    private static boolean polling;
    private static String loadedCoverFor = "";
    private static boolean userSeeking;

    /** Called from {@link ShortsDownloadButton#initialize(Activity)}. */
    public static void initialize(Activity activity) {
        try {
            activityRef = new WeakReference<>(activity);
            floatingButton = null;
            screen = null;
            if (!polling) {
                polling = true;
                Utils.runOnMainThreadDelayed(MusicModePlayer::tick, 1000);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "MusicModePlayer initialize failure", ex);
        }
    }

    private static void tick() {
        try {
            Activity activity = activityRef.get();
            if (activity != null) {
                updateFloatingButton(activity);
                if (screen != null && screen.getVisibility() == View.VISIBLE) {
                    updateScreen();
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "MusicModePlayer tick failure", ex);
        }
        Utils.runOnMainThreadDelayed(MusicModePlayer::tick, 500);
    }

    private static boolean videoIsPlayingInFront() {
        String id = VideoInformation.getVideoId();
        return id != null && !id.isEmpty()
                && !ShortsPlayerState.isOpen()
                && PlayerType.getCurrent().isMaximizedOrFullscreen();
    }

    private static void updateFloatingButton(Activity activity) {
        boolean show = videoIsPlayingInFront() && (screen == null || screen.getVisibility() != View.VISIBLE);
        if (floatingButton == null) {
            if (!show) return;
            floatingButton = makeCircle(activity, "♫", 20);
            floatingButton.setOnClickListener(v -> openScreen(activity));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(activity, 44), dp(activity, 44));
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.topMargin = dp(activity, 110);
            lp.setMarginStart(dp(activity, 12));
            activity.addContentView(floatingButton, lp);
        }
        int vis = show ? View.VISIBLE : View.GONE;
        if (floatingButton.getVisibility() != vis) {
            floatingButton.setVisibility(vis);
            if (show) floatingButton.bringToFront();
        }
    }

    private static TextView makeCircle(Context c, String text, int sizeSp) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(sizeSp);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCCB71C1C);
        tv.setBackground(bg);
        return tv;
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    private static TextView controlButton(Context c, String text, View.OnClickListener l) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(30);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(c, 18), dp(c, 8), dp(c, 18), dp(c, 8));
        tv.setOnClickListener(l);
        return tv;
    }

    private static void openScreen(Activity activity) {
        if (screen == null) {
            screen = buildScreen(activity);
            activity.addContentView(screen, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        screen.setVisibility(View.VISIBLE);
        screen.bringToFront();
        if (floatingButton != null) floatingButton.setVisibility(View.GONE);
        loadedCoverFor = "";
        updateScreen();
    }

    private static FrameLayout buildScreen(Activity activity) {
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(0xFF000000);
        root.setClickable(true); // Block touches to the video below.

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(activity, 24), dp(activity, 70), dp(activity, 24), dp(activity, 40));

        cover = new ImageView(activity);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int side = Math.min(activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 48),
                dp(activity, 340));
        col.addView(cover, new LinearLayout.LayoutParams(side, side));

        titleView = new TextView(activity);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(20);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(2);
        titleView.setPadding(0, dp(activity, 28), 0, dp(activity, 12));
        col.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        seekBar = new SeekBar(activity);
        seekBar.setMax(1000);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                long length = VideoInformation.getVideoLength();
                if (length > 0) VideoInformation.seekTo(length * s.getProgress() / 1000);
            }
        });
        col.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        timeView = new TextView(activity);
        timeView.setTextColor(0xFFAAAAAA);
        timeView.setGravity(Gravity.CENTER);
        col.addView(timeView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(activity, 20), 0, 0);
        controls.addView(controlButton(activity, "⏮", v -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)));
        controls.addView(controlButton(activity, "⏪", v -> VideoInformation.seekToRelative(-10000)));
        controls.addView(controlButton(activity, "⏯", v -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)));
        controls.addView(controlButton(activity, "⏩", v -> VideoInformation.seekToRelative(10000)));
        controls.addView(controlButton(activity, "⏭", v -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)));
        col.addView(controls);

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        TextView close = controlButton(activity, "✕", v -> {
            screen.setVisibility(View.GONE);
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        lp.topMargin = dp(activity, 30);
        root.addView(close, lp);
        return root;
    }

    private static void mediaKey(int keyCode) {
        try {
            AudioManager am = (AudioManager) Utils.getContext().getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        } catch (Exception ex) {
            Logger.printException(() -> "mediaKey failure", ex);
        }
    }

    private static void updateScreen() {
        String id = VideoInformation.getVideoId();
        if (id == null || id.isEmpty()) return;
        titleView.setText(VideoInformation.getVideoTitle());

        if (!id.equals(loadedCoverFor)) {
            loadedCoverFor = id;
            final String videoId = id;
            Utils.runOnBackgroundThread(() -> {
                try (InputStream in = new URL("https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg").openStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp != null) Utils.runOnMainThread(() -> cover.setImageBitmap(bmp));
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Cover load failed: " + ex);
                }
            });
        }

        long time = VideoInformation.getVideoTime();
        long length = VideoInformation.getVideoLength();
        if (length > 0 && time >= 0) {
            if (!userSeeking) seekBar.setProgress((int) (1000 * time / length));
            timeView.setText(format(time) + " / " + format(length));
        }
    }

    private static String format(long millis) {
        long s = millis / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private MusicModePlayer() {}
}
