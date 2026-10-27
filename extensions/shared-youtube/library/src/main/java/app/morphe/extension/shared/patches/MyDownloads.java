package app.morphe.extension.shared.patches;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Lists the files that the in-app downloader saved in the "Youtube M.H" folders, and plays them.
 */
@SuppressWarnings("unused")
public final class MyDownloads {

    private record Item(String name, Uri uri, String mime) {}

    public static void show(Activity activity) {
        try {
            List<Item> items = new ArrayList<>();
            query(activity.getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI, items);
            query(activity.getContentResolver(), MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, items);

            if (items.isEmpty()) {
                Utils.showToastLong("No downloads yet");
                return;
            }
            String[] names = new String[items.size()];
            for (int i = 0; i < names.length; i++) names[i] = items.get(i).name;

            new AlertDialog.Builder(activity)
                    .setTitle("My downloads (" + names.length + ")")
                    .setItems(names, (d, which) -> play(activity, items.get(which)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Exception ex) {
            Logger.printException(() -> "MyDownloads show failure", ex);
            Utils.showToastLong("Could not list downloads");
        }
    }

    private static void query(ContentResolver resolver, Uri collection, List<Item> out) {
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE
        };
        try (Cursor c = resolver.query(collection, projection,
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", new String[]{"%Youtube M.H%"},
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c == null) return;
            while (c.moveToNext()) {
                out.add(new Item(c.getString(1),
                        ContentUris.withAppendedId(collection, c.getLong(0)), c.getString(2)));
            }
        }
    }

    private static void play(Activity activity, Item item) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(item.uri, item.mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "MyDownloads play failure", ex);
            Utils.showToastLong("No app found to play this file");
        }
    }

    private MyDownloads() {}
}
