package com.tsdlanm.documentreader.docx;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.text.LineBreaker;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.UnderlineSpan;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tsdlanm.documentreader.docx.DocModel.Block;
import com.tsdlanm.documentreader.docx.DocModel.Cell;
import com.tsdlanm.documentreader.docx.DocModel.Image;
import com.tsdlanm.documentreader.docx.DocModel.Paragraph;
import com.tsdlanm.documentreader.docx.DocModel.Run;
import com.tsdlanm.documentreader.docx.DocModel.Table;
import com.tsdlanm.documentreader.util.Tasks;

import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Shows document blocks in a recycling ListView, so even very long documents only create views
 * for what is on screen. Images are decoded in the background at the size they are shown.
 */
public final class DocxAdapter extends BaseAdapter {
    private static final int TYPE_PARAGRAPH = 0, TYPE_IMAGE = 1, TYPE_TABLE = 2;
    private static final float BASE_TEXT_SP = 15f;
    private static final int TEXT_COLOR = 0xFF212121;

    private final Context context;
    private final List<Block> blocks;
    private final float defaultPoints;
    private final ZipFile zip;
    private final float density;
    private final int contentWidth;
    private final LruCache<String, Bitmap> images;
    private float textScale = 1f;

    public DocxAdapter(Context context, DocModel.Document doc, ZipFile zip, int contentWidthPx) {
        this.context = context;
        this.blocks = doc.blocks;
        this.defaultPoints = Math.max(1, doc.defaultHalfPoints / 2f);
        this.zip = zip;
        this.density = context.getResources().getDisplayMetrics().density;
        this.contentWidth = contentWidthPx;
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        images = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
    }

    public void setTextScale(float scale) {
        textScale = Math.max(0.6f, Math.min(2.5f, scale));
        notifyDataSetChanged();
    }

    public float getTextScale() {
        return textScale;
    }

    public void release() {
        images.evictAll();
    }

    @Override
    public int getCount() {
        return blocks.size();
    }

    @Override
    public Object getItem(int position) {
        return blocks.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 3;
    }

    @Override
    public int getItemViewType(int position) {
        Block b = blocks.get(position);
        if (b instanceof Image) return TYPE_IMAGE;
        if (b instanceof Table) return TYPE_TABLE;
        return TYPE_PARAGRAPH;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        return false;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Block b = blocks.get(position);
        if (b instanceof Image) {
            ImageView iv = convertView instanceof ImageView ? (ImageView) convertView : newImageView();
            bindImage(iv, (Image) b, contentWidth);
            return iv;
        }
        if (b instanceof Table) {
            HorizontalScrollView hs = convertView instanceof HorizontalScrollView
                    ? (HorizontalScrollView) convertView : newTableContainer();
            hs.removeAllViews();
            hs.addView(buildTable((Table) b));
            hs.scrollTo(0, 0);
            return hs;
        }
        TextView tv = convertView instanceof TextView ? (TextView) convertView : newTextView();
        bindParagraph(tv, (Paragraph) b, 1f, true);
        return tv;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ------------------------------------------------------------ paragraphs

    private TextView newTextView() {
        TextView tv = new TextView(context);
        tv.setTextColor(TEXT_COLOR);
        tv.setLineSpacing(0, 1.15f);
        tv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    private void bindParagraph(TextView tv, Paragraph p, float cellScale, boolean topLevel) {
        tv.setText(buildText(p));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SP * textScale * cellScale);
        int gravity;
        switch (p.align) {
            case DocModel.ALIGN_CENTER:
                gravity = Gravity.CENTER_HORIZONTAL;
                break;
            case DocModel.ALIGN_END:
                gravity = Gravity.END;
                break;
            default:
                gravity = Gravity.START;
        }
        tv.setGravity(gravity);
        if (Build.VERSION.SDK_INT >= 26) {
            Justify.set(tv, p.align == DocModel.ALIGN_JUSTIFY);
        }
        // Twips to dp, scaled down for small screens and capped.
        int indent = Math.min(dp(96), dp(p.indent / 1440f * 96f * 0.5f));
        int top = topLevel ? (p.heading > 0 ? dp(10) : dp(2)) : 0;
        int bottom = topLevel ? dp(p.heading > 0 ? 6 : 4) : dp(2);
        tv.setPadding(indent, top, 0, bottom);
    }

    private CharSequence buildText(Paragraph p) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (p.listPrefix != null) sb.append(p.listPrefix).append("  ");
        for (Run r : p.runs) {
            int start = sb.length();
            sb.append(r.text);
            int end = sb.length();
            if (start == end) continue;
            int flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
            boolean bold = r.bold || (p.heading > 0 && r.halfPoints == 0);
            if (bold && r.italic) sb.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), start, end, flags);
            else if (bold) sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, flags);
            else if (r.italic) sb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, flags);
            if (r.underline) sb.setSpan(new UnderlineSpan(), start, end, flags);
            if (r.strike) sb.setSpan(new StrikethroughSpan(), start, end, flags);
            float size = relativeSize(r, p.heading);
            if (Math.abs(size - 1f) > 0.02f) sb.setSpan(new RelativeSizeSpan(size), start, end, flags);
            if (r.color != 0 && !isNearWhite(r.color)) {
                sb.setSpan(new ForegroundColorSpan(r.color), start, end, flags);
            }
            if (r.background != 0) sb.setSpan(new BackgroundColorSpan(r.background), start, end, flags);
            if (r.vertAlign == DocModel.VALIGN_SUPER) {
                sb.setSpan(new SuperscriptSpan(), start, end, flags);
                sb.setSpan(new RelativeSizeSpan(0.7f), start, end, flags);
            } else if (r.vertAlign == DocModel.VALIGN_SUB) {
                sb.setSpan(new SubscriptSpan(), start, end, flags);
                sb.setSpan(new RelativeSizeSpan(0.7f), start, end, flags);
            }
        }
        return sb;
    }

    private float relativeSize(Run r, int heading) {
        float pt;
        if (r.halfPoints > 0) pt = r.halfPoints / 2f;
        else if (heading > 0) pt = defaultPoints * (heading == 1 ? 1.6f : heading == 2 ? 1.35f : 1.15f);
        else pt = defaultPoints;
        // Compress the range a little: huge title fonts are too big on a phone.
        float rel = pt / defaultPoints;
        if (rel > 1f) rel = 1f + (rel - 1f) * 0.75f;
        return Math.max(0.6f, Math.min(2.4f, rel));
    }

    /** White text is only readable on its original coloured background, which may be missing. */
    private static boolean isNearWhite(int c) {
        return ((c >> 16) & 0xFF) > 235 && ((c >> 8) & 0xFF) > 235 && (c & 0xFF) > 235;
    }

    // ---------------------------------------------------------------- images

    private ImageView newImageView() {
        ImageView iv = new ImageView(context);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(0, dp(4), 0, dp(4));
        iv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
        return iv;
    }

    private void bindImage(final ImageView iv, final Image img, int maxWidth) {
        // Reserve the right height from the document's size hint so the list does not jump.
        int w = maxWidth;
        int h = dp(120);
        if (img.widthEmu > 0 && img.heightEmu > 0) {
            float wDp = img.widthEmu / 914400f * 96f;
            w = Math.min(maxWidth, dp(wDp));
            h = Math.round(w * (img.heightEmu / (float) img.widthEmu));
            h = Math.min(h, dp(1200));
        }
        ViewGroup.LayoutParams lp = iv.getLayoutParams();
        if (lp == null) lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
        lp.height = h + iv.getPaddingTop() + iv.getPaddingBottom();
        iv.setLayoutParams(lp);

        final String key = img.part + "@" + w;
        iv.setTag(key);
        Bitmap cached = images.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageDrawable(null);
        final int targetW = w;
        Tasks.io().execute(() -> {
            final Bitmap bmp = decode(img.part, targetW);
            Tasks.main(() -> {
                if (bmp == null) return;
                images.put(key, bmp);
                if (key.equals(iv.getTag())) iv.setImageBitmap(bmp);
            });
        });
    }

    /** Decodes with a sample size so the bitmap is about as wide as it is shown. */
    private Bitmap decode(String part, int targetW) {
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
            // EMF/WMF and corrupt images cannot be decoded; leave them out.
            return null;
        }
    }

    // ---------------------------------------------------------------- tables

    private HorizontalScrollView newTableContainer() {
        HorizontalScrollView hs = new HorizontalScrollView(context);
        hs.setHorizontalScrollBarEnabled(true);
        hs.setFillViewport(false);
        hs.setPadding(0, dp(4), 0, dp(8));
        hs.setClipToPadding(false);
        hs.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return hs;
    }

    private View buildTable(Table t) {
        int cols = Math.max(1, t.columnCount());
        int[] widths = columnWidths(t, cols);

        LinearLayout table = new LinearLayout(context);
        table.setOrientation(LinearLayout.VERTICAL);
        int line = Math.max(1, Math.round(density));
        for (List<Cell> row : t.rows) {
            LinearLayout tr = new LinearLayout(context);
            tr.setOrientation(LinearLayout.HORIZONTAL);
            tr.setBaselineAligned(false);
            int col = 0;
            for (Cell c : row) {
                int span = Math.min(c.span, cols - col);
                if (span <= 0) break;
                int w = 0;
                for (int k = col; k < col + span; k++) w += widths[k];
                col += span;
                tr.addView(buildCell(c, w, line), new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            table.addView(tr, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return table;
    }

    /** Proportional to the document's grid, fitted to the screen; scrolls only if unavoidable. */
    private int[] columnWidths(Table t, int cols) {
        int[] widths = new int[cols];
        float[] weights = new float[cols];
        float total = 0;
        for (int i = 0; i < cols; i++) {
            float w = i < t.grid.size() ? t.grid.get(i) : 0;
            weights[i] = w > 0 ? w : 1440;
            total += weights[i];
        }
        int min = dp(56 * Math.min(1.5f, textScale));
        int sum = 0;
        for (int i = 0; i < cols; i++) {
            widths[i] = Math.max(min, Math.round(contentWidth * weights[i] / total));
            sum += widths[i];
        }
        if (sum > contentWidth) {
            // Shrink the wide columns back toward the screen width where possible.
            float over = sum - contentWidth;
            float shrinkable = 0;
            for (int w : widths) shrinkable += Math.max(0, w - min);
            if (shrinkable > 0) {
                float f = Math.min(1f, over / shrinkable);
                for (int i = 0; i < cols; i++) widths[i] -= Math.round(Math.max(0, widths[i] - min) * f);
            }
        }
        return widths;
    }

    private View buildCell(Cell c, int width, int line) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(4);
        cell.setPadding(pad, pad / 2, pad, pad / 2);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c.background != 0 ? c.background : Color.WHITE);
        bg.setStroke(line, 0xFFBDBDBD);
        cell.setBackground(bg);
        if (c.mergedContinue) return cell;
        int inner = Math.max(1, width - 2 * pad);
        for (Block b : c.blocks) {
            if (b instanceof Paragraph) {
                TextView tv = newTextView();
                bindParagraph(tv, (Paragraph) b, 0.9f, false);
                cell.addView(tv);
            } else if (b instanceof Image) {
                ImageView iv = newImageView();
                cell.addView(iv);
                bindImage(iv, (Image) b, inner);
            }
        }
        return cell;
    }

    /** Wraps the API 26 call so older devices never load it. */
    @TargetApi(26)
    private static final class Justify {
        static void set(TextView tv, boolean on) {
            // LineBreaker constants are compile-time ints, identical to the API 26 Layout ones.
            tv.setJustificationMode(on ? LineBreaker.JUSTIFICATION_MODE_INTER_WORD : LineBreaker.JUSTIFICATION_MODE_NONE);
        }
    }
}
