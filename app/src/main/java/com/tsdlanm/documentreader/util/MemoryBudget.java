package com.tsdlanm.documentreader.util;

import android.app.ActivityManager;
import android.content.Context;
import android.util.DisplayMetrics;

/**
 * Sizes the bitmap caches for the device. Bitmap pixels live outside the Java heap, so the heap
 * limit alone overstates what a 1-2 GB phone can spare; total RAM decides how much to keep.
 */
public final class MemoryBudget {
    private static float share = -1f;

    private MemoryBudget() {
    }

    /**
     * {@code maxKb} scaled down on low-RAM devices and capped at {@code 1/heapDivisor} of the heap,
     * but never below {@code screens} screen-sized bitmaps of {@code bytesPerPixel}: a cache that
     * cannot hold the visible pages and their neighbours evicts them as fast as they are drawn.
     */
    public static int kb(Context context, int maxKb, int heapDivisor, float screens, int bytesPerPixel) {
        long heapKb = Runtime.getRuntime().maxMemory() / 1024 / heapDivisor;
        long kb = Math.min(heapKb, (long) (maxKb * share(context)));
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        long floorKb = (long) (screens * dm.widthPixels * dm.heightPixels * bytesPerPixel / 1024);
        return (int) Math.max(Math.max(1024, floorKb), kb);
    }

    /** True on phones with about 2 GB of RAM or less. */
    public static boolean low(Context context) {
        return share(context) < 1f;
    }

    private static synchronized float share(Context context) {
        if (share < 0) {
            ActivityManager am = (ActivityManager) context.getApplicationContext()
                    .getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            long mb = info.totalMem / (1024 * 1024);
            if (am.isLowRamDevice() || mb <= 1536) share = 0.25f;
            else if (mb <= 2560) share = 0.5f;
            else share = 1f;
        }
        return share;
    }
}
