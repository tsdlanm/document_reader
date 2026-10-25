package com.tsdlanm.documentreader.pdf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
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

    /** Where one page goes in a detail bitmap: its top-left corner and pixels per point. */
    public static final class Region {
        final int index;
        final float left, top, scale;

        public Region(int index, float left, float top, float scale) {
            this.index = index;
            this.left = left;
            this.top = top;
            this.scale = scale;
        }
    }

    public final int pageCount;
    /** Page sizes in points. */
    public final float[] widths, heights;

    private final ParcelFileDescriptor pfd;
    private final PdfRenderer renderer;
    private final HandlerThread thread;
    private final Handler handler;
    private volatile boolean closed;

    private PdfEngine(ParcelFileDescriptor pfd, PdfRenderer renderer) {
        this.pfd = pfd;
        this.renderer = renderer;
        pageCount = renderer.getPageCount();
        widths = new float[pageCount];
        heights = new float[pageCount];
        for (int i = 0; i < pageCount; i++) {
            PdfRenderer.Page page = renderer.openPage(i);
            widths[i] = Math.max(1, page.getWidth());
            heights[i] = Math.max(1, page.getHeight());
            page.close();
        }
        thread = new HandlerThread("pdf", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /**
     * Opens the document and measures all pages. Call off the main thread. Throws
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

    public void renderPage(final int index, final int w, final int h, final Wanted wanted, final PageCallback cb) {
        handler.post(() -> {
            Bitmap bmp = null;
            if (!closed && wanted.isWanted(index)) {
                PdfRenderer.Page page = null;
                try {
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page = renderer.openPage(index);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } catch (OutOfMemoryError | RuntimeException e) {
                    bmp = null;
                } finally {
                    if (page != null) page.close();
                }
            }
            final Bitmap result = bmp;
            Tasks.main(() -> cb.onPage(index, result));
        });
    }

    /** Renders the visible part of several pages at high resolution into {@code target}. */
    public void renderDetail(final Bitmap target, final Region[] regions, final int background,
                             final Runnable done) {
        handler.post(() -> {
            if (!closed) {
                target.eraseColor(background);
                Canvas canvas = new Canvas(target);
                Paint white = new Paint();
                white.setColor(Color.WHITE);
                Rect bounds = new Rect(0, 0, target.getWidth(), target.getHeight());
                Matrix m = new Matrix();
                Rect clip = new Rect();
                for (Region r : regions) {
                    int left = (int) Math.floor(r.left);
                    int top = (int) Math.floor(r.top);
                    clip.set(left, top, (int) Math.ceil(r.left + widths[r.index] * r.scale),
                            (int) Math.ceil(r.top + heights[r.index] * r.scale));
                    if (!clip.intersect(bounds)) continue;
                    canvas.drawRect(clip, white);
                    m.setScale(r.scale, r.scale);
                    m.postTranslate(r.left, r.top);
                    PdfRenderer.Page page = null;
                    try {
                        page = renderer.openPage(r.index);
                        page.render(target, clip, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    } catch (RuntimeException ignored) {
                        // Leave the page blank rather than crash.
                    } finally {
                        if (page != null) page.close();
                    }
                }
            }
            Tasks.main(done);
        });
    }

    public void close() {
        if (closed) return;
        closed = true;
        handler.post(() -> {
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
}
