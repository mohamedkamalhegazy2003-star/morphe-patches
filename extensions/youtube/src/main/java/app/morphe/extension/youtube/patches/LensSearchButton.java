package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Shows a small Google Lens button while the YouTube search box is being typed in.
 * It opens Google Lens, which identifies an image, and the result can be searched in YouTube.
 */
@SuppressWarnings("unused")
public final class LensSearchButton {

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static TextView button;
    private static boolean listenerAdded;

    /**
     * Called from {@link ShortsDownloadButton#initialize(Activity)}.
     */
    public static void initialize(Activity activity) {
        try {
            activityRef = new WeakReference<>(activity);
            button = null;
            listenerAdded = false;
            final View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    try {
                        update(activity, decor);
                    } catch (Exception ex) {
                        Logger.printException(() -> "LensSearchButton layout failure", ex);
                    }
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "LensSearchButton initialize failure", ex);
        }
    }

    private static boolean isSearchBoxFocused(Activity activity) {
        View focus = activity.getCurrentFocus();
        if (!(focus instanceof EditText edit)) return false;
        CharSequence hint = edit.getHint();
        CharSequence desc = edit.getContentDescription();
        String text = ((hint != null ? hint : "") + " " + (desc != null ? desc : "")).toLowerCase();
        // Any text box that is not a comment, reply or chat box is treated as the search box.
        String[] excluded = {"comment", "reply", "chat", "add a", "\u062a\u0639\u0644\u064a\u0642", "\u0631\u062f"};
        for (String word : excluded) {
            if (text.contains(word)) return false;
        }
        return true;
    }

    private static boolean isKeyboardVisible(View decor) {
        Rect r = new Rect();
        decor.getWindowVisibleDisplayFrame(r);
        int screenHeight = decor.getRootView().getHeight();
        return screenHeight - r.bottom > screenHeight * 0.15f;
    }

    private static void update(Activity activity, View decor) {
        boolean show = isKeyboardVisible(decor) && isSearchBoxFocused(activity);
        if (button == null) {
            if (!show) return;
            button = createButton(activity);
            activity.addContentView(button, layoutParams(activity));
        }
        int visibility = show ? View.VISIBLE : View.GONE;
        if (button.getVisibility() != visibility) {
            button.setVisibility(visibility);
            if (show) button.bringToFront();
        }
    }

    private static TextView createButton(Activity activity) {
        TextView tv = new TextView(activity);
        tv.setText("\uD83D\uDCF7");
        tv.setTextSize(20);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC202020);
        bg.setStroke(2, 0xFF4285F4);
        tv.setBackground(bg);
        tv.setContentDescription("Search with Google Lens");
        tv.setOnClickListener(v -> openLens(activity));
        return tv;
    }

    private static FrameLayout.LayoutParams layoutParams(Activity activity) {
        float d = activity.getResources().getDisplayMetrics().density;
        int size = (int) (44 * d);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = (int) (72 * d);
        lp.setMarginEnd((int) (12 * d));
        return lp;
    }

    private static void openLens(Activity activity) {
        try {
            Intent lens = new Intent(Intent.ACTION_VIEW, Uri.parse("google://lens"));
            lens.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(lens);
            } catch (ActivityNotFoundException e) {
                Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://lens.google.com/"));
                web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(web);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "openLens failure", ex);
            Utils.showToastShort("Google Lens is not available");
        }
    }

    private LensSearchButton() {}
}
