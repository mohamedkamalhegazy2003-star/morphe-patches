package app.morphe.extension.youtube.patches;

import android.graphics.drawable.Drawable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;

/**
 * Adds a "Download" item to the video "..." menu.
 */
@SuppressWarnings("unused")
public final class DownloadFlyoutButton {

    public static int addFlyoutButton(Object flyoutPanel, int index, String videoId) {
        try {
            if (videoId == null || videoId.isEmpty()) return index;
            Drawable icon = ResourceUtils.getDrawable("morphe_yt_download_button");
            return FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    icon,
                    "Download",
                    v -> {
                        FlyoutUtils.dismissFlyout();
                        download(videoId);
                    },
                    index
            );
        } catch (Exception ex) {
            Logger.printException(() -> "DownloadFlyoutButton failure", ex);
            return index;
        }
    }

    private static void download(String videoId) {
        DownloadMode mode = SharedYouTubeSettings.DOWNLOAD_MODE.get();
        if (mode != DownloadMode.COBALT && mode != DownloadMode.EXTERNAL) {
            Utils.showToastLong("Set Download mode to Cobalt or External downloader");
            return;
        }
        ExternalDownloaderPreference.launchExternalDownloader(
                videoId, Utils.getActivity(), "https://youtu.be/" + videoId);
    }

    private DownloadFlyoutButton() {}
}
