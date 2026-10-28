package com.tsdlanm.documentreader.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Decodes pictures stored in a document's zip at about the size they are shown. */
public final class ZipImages {
    private ZipImages() {
    }

    /** Returns null for missing, unsupported (EMF/WMF) or corrupt images. */
    public static Bitmap decode(ZipFile zip, String part, int targetW) {
        try {
            ZipEntry e = zip.getEntry(part);
            if (e == null || e.getSize() > 30L * 1024 * 1024) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = zip.getInputStream(e);
            try {
                BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetW) sample *= 2;
            // Also keep tall images within a sane pixel budget.
            while ((long) (o.outWidth / sample) * (o.outHeight / sample) > 2_500_000L) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.RGB_565;
            in = zip.getInputStream(e);
            try {
                return BitmapFactory.decodeStream(in, null, d);
            } finally {
                in.close();
            }
        } catch (Exception | OutOfMemoryError ex) {
            return null;
        }
    }
}
