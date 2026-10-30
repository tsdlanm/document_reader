package com.tsdlanm.documentreader.pdf;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Annotations, which {@link android.graphics.pdf.PdfRenderer} never draws: signature stamps and
 * their QR codes, filled form fields, highlights, ink, stamps and the like.
 *
 * <p>Each one carries its own picture (an appearance stream). In a copy of the file, an
 * incremental update appends a little content to each page that draws those pictures where the
 * annotations sit, so the platform renderer draws them as ordinary page content. The original
 * objects are left untouched. Links are collected along the way so they can be tapped.
 */
public final class PdfAnnotations {
    public static final class Link {
        /** Bounds as fractions of the page as shown (after its rotation). */
        public final float left, top, right, bottom;
        /** Address to open, or null for a link within the document. */
        public final String uri;
        /** Page a link within the document goes to, or -1. */
        public final int page;

        Link(float left, float top, float right, float bottom, String uri, int page) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.uri = uri;
            this.page = page;
        }
    }

    public static final class Result {
        /** Copy of the document with the annotations in the page content, or null if none are drawn. */
        public File flattened;
        /** Pages that look different in {@link #flattened}. */
        public boolean[] changed;
        /** Links of each page, null where a page has none. */
        public Link[][] links;
    }

    // Annotation flags.
    private static final int HIDDEN = 2, NO_VIEW = 32;

    private static final class Page {
        PdfFile.Ref ref;
        Map<String, Object> dict;
        Object resources;
        float[] box;
        int rotate;
    }

    private PdfAnnotations() {
    }

    /**
     * Reads the annotations of a document with {@code pageCount} pages (as PdfRenderer counts
     * them) and writes the copy to {@code out} if any are drawn. Returns null if the file cannot
     * be read this way (damaged, encrypted, or counted differently): it is then shown as is.
     */
    public static Result read(FileChannel in, int pageCount, File out) {
        try {
            PdfFile f = new PdfFile(in);
            if (f.trailer.containsKey("Encrypt")) return null;
            Map<String, Object> root = f.dict(f.trailer.get("Root"));
            if (root == null) return null;
            List<Page> pages = pages(f, root, pageCount);
            if (pages == null || pages.size() != pageCount) return null;
            Map<Integer, Integer> pageIndex = new HashMap<>();
            for (int i = 0; i < pages.size(); i++) {
                if (pages.get(i).ref != null) pageIndex.put(pages.get(i).ref.num, i);
            }

            Result r = new Result();
            r.changed = new boolean[pageCount];
            r.links = new Link[pageCount][];
            Map<Integer, Map<String, Object>> edits = new LinkedHashMap<>();
            Map<Integer, String> contents = new HashMap<>();
            for (int i = 0; i < pageCount; i++) {
                Page p = pages.get(i);
                if (p.dict == null) continue;
                List<Object> annots = f.array(p.dict.get("Annots"));
                if (annots == null) continue;
                StringBuilder draw = new StringBuilder();
                Map<String, Object> xobjects = new LinkedHashMap<>();
                List<Link> links = new ArrayList<>();
                for (Object a : annots) {
                    try {
                        annotation(f, root, p, f.dict(a), pageIndex, draw, xobjects, links);
                    } catch (IOException | RuntimeException e) {
                        // Skip one broken annotation, keep the others.
                    }
                }
                if (!links.isEmpty()) r.links[i] = links.toArray(new Link[0]);
                if (xobjects.isEmpty() || p.ref == null) continue;
                edits.put(i, rewritePage(f, p, xobjects));
                contents.put(i, draw.toString());
                r.changed[i] = true;
            }
            if (!edits.isEmpty()) {
                write(in, f, pages, edits, contents, out);
                r.flattened = out;
            }
            return r;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return null;
        }
    }

    /** All pages in order, with the attributes they inherit from the page tree. */
    private static List<Page> pages(PdfFile f, Map<String, Object> root, int expected) throws IOException {
        List<Page> pages = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Object[]> stack = new ArrayDeque<>();
        // Node, Resources, MediaBox, CropBox, Rotate.
        stack.push(new Object[]{root.get("Pages"), null, null, null, null});
        while (!stack.isEmpty()) {
            Object[] item = stack.pop();
            Object node = item[0];
            if (node instanceof PdfFile.Ref && !visited.add(((PdfFile.Ref) node).num)) continue;
            Map<String, Object> d = f.dict(node);
            if (d == null) continue;
            Object res = d.containsKey("Resources") ? d.get("Resources") : item[1];
            Object media = d.containsKey("MediaBox") ? d.get("MediaBox") : item[2];
            Object crop = d.containsKey("CropBox") ? d.get("CropBox") : item[3];
            Object rotate = d.containsKey("Rotate") ? d.get("Rotate") : item[4];
            List<Object> kids = f.array(d.get("Kids"));
            if ("Pages".equals(d.get("Type")) || (!"Page".equals(d.get("Type")) && kids != null)) {
                if (kids == null) continue;
                for (int i = kids.size() - 1; i >= 0; i--) {
                    stack.push(new Object[]{kids.get(i), res, media, crop, rotate});
                }
                continue;
            }
            Page p = new Page();
            p.ref = node instanceof PdfFile.Ref ? (PdfFile.Ref) node : null;
            if (d.containsKey("Annots")) p.dict = d;
            p.resources = res;
            float[] m = box(f, media);
            float[] c = box(f, crop);
            p.box = c != null ? c : m != null ? m : new float[]{0, 0, 612, 792};
            p.rotate = ((PdfFile.intOr(f.resolve(rotate), 0) % 360 + 360) % 360) / 90 * 90;
            pages.add(p);
            if (pages.size() > expected) return null;
        }
        return pages;
    }

    private static float[] box(PdfFile f, Object o) throws IOException {
        List<Object> a = f.array(o);
        if (a == null || a.size() < 4) return null;
        float[] v = new float[4];
        for (int i = 0; i < 4; i++) {
            Object n = f.resolve(a.get(i));
            if (!(n instanceof Number)) return null;
            v[i] = ((Number) n).floatValue();
        }
        float[] b = {Math.min(v[0], v[2]), Math.min(v[1], v[3]), Math.max(v[0], v[2]), Math.max(v[1], v[3])};
        return b[2] - b[0] < 1 || b[3] - b[1] < 1 ? null : b;
    }

    private static void annotation(PdfFile f, Map<String, Object> root, Page p, Map<String, Object> a,
                                   Map<Integer, Integer> pageIndex, StringBuilder draw,
                                   Map<String, Object> xobjects, List<Link> links) throws IOException {
        if (a == null) return;
        float[] rect = box(f, a.get("Rect"));
        if (rect == null) return;
        Object subtype = a.get("Subtype");
        if ("Link".equals(subtype)) {
            link(f, root, p, a, rect, pageIndex, links);
            return;
        }
        if ("Popup".equals(subtype)) return;
        if ((PdfFile.intOr(f.resolve(a.get("F")), 0) & (HIDDEN | NO_VIEW)) != 0) return;
        Map<String, Object> ap = f.dict(a.get("AP"));
        if (ap == null) return;
        Object normal = ap.get("N");
        Object chosen = f.resolve(normal);
        if (chosen instanceof Map) {
            // One appearance per state, e.g. a check box that is on or off.
            Object state = f.resolve(a.get("AS"));
            if (!(state instanceof String)) return;
            normal = ((Map<?, ?>) chosen).get(state);
            chosen = f.resolve(normal);
        }
        if (!(normal instanceof PdfFile.Ref) || !(chosen instanceof PdfFile.Stream)) return;
        Map<String, Object> form = ((PdfFile.Stream) chosen).dict;
        float[] bbox = box(f, form.get("BBox"));
        if (bbox == null) return;
        float[] m = matrix(f, form.get("Matrix"));
        // The appearance's box, after its own matrix, is fitted to the annotation's rectangle
        // (PDF 32000-1, 12.5.5).
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float x = i % 2 == 0 ? bbox[0] : bbox[2], y = i < 2 ? bbox[1] : bbox[3];
            float tx = m[0] * x + m[2] * y + m[4], ty = m[1] * x + m[3] * y + m[5];
            x0 = Math.min(x0, tx);
            y0 = Math.min(y0, ty);
            x1 = Math.max(x1, tx);
            y1 = Math.max(y1, ty);
        }
        if (x1 - x0 < 1e-3 || y1 - y0 < 1e-3) return;
        float sx = (rect[2] - rect[0]) / (x1 - x0), sy = (rect[3] - rect[1]) / (y1 - y0);
        String name = "DrAnnot" + xobjects.size();
        xobjects.put(name, normal);
        draw.append("q ").append(fmt(sx)).append(" 0 0 ").append(fmt(sy)).append(' ')
                .append(fmt(rect[0] - sx * x0)).append(' ').append(fmt(rect[1] - sy * y0))
                .append(" cm /").append(name).append(" Do Q\n");
    }

    private static float[] matrix(PdfFile f, Object o) throws IOException {
        float[] m = {1, 0, 0, 1, 0, 0};
        List<Object> a = f.array(o);
        if (a == null || a.size() < 6) return m;
        for (int i = 0; i < 6; i++) {
            Object n = f.resolve(a.get(i));
            if (!(n instanceof Number)) return new float[]{1, 0, 0, 1, 0, 0};
            m[i] = ((Number) n).floatValue();
        }
        return m;
    }

    // -------------------------------------------------------------------- links

    private static void link(PdfFile f, Map<String, Object> root, Page p, Map<String, Object> a, float[] rect,
                             Map<Integer, Integer> pageIndex, List<Link> links) throws IOException {
        String uri = null;
        int target = -1;
        Map<String, Object> action = f.dict(a.get("A"));
        if (action != null) {
            Object s = action.get("S");
            if ("URI".equals(s)) {
                Object u = f.resolve(action.get("URI"));
                if (u instanceof byte[]) uri = new String((byte[]) u, StandardCharsets.ISO_8859_1).trim();
            } else if ("GoTo".equals(s)) {
                target = destPage(f, root, action.get("D"), pageIndex);
            }
        } else if (a.containsKey("Dest")) {
            target = destPage(f, root, a.get("Dest"), pageIndex);
        }
        if ((uri == null || uri.isEmpty()) && target < 0) return;
        float[] tl = shown(p, rect[0], rect[3]), br = shown(p, rect[2], rect[1]);
        links.add(new Link(Math.min(tl[0], br[0]), Math.min(tl[1], br[1]), Math.max(tl[0], br[0]),
                Math.max(tl[1], br[1]), uri, target));
    }

    /** A point in page space as fractions of the page as shown, top-down, after its rotation. */
    private static float[] shown(Page p, float x, float y) {
        float u = (x - p.box[0]) / (p.box[2] - p.box[0]);
        float v = (p.box[3] - y) / (p.box[3] - p.box[1]);
        switch (p.rotate) {
            case 90: return new float[]{1 - v, u};
            case 180: return new float[]{1 - u, 1 - v};
            case 270: return new float[]{v, 1 - u};
            default: return new float[]{u, v};
        }
    }

    private static int destPage(PdfFile f, Map<String, Object> root, Object dest,
                                Map<Integer, Integer> pageIndex) throws IOException {
        dest = f.resolve(dest);
        if (dest instanceof String || dest instanceof byte[]) dest = named(f, root, dest);
        if (dest instanceof Map) dest = f.resolve(((Map<?, ?>) dest).get("D"));
        if (!(dest instanceof List) || ((List<?>) dest).isEmpty()) return -1;
        Object first = ((List<?>) dest).get(0);
        if (first instanceof PdfFile.Ref) {
            Integer i = pageIndex.get(((PdfFile.Ref) first).num);
            return i == null ? -1 : i;
        }
        return -1;
    }

    /** Looks a named destination up in the catalog's Dests dictionary or name tree. */
    private static Object named(PdfFile f, Map<String, Object> root, Object name) throws IOException {
        String key = name instanceof String ? (String) name : new String((byte[]) name, StandardCharsets.ISO_8859_1);
        Map<String, Object> dests = f.dict(root.get("Dests"));
        if (dests != null && dests.containsKey(key)) return f.resolve(dests.get(key));
        Map<String, Object> names = f.dict(root.get("Names"));
        return names == null ? null : lookup(f, f.dict(names.get("Dests")), key, 0);
    }

    private static Object lookup(PdfFile f, Map<String, Object> node, String key, int depth) throws IOException {
        if (node == null || depth > 32) return null;
        List<Object> names = f.array(node.get("Names"));
        if (names != null) {
            for (int i = 0; i + 1 < names.size(); i += 2) {
                Object k = f.resolve(names.get(i));
                if (k instanceof byte[] && key.equals(new String((byte[]) k, StandardCharsets.ISO_8859_1))) {
                    return f.resolve(names.get(i + 1));
                }
            }
        }
        List<Object> kids = f.array(node.get("Kids"));
        if (kids != null) {
            for (Object kid : kids) {
                Object v = lookup(f, f.dict(kid), key, depth + 1);
                if (v != null) return v;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ writing

    /** The page with its appearances added as XObjects; its content is completed in write(). */
    private static Map<String, Object> rewritePage(PdfFile f, Page p, Map<String, Object> added) throws IOException {
        Map<String, Object> page = new LinkedHashMap<>(p.dict);
        // The platform renderer ignores them anyway; dropped so no renderer draws them twice.
        page.remove("Annots");
        Map<String, Object> res = f.dict(p.resources);
        res = res == null ? new LinkedHashMap<>() : new LinkedHashMap<>(res);
        Map<String, Object> xo = f.dict(res.get("XObject"));
        xo = xo == null ? new LinkedHashMap<>() : new LinkedHashMap<>(xo);
        for (Map.Entry<String, Object> e : added.entrySet()) {
            if (xo.containsKey(e.getKey())) throw new IOException("Name clash");
            xo.put(e.getKey(), e.getValue());
        }
        res.put("XObject", xo);
        page.put("Resources", res);
        return page;
    }

    private static void write(FileChannel in, PdfFile f, List<Page> pages, Map<Integer, Map<String, Object>> edits,
                              Map<Integer, String> contents, File out) throws IOException {
        FileOutputStream os = new FileOutputStream(out);
        try {
            FileChannel oc = os.getChannel();
            long size = in.size();
            for (long done = 0; done < size; ) {
                long n = in.transferTo(done, size - done, oc);
                if (n <= 0) throw new IOException("Copy failed");
                done += n;
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ascii(b, "\n");
            int next = f.size;
            // Object number -> {offset, generation}.
            TreeMap<Integer, long[]> xref = new TreeMap<>();

            // The page's own content runs inside q ... Q, so whatever state it leaves behind
            // does not move the annotations.
            int open = next++;
            xref.put(open, new long[]{size + b.size(), 0});
            stream(b, open, "q\n");
            for (Map.Entry<Integer, Map<String, Object>> e : edits.entrySet()) {
                Page p = pages.get(e.getKey());
                Map<String, Object> page = e.getValue();
                int drawn = next++;
                xref.put(drawn, new long[]{size + b.size(), 0});
                stream(b, drawn, "Q\n" + contents.get(e.getKey()));

                List<Object> list = new ArrayList<>();
                list.add(new PdfFile.Ref(open, 0));
                Object old = page.get("Contents");
                Object resolved = f.resolve(old);
                if (resolved instanceof List) list.addAll((List<?>) resolved);
                else if (old instanceof PdfFile.Ref) list.add(old);
                list.add(new PdfFile.Ref(drawn, 0));
                page.put("Contents", list);

                xref.put(p.ref.num, new long[]{size + b.size(), p.ref.gen});
                ascii(b, p.ref.num + " " + p.ref.gen + " obj\n");
                value(b, page);
                ascii(b, "\nendobj\n");
            }

            Map<String, Object> trailer = new LinkedHashMap<>();
            for (String k : new String[]{"Root", "Info", "ID"}) {
                if (f.trailer.containsKey(k)) trailer.put(k, f.trailer.get(k));
            }
            trailer.put("Prev", f.startxref);
            long xrefAt = size + b.size();
            if (f.xrefStream) {
                // The file uses cross-reference streams: its update must too.
                int self = next++;
                xref.put(self, new long[]{xrefAt, 0});
                ByteArrayOutputStream rows = new ByteArrayOutputStream();
                List<Object> index = new ArrayList<>();
                for (int[] run : runs(xref)) {
                    index.add((long) run[0]);
                    index.add((long) run[1]);
                    for (int k = 0; k < run[1]; k++) {
                        long[] v = xref.get(run[0] + k);
                        rows.write(1);
                        for (int s = 32; s >= 0; s -= 8) rows.write((int) (v[0] >>> s));
                        rows.write((int) (v[1] >>> 8));
                        rows.write((int) v[1]);
                    }
                }
                trailer.put("Type", "XRef");
                trailer.put("Size", (long) next);
                trailer.put("Index", index);
                List<Object> w = new ArrayList<>();
                w.add(1L);
                w.add(5L);
                w.add(2L);
                trailer.put("W", w);
                trailer.put("Length", (long) rows.size());
                ascii(b, self + " 0 obj\n");
                value(b, trailer);
                ascii(b, "\nstream\n");
                rows.writeTo(b);
                ascii(b, "\nendstream\nendobj\n");
            } else {
                ascii(b, "xref\n");
                for (int[] run : runs(xref)) {
                    ascii(b, run[0] + " " + run[1] + "\n");
                    for (int k = 0; k < run[1]; k++) {
                        long[] v = xref.get(run[0] + k);
                        ascii(b, String.format(Locale.US, "%010d %05d n\r\n", v[0], v[1]));
                    }
                }
                trailer.put("Size", (long) next);
                ascii(b, "trailer\n");
                value(b, trailer);
                ascii(b, "\n");
            }
            ascii(b, "startxref\n" + xrefAt + "\n%%EOF\n");
            b.writeTo(os);
        } finally {
            os.close();
        }
    }

    /** Consecutive object numbers: {first, count}. */
    private static List<int[]> runs(TreeMap<Integer, long[]> xref) {
        List<int[]> runs = new ArrayList<>();
        for (int num : xref.keySet()) {
            int[] last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (last != null && last[0] + last[1] == num) last[1]++;
            else runs.add(new int[]{num, 1});
        }
        return runs;
    }

    private static void stream(ByteArrayOutputStream b, int num, String content) {
        byte[] data = content.getBytes(StandardCharsets.ISO_8859_1);
        ascii(b, num + " 0 obj\n<< /Length " + data.length + " >>\nstream\n");
        b.write(data, 0, data.length);
        ascii(b, "\nendstream\nendobj\n");
    }

    private static void ascii(ByteArrayOutputStream b, String s) {
        byte[] d = s.getBytes(StandardCharsets.ISO_8859_1);
        b.write(d, 0, d.length);
    }

    private static void value(ByteArrayOutputStream b, Object o) throws IOException {
        if (o == null) {
            ascii(b, "null");
        } else if (o instanceof Boolean || o instanceof Long || o instanceof Integer) {
            ascii(b, o.toString());
        } else if (o instanceof Double || o instanceof Float) {
            ascii(b, fmt(((Number) o).doubleValue()));
        } else if (o instanceof String) {
            StringBuilder sb = new StringBuilder("/");
            for (char c : ((String) o).toCharArray()) {
                if (c < 33 || c > 126 || "()<>[]{}/%#".indexOf(c) >= 0) sb.append(String.format(Locale.US, "#%02X", c & 0xff));
                else sb.append(c);
            }
            ascii(b, sb.toString());
        } else if (o instanceof byte[]) {
            StringBuilder sb = new StringBuilder("<");
            for (byte x : (byte[]) o) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
            ascii(b, sb.append('>').toString());
        } else if (o instanceof PdfFile.Ref) {
            ascii(b, ((PdfFile.Ref) o).num + " " + ((PdfFile.Ref) o).gen + " R");
        } else if (o instanceof List) {
            ascii(b, "[");
            for (Object v : (List<?>) o) {
                value(b, v);
                ascii(b, " ");
            }
            ascii(b, "]");
        } else if (o instanceof Map) {
            ascii(b, "<<");
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                value(b, e.getKey());
                ascii(b, " ");
                value(b, e.getValue());
                ascii(b, "\n");
            }
            ascii(b, ">>");
        } else {
            // A stream can only be referred to, never written inline.
            throw new IOException("Cannot write " + o.getClass());
        }
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) return Long.toString(Math.round(v));
        String s = String.format(Locale.US, "%.6f", v);
        int end = s.length();
        while (s.charAt(end - 1) == '0') end--;
        return s.substring(0, end);
    }
}
