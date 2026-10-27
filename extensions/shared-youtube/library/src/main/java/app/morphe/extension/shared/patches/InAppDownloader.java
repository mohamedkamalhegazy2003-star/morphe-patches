package app.morphe.extension.shared.patches;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/**
 * A downloader that runs inside the app. It takes the direct stream urls of the video from the
 * same source that the app uses for playback, downloads the video and audio streams
 * and joins them into one MP4 file in Movies/Youtube M.H.
 */
@SuppressWarnings("unused")
public final class InAppDownloader {

    private static final List<ClientType> CLIENTS = List.of(
            ClientType.VISIONOS_1_03,
            ClientType.VISIONOS_1_02,
            ClientType.ANDROID_VR_DASH,
            ClientType.TV_DASH,
            ClientType.ANDROID_CREATOR,
            ClientType.TV_SIMPLY
    );

    private static final String[] LABELS = {
            "Video 1080p (MP4)",
            "Video 720p (MP4)",
            "Video 480p (MP4)",
            "Video 360p (MP4)",
            "Audio only (M4A)"
    };

    /** H.264 video-only itags for 1080p, 720p, 480p, 360p. */
    private static final int[] VIDEO_ITAGS = {137, 136, 135, 134};
    /** AAC audio itags, best first. */
    private static final int[] AUDIO_ITAGS = {140, 139};

    private static final long CHUNK = 6L * 1024 * 1024;

    private record Stream(int itag, String url, String mime) {}

    private static String userAgent = "Mozilla/5.0";

    public static void download(Activity activity, String videoId) {
        Utils.runOnMainThread(() -> new android.app.AlertDialog.Builder(activity)
                .setTitle("Download in app")
                .setItems(LABELS, (d, which) -> start(activity, videoId, which))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private static void start(Context context, String videoId, int choice) {
        Utils.showToastShort("Preparing download...");
        Utils.runOnBackgroundThread(() -> {
            try {
                run(context, videoId, choice);
            } catch (Exception ex) {
                Logger.printException(() -> "In-app download failure", ex);
                Utils.runOnMainThread(() -> Utils.showToastLong("Download failed: " + ex.getMessage()));
            }
        });
    }

    private static void run(Context context, String videoId, int choice) throws Exception {
        StreamingDataRequest.StreamData data = StreamingDataRequest
                .fetchRequestForDownload(videoId, CLIENTS, CLIENTS.get(0)).getStream();
        if (data == null) throw new Exception("no streams available for this video");
        String ua = StreamingDataRequest.getLastSpoofedClientUserAgent();
        if (ua != null) userAgent = ua;

        List<Stream> streams = readStreams(data.streamingData());
        if (streams.isEmpty()) throw new Exception("no direct stream urls");

        Stream audio = null;
        for (int itag : AUDIO_ITAGS) {
            audio = find(streams, itag);
            if (audio != null) break;
        }

        String name = "YT_" + videoId + "_" + System.currentTimeMillis();
        File cache = context.getCacheDir();

        if (choice == 4) {
            if (audio == null) throw new Exception("no audio stream found");
            File a = new File(cache, name + ".m4a");
            downloadFile(audio.url, a, "Audio");
            saveToMediaStore(context, a, name + ".m4a", "audio/mp4", false);
            a.delete();
            Utils.runOnMainThread(() -> Utils.showToastLong("Saved in Music/Youtube M.H"));
            return;
        }

        Stream video = null;
        for (int i = choice; i < VIDEO_ITAGS.length && video == null; i++) {
            video = find(streams, VIDEO_ITAGS[i]);
        }
        if (video == null) throw new Exception("no MP4 video stream found");
        if (audio == null) throw new Exception("no audio stream found");

        File v = new File(cache, name + "_v.mp4");
        File a = new File(cache, name + "_a.m4a");
        File out = new File(cache, name + ".mp4");
        try {
            downloadFile(video.url, v, "Video");
            downloadFile(audio.url, a, "Audio");
            Utils.runOnMainThread(() -> Utils.showToastShort("Joining video and audio..."));
            mux(v, a, out);
            saveToMediaStore(context, out, name + ".mp4", "video/mp4", true);
            Utils.runOnMainThread(() -> Utils.showToastLong("Saved in Movies/Youtube M.H"));
        } finally {
            v.delete();
            a.delete();
            out.delete();
        }
    }

    @androidx.annotation.Nullable
    private static Stream find(List<Stream> streams, int itag) {
        for (Stream s : streams) if (s.itag == itag) return s;
        return null;
    }

    // region Protobuf reading (only what is needed to read the stream list).

    private record Field(int number, int wire, long varint, byte[] data) {}

    private static List<Field> readMessage(byte[] b) {
        List<Field> fields = new ArrayList<>();
        int[] pos = {0};
        try {
            while (pos[0] < b.length) {
                long key = readVarint(b, pos);
                int number = (int) (key >>> 3);
                int wire = (int) (key & 7);
                switch (wire) {
                    case 0 -> fields.add(new Field(number, 0, readVarint(b, pos), null));
                    case 1 -> pos[0] += 8;
                    case 5 -> pos[0] += 4;
                    case 2 -> {
                        int len = (int) readVarint(b, pos);
                        byte[] d = new byte[len];
                        System.arraycopy(b, pos[0], d, 0, len);
                        pos[0] += len;
                        fields.add(new Field(number, 2, 0, d));
                    }
                    default -> { return fields; }
                }
            }
        } catch (Exception ignored) {
            // Truncated or not a message: return what was read.
        }
        return fields;
    }

    private static long readVarint(byte[] b, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            byte x = b[pos[0]++];
            result |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return result;
            shift += 7;
        }
    }

    /** Reads the formats of the streaming data (field 4) of a player response. */
    private static List<Stream> readStreams(byte[] playerResponse) {
        List<Stream> list = new ArrayList<>();
        for (Field f : readMessage(playerResponse)) {
            if (f.number != 4 || f.wire != 2) continue;
            for (Field fmt : readMessage(f.data)) {
                // 2 = formats, 3 = adaptive formats.
                if ((fmt.number != 3 && fmt.number != 2) || fmt.wire != 2) continue;
                int itag = 0;
                String url = null;
                String mime = null;
                for (Field x : readMessage(fmt.data)) {
                    if (x.number == 1 && x.wire == 0) itag = (int) x.varint;
                    else if (x.number == 2 && x.wire == 2) url = new String(x.data, StandardCharsets.UTF_8);
                    else if (x.number == 5 && x.wire == 2) mime = new String(x.data, StandardCharsets.UTF_8);
                }
                if (itag != 0 && url != null && url.startsWith("http")) list.add(new Stream(itag, url, mime));
            }
        }
        return list;
    }

    // endregion

    private static void downloadFile(String url, File out, String label) throws Exception {
        long pos = 0;
        long total = -1;
        int lastPercent = -25;
        try (OutputStream os = new FileOutputStream(out)) {
            while (total < 0 || pos < total) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", userAgent);
                c.setRequestProperty("Range", "bytes=" + pos + "-" + (pos + CHUNK - 1));
                int code = c.getResponseCode();
                if (code != 200 && code != 206) throw new Exception("HTTP " + code);
                boolean ranged = code == 206;
                if (ranged) {
                    String cr = c.getHeaderField("Content-Range");
                    if (cr != null && cr.contains("/")) total = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim());
                } else {
                    total = c.getContentLengthLong() + pos;
                }
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        pos += n;
                    }
                }
                if (!ranged) break; // The whole file came in one response.
                if (total > 0) {
                    int percent = (int) (100 * pos / total);
                    if (percent - lastPercent >= 25) {
                        lastPercent = percent;
                        final int shown = percent;
                        Utils.runOnMainThread(() -> Utils.showToastShort(label + " " + shown + "%"));
                    }
                }
                if (total < 0) throw new Exception("unknown size");
            }
        }
    }

    private static void mux(File video, File audio, File out) throws Exception {
        MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaExtractor vx = new MediaExtractor();
        MediaExtractor ax = new MediaExtractor();
        try {
            vx.setDataSource(video.getAbsolutePath());
            ax.setDataSource(audio.getAbsolutePath());
            int vTrack = selectTrack(vx, "video/");
            int aTrack = selectTrack(ax, "audio/");
            int outV = muxer.addTrack(vx.getTrackFormat(vTrack));
            int outA = muxer.addTrack(ax.getTrackFormat(aTrack));
            vx.selectTrack(vTrack);
            ax.selectTrack(aTrack);
            muxer.start();
            copySamples(vx, muxer, outV);
            copySamples(ax, muxer, outA);
            muxer.stop();
        } finally {
            vx.release();
            ax.release();
            muxer.release();
        }
    }

    private static int selectTrack(MediaExtractor x, String prefix) throws Exception {
        for (int i = 0; i < x.getTrackCount(); i++) {
            MediaFormat f = x.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        throw new Exception("no " + prefix + " track");
    }

    private static void copySamples(MediaExtractor x, MediaMuxer muxer, int track) {
        ByteBuffer buffer = ByteBuffer.allocate(2 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            buffer.clear();
            int size = x.readSampleData(buffer, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = x.getSampleTime();
            info.flags = (x.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            muxer.writeSampleData(track, buffer, info);
            x.advance();
        }
    }

    private static void saveToMediaStore(Context context, File file, String fileName,
                                         String mime, boolean video) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_MUSIC) + "/Youtube M.H");
        Uri collection = video
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new Exception("could not create the file");
        try (OutputStream os = resolver.openOutputStream(uri);
             InputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private InAppDownloader() {}
}
