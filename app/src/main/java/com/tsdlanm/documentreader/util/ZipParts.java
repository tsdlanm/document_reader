package com.tsdlanm.documentreader.util;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Helpers for reading the parts of an Office Open XML (ZIP) package. */
public final class ZipParts {
    /** Upper bound for one uncompressed XML part; protects against zip bombs. */
    private static final long MAX_PART_BYTES = 512L * 1024 * 1024;

    private ZipParts() {
    }

    public static final class Rel {
        public final String type;
        public final String target;
        public final boolean external;

        Rel(String type, String target, boolean external) {
            this.type = type;
            this.target = target;
            this.external = external;
        }
    }

    public static ZipEntry entry(ZipFile zip, String path) {
        if (path == null) return null;
        ZipEntry e = zip.getEntry(path);
        if (e == null && path.startsWith("/")) e = zip.getEntry(path.substring(1));
        return e;
    }

    /** An XML part opened for reading. Close it to release the underlying stream. */
    public static final class Part implements Closeable {
        public final XmlPullParser parser;
        private final InputStream in;

        Part(XmlPullParser parser, InputStream in) {
            this.parser = parser;
            this.in = in;
        }

        @Override
        public void close() {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Opens a parser on the part, or returns null when the part does not exist. */
    public static Part open(ZipFile zip, String path, XmlFactory xml)
            throws IOException, XmlPullParserException {
        ZipEntry e = entry(zip, path);
        if (e == null) return null;
        InputStream in = new Limited(zip.getInputStream(e), MAX_PART_BYTES);
        try {
            XmlPullParser p = xml.newParser();
            p.setInput(in, null);
            return new Part(p, in);
        } catch (XmlPullParserException | RuntimeException ex) {
            in.close();
            throw ex;
        }
    }

    /** Reads the relationships of {@code part} (e.g. word/document.xml), resolving targets to zip paths. */
    public static Map<String, Rel> rels(ZipFile zip, String part, XmlFactory xml)
            throws IOException, XmlPullParserException {
        Map<String, Rel> out = new HashMap<>();
        String dir = dirOf(part);
        String name = part.substring(dir.length());
        Part part0 = open(zip, dir + "_rels/" + name + ".rels", xml);
        if (part0 == null) return out;
        try {
            XmlPullParser p = part0.parser;
            int e;
            while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (e != XmlPullParser.START_TAG || !"Relationship".equals(p.getName())) continue;
                String id = XmlUtil.attr(p, "Id");
                String target = XmlUtil.attr(p, "Target");
                if (id == null || target == null) continue;
                boolean external = "External".equals(XmlUtil.attr(p, "TargetMode"));
                String type = XmlUtil.attr(p, "Type");
                out.put(id, new Rel(type == null ? "" : type, external ? target : resolve(dir, target), external));
            }
        } finally {
            part0.close();
        }
        return out;
    }

    /** Finds the main part (officeDocument relationship) of the package. */
    public static String mainPart(ZipFile zip, XmlFactory xml, String fallback)
            throws IOException, XmlPullParserException {
        for (Rel r : rels(zip, "", xml).values()) {
            if (r.type.endsWith("/officeDocument") && entry(zip, r.target) != null) return r.target;
        }
        return fallback;
    }

    static String dirOf(String part) {
        int slash = part.lastIndexOf('/');
        return slash < 0 ? "" : part.substring(0, slash + 1);
    }

    /** Resolves a relationship target against the directory of its source part. */
    public static String resolve(String dir, String target) {
        String path;
        if (target.startsWith("/")) path = target.substring(1);
        else path = dir + target;
        if (!path.contains("..") && !path.contains("./")) return path;
        String[] segs = path.split("/");
        StringBuilder sb = new StringBuilder();
        ArrayList<String> stack = new ArrayList<>();
        for (String s : segs) {
            if (s.isEmpty() || ".".equals(s)) continue;
            if ("..".equals(s)) {
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
            } else {
                stack.add(s);
            }
        }
        for (int i = 0; i < stack.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(stack.get(i));
        }
        return sb.toString();
    }

    /** Stops reading after a fixed number of bytes. */
    static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long limit) {
            super(in);
            left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) throw new IOException("Part too large");
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) throw new IOException("Part too large");
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }
}
