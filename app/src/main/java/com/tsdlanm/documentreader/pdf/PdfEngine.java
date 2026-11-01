package com.tsdlanm.documentreader.pdf;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import com.tsdlanm.documentreader.util.Tasks;

import java.io.IOException;

/**
 * Wraps the platform {@link PdfRenderer} (no bundled PDF library) on one dedicated thread, since
 * the renderer can only work on one page at a time.
 */
public final class PdfEngine {
    public interface PageCallback {
        /** Called on the main thread; {@code bitmap} is null when the render was skipped or failed. */
        void onPage(int index, Bitmap bitmap);
    }

    public interface Wanted {
        /** Called on the render thread to drop requests for pages that scrolled away. */
        boolean isWanted(int index);
    }

    public interface TileWanted {
        /** Called on the render thread to drop tiles that scrolled away or are of an old zoom. */
        boolean isWanted(Tile tile);
    }

    public interface TileCallback {
        /** Called on the main thread; {@code bitmap} is null when the render was skipped or failed. */
        void onTile(Tile tile, Bitmap bitmap);
    }

    /**
     * A piece of a page at one zoom: {@code w x h} pixels starting at {@code x, y} in the page as
     * drawn at {@code scale} pixels per point.
     */
    public static final class Tile {
        public final int page;
        public final long key;
        public final int x, y, w, h;
        public final float scale, zoom;
        /** Where the tile lies in the view's content at {@code zoom}, for the wanted check. */
        public final float left, top;

        public Tile(int page, long key, int x, int y, int w, int h, float scale, float zoom, float left, float top) {
            this.page = page;
            this.key = key;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.scale = scale;
            this.zoom = zoom;
            this.left = left;
            this.top = top;
        }
    }

    public final int pageCount;
    /**
     * Page sizes in points; only read and written on the main thread. Until {@link #measureRest}
     * has run, every page is assumed to be as large as the first.
     */
    public final float[] widths, heights;

    private final ParcelFileDescriptor pfd;
    private final PdfRenderer renderer;
    private final HandlerThread thread;
    private final Handler handler;
    private volatile boolean closed;
    /** Whether {@link #measureRest} has finished; main thread only. */
    private boolean measured;
    /**
     * The last page opened, kept open (render thread only): the tiles of a page come one after
     * another, and opening a page parses all of it again.
     */
    private PdfRenderer.Page openPage;
    private int openIndex = -1;

    private PdfEngine(ParcelFileDescriptor pfd, PdfRenderer renderer) {
        this.pfd = pfd;
        this.renderer = renderer;
        pageCount = renderer.getPageCount();
        widths = new float[pageCount];
        heights = new float[pageCount];
        // Opening a page parses it, which for scanned or image-heavy pages takes long: only the
        // first is measured before the document shows, the rest by measureRest().
        if (pageCount > 0) {
            PdfRenderer.Page page = renderer.openPage(0);
            java.util.Arrays.fill(widths, Math.max(1, page.getWidth()));
            java.util.Arrays.fill(heights, Math.max(1, page.getHeight()));
            page.close();
        }
        thread = new HandlerThread("pdf", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** Render thread: the page, reusing the open one. Only one page may be open at a time. */
    private PdfRenderer.Page page(int index) {
        if (openIndex == index) return openPage;
        closePage();
        openPage = renderer.openPage(index);
        openIndex = index;
        return openPage;
    }

    private void closePage() {
        if (openPage == null) return;
        try {
            openPage.close();
        } catch (RuntimeException ignored) {
        }
        openPage = null;
        openIndex = -1;
    }

    /**
     * Measures the other pages on the render thread, one page per message so that page renders
     * queued meanwhile are not held up. {@code changed} runs on the main thread, after the sizes
     * are updated, if some page differs from the assumed size.
     */
    public void measureRest(final Runnable changed) {
        if (pageCount < 2) {
            measured = true;
            return;
        }
        final float w0 = widths[0], h0 = heights[0];
        final float[] w = new float[pageCount], h = new float[pageCount];
        handler.post(new Runnable() {
            int next = 1;
            boolean differs;

            @Override
            public void run() {
                if (closed) return;
                try {
                    PdfRenderer.Page page = page(next);
                    w[next] = Math.max(1, page.getWidth());
                    h[next] = Math.max(1, page.getHeight());
                } catch (RuntimeException e) {
                    closePage();
                    w[next] = w0;
                    h[next] = h0;
                }
                if (w[next] != w0 || h[next] != h0) differs = true;
                if (++next < pageCount) {
                    handler.post(this);
                } else {
                    Tasks.main(() -> {
                        if (closed) return;
                        measured = true;
                        if (!differs) return;
                        System.arraycopy(w, 1, widths, 1, pageCount - 1);
                        System.arraycopy(h, 1, heights, 1, pageCount - 1);
                        changed.run();
                    });
                }
            }
        });
    }

    /**
     * Opens the document and measures its first page. Call off the main thread. Throws
     * SecurityException for password-protected files and IOException for unreadable ones.
     */
    public static PdfEngine open(ParcelFileDescriptor pfd) throws IOException {
        PdfRenderer r = new PdfRenderer(pfd);
        try {
            return new PdfEngine(pfd, r);
        } catch (RuntimeException e) {
            r.close();
            throw e;
        }
    }

    /** {@code compact}: return an RGB_565 copy, half the memory, for low-RAM phones. */
    public void renderPage(final int index, final int w, final int h, final boolean compact,
                           final Wanted wanted, final PageCallback cb) {
        handler.post(() -> {
            Bitmap bmp = null;
            if (!closed && wanted.isWanted(index)) {
                bmp = render(index, w, h, null, compact);
            }
            final Bitmap result = bmp;
            Tasks.main(() -> cb.onPage(index, result));
        });
    }

    /** Renders one tile of a zoomed page. {@code compact} as for {@link #renderPage}. */
    public void renderTile(final Tile tile, final boolean compact, final TileWanted wanted,
                           final TileCallback cb) {
        handler.post(() -> {
            Bitmap bmp = null;
            if (!closed && wanted.isWanted(tile)) {
                Matrix m = new Matrix();
                m.setScale(tile.scale, tile.scale);
                m.postTranslate(-tile.x, -tile.y);
                bmp = render(tile.page, tile.w, tile.h, m, compact);
            }
            final Bitmap result = bmp;
            Tasks.main(() -> cb.onTile(tile, result));
        });
    }

    /** Render thread: page {@code index} drawn into a new white bitmap, or null on failure. */
    private Bitmap render(int index, int w, int h, Matrix m, boolean compact) {
        try {
            // PdfRenderer only draws into ARGB_8888.
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            try {
                page(index).render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            } catch (RuntimeException e) {
                closePage();
                bmp.recycle();
                return null;
            }
            if (compact) {
                Bitmap small = bmp.copy(Bitmap.Config.RGB_565, false);
                bmp.recycle();
                bmp = small;
            }
            return bmp;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        handler.post(() -> {
            closePage();
            try {
                renderer.close();
            } catch (RuntimeException ignored) {
            }
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
        });
        thread.quitSafely();
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isMeasured() {
        return measured;
    }
}
