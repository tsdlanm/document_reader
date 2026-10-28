package com.tsdlanm.documentreader.docx;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.FontRequest;
import android.provider.FontsContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Free fonts with the same metrics as the Office fonts (Carlito for Calibri, Tinos for Times New
 * Roman, ...), as Google Docs and Sheets use them. They come from the Google Play services font
 * provider, which downloads each once and shares it between apps, so the APK stays small. Until
 * they arrive (or without Play services, or before Android 8) the phone fonts, stretched to the
 * Office widths, stand in.
 */
final class FontDownloads {
    /** Office font (lowercase part of the name) and its metric-compatible replacement. */
    private static final String[][] REPLACEMENTS = {
            {"calibri", "Carlito"}, {"cambria", "Caladea"}, {"times", "Tinos"},
            {"arial", "Arimo"}, {"helvetica", "Arimo"}, {"courier", "Cousine"},
    };
    private static final String AUTHORITY = "com.google.android.gms.fonts";
    private static final String PROVIDER = "com.google.android.gms";

    /** Keyed by replacement family and style (0 regular, 1 bold, 2 italic, 3 bold italic). */
    private static final Map<String, Typeface> FONTS = new ConcurrentHashMap<>();
    private static final List<Runnable> LISTENERS = new ArrayList<>();
    private static final List<Runnable> WAITING = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean started;
    /** Requests not answered yet. */
    private static int pending;

    private FontDownloads() {
    }

    /** The downloaded replacement for a document font, or null. */
    static Typeface get(String font, boolean bold, boolean italic) {
        String family = replacement(font);
        return family == null ? null : FONTS.get(family + style(bold, italic));
    }

    /** True when {@code font} is drawn in a downloaded font with the original's widths. */
    static boolean exact(String font, boolean bold, boolean italic) {
        return get(font, bold, italic) != null;
    }

    private static String replacement(String font) {
        if (font == null) return null;
        String n = font.toLowerCase(Locale.ROOT);
        // "Calibri Light" has its own metrics; Carlito would be too heavy and wide.
        if (n.contains("light")) return null;
        for (String[] r : REPLACEMENTS) if (n.contains(r[0])) return r[1];
        return null;
    }

    private static int style(boolean bold, boolean italic) {
        return (bold ? 1 : 0) | (italic ? 2 : 0);
    }

    /** Runs {@code r} on the main thread whenever another font arrives. */
    static synchronized void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    static synchronized void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    /** Starts fetching all replacement fonts (once per process); cached ones arrive within moments. */
    static synchronized void prefetch(Context context) {
        if (started || Build.VERSION.SDK_INT < 26) return;
        started = true;
        List<List<byte[]>> certs = providerCertificates(context.getApplicationContext());
        if (certs == null) return;
        HandlerThread thread = new HandlerThread("fonts");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        java.util.Set<String> families = new java.util.LinkedHashSet<>();
        for (String[] r : REPLACEMENTS) families.add(r[1]);
        pending = families.size() * 4;
        for (String family : families) {
            for (int s = 0; s < 4; s++) {
                final String key = family + s;
                String query = "name=" + family + "&weight=" + ((s & 1) != 0 ? 700 : 400)
                        + "&italic=" + ((s & 2) != 0 ? 1 : 0);
                FontRequest request = new FontRequest(AUTHORITY, PROVIDER, query, certs);
                FontsContract.requestFonts(context.getApplicationContext(), request, handler, null,
                        new FontsContract.FontRequestCallback() {
                            @Override
                            public void onTypefaceRetrieved(Typeface typeface) {
                                FONTS.put(key, typeface);
                                answered();
                            }

                            @Override
                            public void onTypefaceRequestFailed(int reason) {
                                // Not available: the phone font keeps standing in.
                                answered();
                            }
                        });
            }
        }
    }

    private static final Runnable CHANGED = FontDownloads::changed;

    /** One request answered (font thread): coalesce the change notices, release waiters at the end. */
    private static void answered() {
        boolean done;
        synchronized (FontDownloads.class) {
            done = --pending <= 0;
        }
        MAIN.removeCallbacks(CHANGED);
        MAIN.postDelayed(CHANGED, done ? 0 : 150);
        if (done) MAIN.post(FontDownloads::release);
    }

    private static void changed() {
        PageLayout.Fonts.reset();
        List<Runnable> copy;
        synchronized (FontDownloads.class) {
            copy = new ArrayList<>(LISTENERS);
        }
        for (Runnable r : copy) r.run();
    }

    /**
     * Runs {@code r} on the main thread once the fonts have been fetched (usually at once, from the
     * cache), or after {@code timeoutMs} at the latest. Laying a document out after that keeps one
     * font throughout instead of switching midway.
     */
    static void whenReady(Runnable r, long timeoutMs) {
        synchronized (FontDownloads.class) {
            if (started && pending > 0) {
                WAITING.add(r);
                MAIN.postDelayed(() -> {
                    boolean waiting;
                    synchronized (FontDownloads.class) {
                        waiting = WAITING.remove(r);
                    }
                    if (waiting) r.run();
                }, timeoutMs);
                return;
            }
        }
        MAIN.post(r);
    }

    private static void release() {
        List<Runnable> copy;
        synchronized (FontDownloads.class) {
            copy = new ArrayList<>(WAITING);
            WAITING.clear();
        }
        for (Runnable r : copy) r.run();
    }

    /**
     * The signing certificates of the font provider. Only a provider installed with the system
     * (Play services) is trusted; its own certificates then identify it to FontsContract.
     */
    @SuppressWarnings("deprecation")
    private static List<List<byte[]>> providerCertificates(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(PROVIDER, 0);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) return null;
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(PROVIDER, PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = pi.signingInfo == null ? null : pi.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo pi = pm.getPackageInfo(PROVIDER, PackageManager.GET_SIGNATURES);
                sigs = pi.signatures;
            }
            if (sigs == null || sigs.length == 0) return null;
            List<byte[]> set = new ArrayList<>();
            for (Signature s : sigs) set.add(s.toByteArray());
            return Collections.singletonList(set);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }
}
