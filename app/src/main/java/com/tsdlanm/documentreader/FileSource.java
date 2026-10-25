package com.tsdlanm.documentreader;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Access to the document behind a content:// or file:// URI. */
final class FileSource {
    private FileSource() {
    }

    static String displayName(Context ctx, Uri uri) {
        if ("file".equals(uri.getScheme())) {
            String p = uri.getLastPathSegment();
            return p == null ? "" : p;
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (RuntimeException ignored) {
            // Some providers throw for unsupported columns.
        } finally {
            if (c != null) c.close();
        }
        String last = uri.getLastPathSegment();
        if (last == null) return "";
        int slash = last.lastIndexOf('/');
        return slash >= 0 ? last.substring(slash + 1) : last;
    }

    static int readHead(ContentResolver cr, Uri uri, byte[] buf) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("Cannot open");
        try {
            int total = 0;
            while (total < buf.length) {
                int n = in.read(buf, total, buf.length - total);
                if (n < 0) break;
                total += n;
            }
            return total;
        } finally {
            in.close();
        }
    }

    /** Local file for the URI: the file itself for file://, otherwise a copy in the cache. */
    static File localFile(Context ctx, Uri uri) throws IOException {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            if (f.canRead()) return f;
        }
        File dir = new File(ctx.getCacheDir(), "open");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No cache dir");
        File out = new File(dir, "doc_" + Integer.toHexString(uri.toString().hashCode()) + "_" + System.nanoTime());
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Cannot open");
        OutputStream os = new FileOutputStream(out);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } catch (IOException e) {
            os.close();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        } finally {
            in.close();
            os.close();
        }
        return out;
    }

    static boolean isCacheCopy(Context ctx, File f) {
        File dir = new File(ctx.getCacheDir(), "open");
        return dir.equals(f.getParentFile());
    }

    /** Removes copies left behind by earlier sessions (e.g. after a crash). */
    static void clearCache(Context ctx, File keep) {
        File[] files = new File(ctx.getCacheDir(), "open").listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.equals(keep)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
