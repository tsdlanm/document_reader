package com.tsdlanm.documentreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/** Small most-recently-opened list kept in SharedPreferences. */
final class RecentFiles {
    private static final String PREFS = "recent";
    private static final String KEY = "items";
    private static final int MAX = 30;

    static final class Item {
        final Uri uri;
        final String name;
        final long time;

        Item(Uri uri, String name, long time) {
            this.uri = uri;
            this.name = name;
            this.time = time;
        }
    }

    private RecentFiles() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<Item> load(Context ctx) {
        List<Item> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            String[] f = line.split("\t", 3);
            if (f.length < 3) continue;
            try {
                out.add(new Item(Uri.parse(f[2]), f[1], Long.parseLong(f[0])));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static void save(Context ctx, List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size() && i < MAX; i++) {
            Item it = items.get(i);
            if (sb.length() > 0) sb.append('\n');
            sb.append(it.time).append('\t').append(clean(it.name)).append('\t').append(it.uri);
        }
        prefs(ctx).edit().putString(KEY, sb.toString()).apply();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    static void add(Context ctx, Uri uri, String name) {
        List<Item> items = load(ctx);
        remove(items, uri);
        items.add(0, new Item(uri, name, System.currentTimeMillis()));
        save(ctx, items);
    }

    static void remove(Context ctx, Uri uri) {
        List<Item> items = load(ctx);
        if (remove(items, uri)) save(ctx, items);
    }

    static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY).apply();
    }

    private static boolean remove(List<Item> items, Uri uri) {
        boolean changed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).uri.equals(uri)) {
                items.remove(i);
                changed = true;
            }
        }
        return changed;
    }
}
