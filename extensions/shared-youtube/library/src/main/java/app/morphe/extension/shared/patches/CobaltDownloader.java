package app.morphe.extension.shared.patches;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

/**
 * Download mode "Cobalt": asks a Cobalt instance for the video (default 1080p, h264 mp4)
 * and saves it to Movies/Cobalt with the system DownloadManager, so it plays offline.
 * Needs no YouTube Premium.
 */
@SuppressWarnings("unused")
public final class CobaltDownloader {
    private static final String UA = "MorpheCobalt/1.0";

    private static final String[] LABELS = {
            "Video 1080p (MP4)",
            "Video 720p (MP4)",
            "Video 480p (MP4)",
            "Video 4K / best (WebM)",
            "Audio MP3",
            "Audio OGG",
            "Audio WAV",
            "Audio OPUS",
            "Audio best",
            "Video without sound"
    };

    public static void download(Context context, String videoUrl) {
        final Context ctx = context != null ? context : Utils.getContext();
        if (SharedYouTubeSettings.COBALT_ASK_FORMAT.get()) {
            Activity activity = Utils.getActivity();
            if (activity != null) {
                Utils.runOnMainThread(() -> new AlertDialog.Builder(activity)
                        .setTitle("Cobalt download")
                        .setItems(LABELS, (d, which) -> start(ctx, videoUrl, which))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
                return;
            }
        }
        start(ctx, videoUrl, -1);
    }

    /** @param choice index into LABELS, or -1 to use the quality from settings. */
    private static void start(Context ctx, String videoUrl, int choice) {
        Utils.showToastShort("Cobalt: preparing download...");
        Utils.runOnBackgroundThread(() -> {
            try {
                String error = request(ctx, videoUrl, choice);
                if (error == null) {
                    Utils.runOnMainThread(() -> Utils.showToastLong("Download started. Saved in Movies/Cobalt"));
                } else {
                    Utils.runOnMainThread(() -> Utils.showToastLong("Cobalt error: " + error));
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Cobalt download failure", ex);
                Utils.runOnMainThread(() -> Utils.showToastLong("Cobalt error: " + ex.getMessage()));
            }
        });
    }

    private static String request(Context ctx, String videoUrl, int choice) throws Exception {
        String instance = SharedYouTubeSettings.COBALT_INSTANCE.get().trim();
        while (instance.endsWith("/")) instance = instance.substring(0, instance.length() - 1);
        if (instance.isEmpty()) return "instance URL is empty";

        String quality = SharedYouTubeSettings.COBALT_QUALITY.get().trim();
        if (quality.isEmpty()) quality = "1080";

        JSONObject body = new JSONObject();
        body.put("url", videoUrl);
        body.put("videoQuality", quality);
        body.put("downloadMode", "auto");
        body.put("youtubeVideoCodec", "h264");
        switch (choice) {
            case 0: body.put("videoQuality", "1080"); break;
            case 1: body.put("videoQuality", "720"); break;
            case 2: body.put("videoQuality", "480"); break;
            case 3:
                body.put("videoQuality", "max");
                body.put("youtubeVideoCodec", "vp9");
                break;
            case 4: audio(body, "mp3"); break;
            case 5: audio(body, "ogg"); break;
            case 6: audio(body, "wav"); break;
            case 7: audio(body, "opus"); break;
            case 8: audio(body, "best"); break;
            case 9: body.put("downloadMode", "mute"); break;
            default: break;
        }
        body.put("filenameStyle", "basic");

        HttpURLConnection c = (HttpURLConnection) new URL(instance + "/").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", UA);
        String key = SharedYouTubeSettings.COBALT_API_KEY.get().trim();
        if (!key.isEmpty()) c.setRequestProperty("Authorization", "Api-Key " + key);
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes("UTF-8"));
        }

        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is == null) return "HTTP " + code;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        JSONObject r;
        try {
            r = new JSONObject(bo.toString("UTF-8"));
        } catch (Exception e) {
            return "HTTP " + code;
        }

        String status = r.optString("status");
        switch (status) {
            case "tunnel":
            case "redirect":
                enqueue(ctx, r.getString("url"), r.optString("filename", "video.mp4"));
                return null;
            case "picker": {
                JSONArray arr = r.getJSONArray("picker");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String ext = "photo".equals(o.optString("type")) ? ".jpg" : ".mp4";
                    enqueue(ctx, o.getString("url"), "cobalt_" + System.currentTimeMillis() + "_" + i + ext);
                }
                return null;
            }
            case "error": {
                JSONObject e = r.optJSONObject("error");
                return e != null ? e.optString("code", "error") : "error";
            }
            default:
                return status.isEmpty() ? "HTTP " + code : status;
        }
    }

    private static void audio(JSONObject body, String format) throws Exception {
        body.put("downloadMode", "audio");
        body.put("audioFormat", format);
    }

    private static void enqueue(Context ctx, String url, String filename) {
        filename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        req.addRequestHeader("User-Agent", UA);
        req.setTitle(filename);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "Cobalt/" + filename);
        req.allowScanningByMediaScanner();
        ((DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
    }

    private CobaltDownloader() {}
}
