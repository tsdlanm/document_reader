package com.tsdlanm.documentreader.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Just enough of a PDF reader to look objects up: cross-reference tables and streams, object
 * streams and Flate. It does not draw anything; {@link android.graphics.pdf.PdfRenderer} does.
 *
 * <p>Objects are plain Java values: null, Boolean, Long, Double, String (a name), byte[] (a
 * string), List (an array), Map (a dictionary), {@link Ref} and {@link Stream}.
 */
final class PdfFile {
    static final class Ref {
        final int num, gen;

        Ref(int num, int gen) {
            this.num = num;
            this.gen = gen;
        }
    }

    static final class Stream {
        final Map<String, Object> dict;
        /** File offset of the stream data. */
        final long data;

        Stream(Map<String, Object> dict, long data) {
            this.dict = dict;
            this.data = data;
        }
    }

    /** A bare keyword such as obj, stream, xref or trailer. */
    static final class Kw {
        final String s;

        Kw(String s) {
            this.s = s;
        }

        boolean is(String k) {
            return s.equals(k);
        }
    }

    private static final byte UNSET = 0, FREE = 1, IN_USE = 2, COMPRESSED = 3;
    private static final int MAX_OBJECTS = 8_000_000;
    private static final int MAX_STREAM = 32 << 20;

    /** Trailer of the newest cross-reference section. */
    final Map<String, Object> trailer;
    /** Offset of the newest cross-reference section, and whether it is a stream. */
    final long startxref;
    final boolean xrefStream;
    /** One more than the highest object number in use. */
    final int size;

    private final FileChannel ch;
    private final long length;
    private byte[] types = new byte[0];
    /** Offset of an object in use, or the object stream holding a compressed one. */
    private long[] offsets = new long[0];
    /** Index within the object stream of a compressed object. */
    private int[] indexes = new int[0];
    private int highest;
    private final Map<Integer, Object> cache = new HashMap<>();
    private final Map<Integer, ObjStm> objStms = new HashMap<>();

    PdfFile(FileChannel ch) throws IOException {
        this.ch = ch;
        length = ch.size();
        int tail = (int) Math.min(length, 2048);
        byte[] end = read(length - tail, tail);
        int at = lastIndexOf(end, "startxref");
        if (at < 0) throw new IOException("No startxref");
        Lexer lx = new Lexer(end, tail, length - tail, true);
        lx.pos = at + 9;
        startxref = num(lx.next());
        boolean[] isStream = new boolean[1];
        Map<String, Object> newest = null;
        boolean newestIsStream = false;
        Set<Long> seen = new HashSet<>();
        long off = startxref;
        while (off >= 0 && off < length && seen.add(off) && seen.size() < 1024) {
            Map<String, Object> t = readXref(off, isStream);
            if (newest == null) {
                newest = t;
                newestIsStream = isStream[0];
            }
            // Hybrid files keep the newer objects in a stream named by the table's trailer.
            Object stm = t.get("XRefStm");
            if (stm instanceof Long && seen.add((Long) stm)) readXref((Long) stm, isStream);
            Object prev = t.get("Prev");
            off = prev instanceof Number ? ((Number) prev).longValue() : -1;
        }
        if (newest == null) throw new IOException("No trailer");
        trailer = newest;
        xrefStream = newestIsStream;
        Object s = trailer.get("Size");
        size = Math.max(highest + 1, s instanceof Number ? ((Number) s).intValue() : 0);
    }

    /** Resolves references; null for missing objects. */
    Object resolve(Object o) throws IOException {
        for (int i = 0; i < 32 && o instanceof Ref; i++) o = get(((Ref) o).num);
        return o instanceof Ref ? null : o;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> dict(Object o) throws IOException {
        o = resolve(o);
        if (o instanceof Stream) return ((Stream) o).dict;
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    List<Object> array(Object o) throws IOException {
        o = resolve(o);
        return o instanceof List ? (List<Object>) o : null;
    }

    Object get(int num) throws IOException {
        if (num < 0 || num >= types.length) return null;
        if (cache.containsKey(num)) return cache.get(num);
        Object v = null;
        if (types[num] == IN_USE) {
            v = parseAt(offsets[num], lx -> {
                lx.next();
                lx.next();
                Object kw = lx.next();
                if (!(kw instanceof Kw) || !((Kw) kw).is("obj")) throw new IOException("No obj");
                Object value = lx.next();
                if (value instanceof Map) {
                    Object after = lx.next();
                    if (after instanceof Kw && ((Kw) after).is("stream")) {
                        @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) value;
                        return new Stream(d, lx.base + lx.streamStart());
                    }
                }
                return value;
            });
        } else if (types[num] == COMPRESSED) {
            ObjStm s = objStm((int) offsets[num]);
            int i = indexes[num];
            if (s != null && i < s.offs.length) {
                Lexer lx = new Lexer(s.data, s.data.length, 0, true);
                lx.pos = s.first + s.offs[i];
                v = lx.next();
            }
        }
        if (cache.size() > 20_000) cache.clear();
        cache.put(num, v);
        return v;
    }

    /** The decoded data of a stream. Only Flate (with PNG predictors) is supported. */
    byte[] streamData(Stream s) throws IOException {
        Object l = resolve(s.dict.get("Length"));
        long len = l instanceof Number ? ((Number) l).longValue() : -1;
        if (len < 0 || s.data + len > length || len > MAX_STREAM) throw new IOException("Bad length");
        byte[] data = read(s.data, (int) len);
        Object filter = resolve(s.dict.get("Filter"));
        Object parms = resolve(s.dict.get("DecodeParms"));
        if (filter instanceof List) {
            List<?> fs = (List<?>) filter;
            if (fs.size() > 1) throw new IOException("Filter chain");
            filter = fs.isEmpty() ? null : resolve(fs.get(0));
            if (parms instanceof List) parms = ((List<?>) parms).isEmpty() ? null : resolve(((List<?>) parms).get(0));
        }
        if (filter == null) return data;
        if (!"FlateDecode".equals(filter) && !"Fl".equals(filter)) throw new IOException("Filter " + filter);
        data = inflate(data);
        if (parms instanceof Map) {
            Map<?, ?> p = (Map<?, ?>) parms;
            int predictor = intOr(p.get("Predictor"), 1);
            if (predictor >= 10) {
                data = unpredict(data, intOr(p.get("Colors"), 1) * intOr(p.get("BitsPerComponent"), 8),
                        intOr(p.get("Columns"), 1));
            } else if (predictor != 1) {
                throw new IOException("Predictor " + predictor);
            }
        }
        return data;
    }

    // --------------------------------------------------------- cross-reference

    @SuppressWarnings("unchecked")
    private Map<String, Object> readXref(long off, boolean[] isStream) throws IOException {
        return parseAt(off, lx -> {
            Object tok = lx.next();
            if (tok instanceof Kw && ((Kw) tok).is("xref")) {
                isStream[0] = false;
                while (true) {
                    Object a = lx.next();
                    if (a instanceof Kw) {
                        if (!((Kw) a).is("trailer")) throw new IOException("Bad xref");
                        Object t = lx.next();
                        if (!(t instanceof Map)) throw new IOException("Bad trailer");
                        return (Map<String, Object>) t;
                    }
                    int start = (int) num(a);
                    int count = (int) num(lx.next());
                    for (int k = 0; k < count; k++) {
                        long o = num(lx.next());
                        lx.next();
                        Object kind = lx.next();
                        set(start + k, kind instanceof Kw && ((Kw) kind).is("n") ? IN_USE : FREE, o, 0);
                    }
                }
            }
            isStream[0] = true;
            lx.next();
            Object kw = lx.next();
            Object d = lx.next();
            Object st = lx.next();
            if (!(kw instanceof Kw) || !(d instanceof Map) || !(st instanceof Kw) || !((Kw) st).is("stream")) {
                throw new IOException("Bad xref stream");
            }
            Map<String, Object> dict = (Map<String, Object>) d;
            byte[] data = streamData(new Stream(dict, lx.base + lx.streamStart()));
            List<?> w = (List<?>) dict.get("W");
            int w0 = (int) num(w.get(0)), w1 = (int) num(w.get(1)), w2 = (int) num(w.get(2));
            int row = w0 + w1 + w2;
            List<?> index = dict.get("Index") instanceof List ? (List<?>) dict.get("Index") : null;
            if (index == null) {
                List<Object> all = new ArrayList<>();
                all.add(0L);
                all.add(dict.get("Size"));
                index = all;
            }
            int p = 0;
            for (int i = 0; i + 1 < index.size(); i += 2) {
                int start = (int) num(index.get(i));
                int count = (int) num(index.get(i + 1));
                for (int k = 0; k < count && p + row <= data.length; k++, p += row) {
                    int type = w0 == 0 ? 1 : (int) field(data, p, w0);
                    long f1 = field(data, p + w0, w1);
                    long f2 = field(data, p + w0 + w1, w2);
                    if (type == 1) set(start + k, IN_USE, f1, 0);
                    else if (type == 2) set(start + k, COMPRESSED, f1, (int) f2);
                    else if (type == 0) set(start + k, FREE, 0, 0);
                }
            }
            return dict;
        });
    }

    /** Newer sections are read first, so the first entry seen for an object wins. */
    private void set(int num, byte type, long offset, int index) throws IOException {
        if (num < 0 || num >= MAX_OBJECTS) return;
        if (num >= types.length) {
            int n = Math.max(num + 1, types.length * 2);
            types = java.util.Arrays.copyOf(types, n);
            offsets = java.util.Arrays.copyOf(offsets, n);
            indexes = java.util.Arrays.copyOf(indexes, n);
        }
        if (types[num] != UNSET) return;
        highest = Math.max(highest, num);
        types[num] = type;
        offsets[num] = offset;
        indexes[num] = index;
    }

    private static long field(byte[] d, int at, int width) {
        long v = 0;
        for (int i = 0; i < width; i++) v = (v << 8) | (d[at + i] & 0xff);
        return v;
    }

    // ----------------------------------------------------------- object streams

    private static final class ObjStm {
        byte[] data;
        int first;
        int[] offs;
    }

    private ObjStm objStm(int num) throws IOException {
        if (objStms.containsKey(num)) return objStms.get(num);
        ObjStm s = null;
        Object o = get(num);
        if (o instanceof Stream) {
            Stream st = (Stream) o;
            s = new ObjStm();
            s.data = streamData(st);
            s.first = intOr(st.dict.get("First"), 0);
            int n = intOr(st.dict.get("N"), 0);
            s.offs = new int[Math.max(0, Math.min(n, s.data.length / 2))];
            Lexer lx = new Lexer(s.data, Math.min(s.first, s.data.length), 0, true);
            for (int i = 0; i < s.offs.length; i++) {
                lx.next();
                s.offs[i] = (int) num(lx.next());
            }
        }
        if (objStms.size() > 8) objStms.clear();
        objStms.put(num, s);
        return s;
    }

    // ------------------------------------------------------------------ reading

    private interface Parse<T> {
        T run(Lexer lx) throws IOException;
    }

    /** Parses at a file offset, reading a larger window whenever the first was too small. */
    private <T> T parseAt(long off, Parse<T> p) throws IOException {
        if (off < 0 || off >= length) throw new IOException("Bad offset");
        for (long len = 4096; ; len *= 4) {
            int n = (int) Math.min(len, length - off);
            boolean complete = off + n >= length;
            try {
                return p.run(new Lexer(read(off, n), n, off, complete));
            } catch (Lexer.Eof e) {
                if (complete || len > MAX_STREAM) throw new IOException("Truncated");
            }
        }
    }

    private byte[] read(long off, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(len);
        while (buf.hasRemaining()) {
            if (ch.read(buf, off + buf.position()) < 0) break;
        }
        return buf.array();
    }

    private static byte[] inflate(byte[] in) throws IOException {
        Inflater inf = new Inflater();
        inf.setInput(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length * 3);
        byte[] buf = new byte[16 * 1024];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(buf, 0, n);
                if (out.size() > MAX_STREAM) throw new IOException("Too large");
            }
        } catch (DataFormatException e) {
            // Keep what was decoded: many writers leave junk after the data.
            if (out.size() == 0) throw new IOException(e);
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }

    /** Undoes PNG row predictors. */
    private static byte[] unpredict(byte[] in, int bitsPerPixel, int columns) {
        int bpp = Math.max(1, bitsPerPixel / 8);
        int rowLen = (bitsPerPixel * columns + 7) / 8;
        int rows = in.length / (rowLen + 1);
        byte[] out = new byte[rows * rowLen];
        for (int r = 0; r < rows; r++) {
            int filter = in[r * (rowLen + 1)] & 0xff;
            int src = r * (rowLen + 1) + 1, dst = r * rowLen;
            for (int i = 0; i < rowLen; i++) {
                int x = in[src + i] & 0xff;
                int a = i >= bpp ? out[dst + i - bpp] & 0xff : 0;
                int b = r > 0 ? out[dst + i - rowLen] & 0xff : 0;
                int c = i >= bpp && r > 0 ? out[dst + i - rowLen - bpp] & 0xff : 0;
                switch (filter) {
                    case 1: x += a; break;
                    case 2: x += b; break;
                    case 3: x += (a + b) / 2; break;
                    case 4: {
                        int pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c);
                        x += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
                        break;
                    }
                    default: break;
                }
                out[dst + i] = (byte) x;
            }
        }
        return out;
    }

    private static int lastIndexOf(byte[] b, String s) {
        outer:
        for (int i = b.length - s.length(); i >= 0; i--) {
            for (int k = 0; k < s.length(); k++) if (b[i + k] != s.charAt(k)) continue outer;
            return i;
        }
        return -1;
    }

    static long num(Object o) throws IOException {
        if (o instanceof Number) return ((Number) o).longValue();
        throw new IOException("Not a number");
    }

    static int intOr(Object o, int def) {
        return o instanceof Number ? ((Number) o).intValue() : def;
    }

    // -------------------------------------------------------------------- lexer

    static final class Lexer {
        /** The window ended before the object did: read more and try again. */
        static final class Eof extends RuntimeException {
            @Override
            public synchronized Throwable fillInStackTrace() {
                return this;
            }
        }

        private static final Eof EOF = new Eof();

        final byte[] b;
        final int end;
        final long base;
        /** True when the window reaches the end of the data, so its end is a real end. */
        final boolean complete;
        int pos;

        Lexer(byte[] b, int end, long base, boolean complete) {
            this.b = b;
            this.end = end;
            this.base = base;
            this.complete = complete;
        }

        /** The byte at {@code i}, or -1 at the real end of the data. */
        private int at(int i) {
            if (i < end) return b[i] & 0xff;
            if (complete) return -1;
            throw EOF;
        }

        private int peek() {
            if (pos >= end) throw EOF;
            return b[pos] & 0xff;
        }

        private static boolean isSpace(int c) {
            return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32;
        }

        private static boolean isDelimiter(int c) {
            return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']'
                    || c == '{' || c == '}' || c == '/' || c == '%';
        }

        private static boolean isRegular(int c) {
            return c >= 0 && !isSpace(c) && !isDelimiter(c);
        }

        private void skipSpace() {
            while (true) {
                int c = peek();
                if (c == '%') {
                    while (c != '\n' && c != '\r') {
                        pos++;
                        c = peek();
                    }
                } else if (isSpace(c)) {
                    pos++;
                } else {
                    return;
                }
            }
        }

        Object next() {
            skipSpace();
            int c = peek();
            switch (c) {
                case '/':
                    pos++;
                    return name();
                case '(':
                    pos++;
                    return literal();
                case '[': {
                    pos++;
                    List<Object> a = new ArrayList<>();
                    while (true) {
                        skipSpace();
                        if (peek() == ']') {
                            pos++;
                            return a;
                        }
                        a.add(next());
                    }
                }
                case '<':
                    if (at(pos + 1) == '<') {
                        pos += 2;
                        Map<String, Object> d = new LinkedHashMap<>();
                        while (true) {
                            skipSpace();
                            if (peek() == '>') {
                                pos += at(pos + 1) == '>' ? 2 : 1;
                                return d;
                            }
                            Object key = next();
                            Object value = next();
                            if (key instanceof String && value != null && !(value instanceof Kw)) {
                                d.put((String) key, value);
                            }
                        }
                    }
                    pos++;
                    return hex();
                default:
                    if (isDelimiter(c)) {
                        pos++;
                        return new Kw(String.valueOf((char) c));
                    }
                    if ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.') return number();
                    String word = regular();
                    if (word.equals("true")) return Boolean.TRUE;
                    if (word.equals("false")) return Boolean.FALSE;
                    if (word.equals("null")) return null;
                    return new Kw(word);
            }
        }

        private String regular() {
            int start = pos;
            while (isRegular(at(pos))) pos++;
            return new String(b, start, pos - start, java.nio.charset.StandardCharsets.ISO_8859_1);
        }

        private Object number() {
            String s = regular();
            if (s.indexOf('.') >= 0) {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    return 0.0;
                }
            }
            long v;
            try {
                v = Long.parseLong(s.startsWith("+") ? s.substring(1) : s);
            } catch (NumberFormatException e) {
                return 0L;
            }
            // "12 0 R" is a reference.
            if (v >= 0) {
                int save = pos;
                while (isSpace(at(pos))) pos++;
                int g = pos;
                while (at(pos) >= '0' && at(pos) <= '9') pos++;
                if (pos > g) {
                    int gen = Integer.parseInt(new String(b, g, Math.min(pos - g, 9), java.nio.charset.StandardCharsets.ISO_8859_1));
                    while (isSpace(at(pos))) pos++;
                    if (at(pos) == 'R' && !isRegular(at(pos + 1))) {
                        pos++;
                        return new Ref((int) Math.min(v, Integer.MAX_VALUE), gen);
                    }
                }
                pos = save;
            }
            return v;
        }

        private String name() {
            StringBuilder sb = new StringBuilder();
            while (isRegular(at(pos))) {
                int c = b[pos++] & 0xff;
                if (c == '#' && pos + 1 < end) {
                    int h = Character.digit(b[pos], 16), l = Character.digit(b[pos + 1], 16);
                    if (h >= 0 && l >= 0) {
                        c = h * 16 + l;
                        pos += 2;
                    }
                }
                sb.append((char) c);
            }
            return sb.toString();
        }

        private byte[] literal() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int depth = 1;
            while (true) {
                int c = peek();
                pos++;
                if (c == '\\') {
                    c = peek();
                    pos++;
                    switch (c) {
                        case 'n': out.write('\n'); break;
                        case 'r': out.write('\r'); break;
                        case 't': out.write('\t'); break;
                        case 'b': out.write('\b'); break;
                        case 'f': out.write('\f'); break;
                        case '\r':
                            if (peek() == '\n') pos++;
                            break;
                        case '\n':
                            break;
                        default:
                            if (c >= '0' && c <= '7') {
                                int v = c - '0';
                                for (int i = 0; i < 2 && peek() >= '0' && peek() <= '7'; i++) v = v * 8 + (b[pos++] - '0');
                                out.write(v);
                            } else {
                                out.write(c);
                            }
                    }
                } else {
                    if (c == '(') depth++;
                    if (c == ')' && --depth == 0) return out.toByteArray();
                    out.write(c);
                }
            }
        }

        private byte[] hex() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int hi = -1;
            while (true) {
                int c = peek();
                pos++;
                if (c == '>') break;
                int d = Character.digit(c, 16);
                if (d < 0) continue;
                if (hi < 0) {
                    hi = d;
                } else {
                    out.write(hi * 16 + d);
                    hi = -1;
                }
            }
            if (hi >= 0) out.write(hi * 16);
            return out.toByteArray();
        }

        /** Start of stream data, after the stream keyword and its end of line. */
        int streamStart() {
            int c = peek();
            if (c == '\r') {
                pos++;
                if (at(pos) == '\n') pos++;
            } else if (c == '\n') {
                pos++;
            }
            return pos;
        }
    }
}
