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

    /** Returns null for missing, unsupported or corrupt images. EMF and WMF are drawn. */
    public static Bitmap decode(ZipFile zip, String part, int targetW) {
        try {
            ZipEntry e = zip.getEntry(part);
            if (e == null || e.getSize() > 30L * 1024 * 1024) return null;
            String lower = part.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".emf") || lower.endsWith(".wmf") || lower.endsWith(".emz") || lower.endsWith(".wmz")) {
                return metafile(zip, e, lower.endsWith("z"), targetW);
            }
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
            // Half the memory for photos; pictures that may be see-through (PNG watermarks, logos)
            // keep their alpha.
            String mime = o.outMimeType;
            if (mime != null && mime.contains("jpeg")) d.inPreferredConfig = Bitmap.Config.RGB_565;
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

    /** A Windows metafile, read whole (they are small) and drawn by {@link Metafile}. */
    private static Bitmap metafile(ZipFile zip, ZipEntry e, boolean gzipped, int targetW) throws java.io.IOException {
        InputStream in = zip.getInputStream(e);
        try {
            if (gzipped) in = new java.util.zip.GZIPInputStream(in);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                    (int) Math.max(1024, Math.min(e.getSize(), 8L * 1024 * 1024)));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 30 * 1024 * 1024) return null;
            }
            return Metafile.render(out.toByteArray(), targetW);
        } finally {
            in.close();
        }
    }
}
