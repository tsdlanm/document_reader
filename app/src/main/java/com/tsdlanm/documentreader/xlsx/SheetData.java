package com.tsdlanm.documentreader.xlsx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Cells of one worksheet, stored compactly per row. Numbers are formatted lazily when drawn. */
public final class SheetData {
    static final byte TEXT = 0, NUMBER = 1, EMPTY = 2;

    static final class Row {
        int size;
        // char: column (< 16384) and style indexes (< 64000) fit in 16 bits, half of an int.
        char[] cols = new char[4];
        byte[] kinds = new byte[4];
        double[] nums;
        String[] texts = new String[4];
        char[] styles = new char[4];
        /** Custom height in points, or 0 for the default. */
        float height;
        boolean hidden;

        void add(int col, byte kind, String text, double num, int style) {
            if (size > 0 && cols[size - 1] >= col) {
                // Cells normally come in column order; replace a duplicate, ignore out-of-order.
                if (cols[size - 1] == col) size--;
                else return;
            }
            if (size == cols.length) grow();
            cols[size] = (char) col;
            kinds[size] = kind;
            texts[size] = text;
            styles[size] = (char) Math.max(0, Math.min(style, Character.MAX_VALUE));
            if (kind == NUMBER) {
                if (nums == null) nums = new double[cols.length];
                nums[size] = num;
            }
            size++;
        }

        private void grow() {
            resize(Math.max(4, cols.length * 2));
        }

        /** Drops the spare capacity once the row is complete; a big sheet has many rows. */
        void trim() {
            if (size < cols.length) resize(size);
        }

        private void resize(int n) {
            cols = Arrays.copyOf(cols, n);
            kinds = Arrays.copyOf(kinds, n);
            texts = Arrays.copyOf(texts, n);
            styles = Arrays.copyOf(styles, n);
            if (nums != null) nums = Arrays.copyOf(nums, n);
        }

        int find(int col) {
            int lo = 0, hi = size - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int c = cols[mid];
                if (c < col) lo = mid + 1;
                else if (c > col) hi = mid - 1;
                else return mid;
            }
            return -1;
        }
    }

    public final int rowCount;
    public final int colCount;
    /** Row heights in points (0 for hidden rows). */
    public final float[] rowHeights;
    /** Column widths in pixels at 96 dpi (0 for hidden columns). */
    public final float[] colWidths;
    /** Merged ranges as {firstRow, firstCol, lastRow, lastCol}. */
    public final List<int[]> merges;
    /** True when the sheet had more cells than the memory limit allowed. */
    public final boolean truncated;
    /** Size of rows and columns without their own: points, and pixels at 96 dpi. */
    public float defaultRowHeight = 15f, defaultColWidth = 64f;
    /** False when the sheet hides Excel's grey gridlines (only real borders are drawn). */
    public boolean showGrid = true;
    /** Rows at the top and columns at the left kept in place while scrolling (freeze panes). */
    public int frozenRows, frozenCols;
    /** First row and column shown when the file was saved (Excel opens scrolled there). */
    public int topRow, topCol;
    /**
     * AutoFilter range {firstRow, firstCol, lastRow, lastCol}, or null; its first row holds the
     * headers with the filter buttons. {@link #filters} maps a column to the texts shown (a
     * missing column shows everything); "" stands for empty cells.
     */
    public int[] filterRange;
    public final java.util.Map<Integer, java.util.Set<String>> filters = new java.util.HashMap<>();
    /** Heights the rows below the filter header have when shown. */
    float[] filterHeights;

    /** Sorted distinct texts of a column below the filter header ("" for empty cells). */
    public List<String> filterValues(int col) {
        java.util.TreeSet<String> out = new java.util.TreeSet<>(NATURAL);
        for (int r = filterRange[0] + 1; r <= filterRange[2] && r < rowCount; r++) {
            String t = text(r, col);
            out.add(t == null ? "" : t);
        }
        return new ArrayList<>(out);
    }

    /** Shows the rows matching every column's filter and hides the others. */
    public void applyFilter() {
        if (filterRange == null || filterHeights == null) return;
        for (int r = filterRange[0] + 1; r <= filterRange[2] && r < rowCount; r++) {
            boolean shown = true;
            for (java.util.Map.Entry<Integer, java.util.Set<String>> f : filters.entrySet()) {
                String t = text(r, f.getKey());
                if (!f.getValue().contains(t == null ? "" : t)) {
                    shown = false;
                    break;
                }
            }
            rowHeights[r] = shown ? filterHeights[r - filterRange[0] - 1] : 0;
        }
    }

    /** Numbers in numeric order, then text, as Excel lists filter values; empty last. */
    private static final java.util.Comparator<String> NATURAL = (a, b) -> {
        if (a.isEmpty() || b.isEmpty()) return a.isEmpty() ? (b.isEmpty() ? 0 : 1) : -1;
        Double x = number(a), y = number(b);
        if (x != null && y != null) return x.compareTo(y) != 0 ? x.compareTo(y) : a.compareTo(b);
        if (x != null) return -1;
        if (y != null) return 1;
        int c = a.compareToIgnoreCase(b);
        return c != 0 ? c : a.compareTo(b);
    };

    private static Double number(String s) {
        try {
            return Double.valueOf(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Pictures and charts floating over the cells. */
    public final List<Picture> pictures = new ArrayList<>();

    /**
     * A picture (or a chart, drawn as a labelled box) anchored to cells: its top-left corner is
     * {@code fromCol/fromRow} plus offsets, its bottom-right {@code toCol/toRow} plus offsets, or
     * when {@code toCol < 0}, the top-left plus {@code width/height}. Offsets and sizes in EMU.
     */
    public static final class Picture {
        /** Image part in the zip, or null for a chart. */
        public String part;
        public int fromCol, fromRow, toCol = -1, toRow;
        public long fromColOff, fromRowOff, toColOff, toRowOff, width, height;
    }

    private final Row[] rows;
    private final CellStyle[] styles;
    private final boolean date1904;

    SheetData(Row[] rows, int colCount, float[] rowHeights, float[] colWidths, List<int[]> merges,
              boolean truncated, CellStyle[] styles, boolean date1904) {
        this.rows = rows;
        this.rowCount = rows.length;
        this.colCount = colCount;
        this.rowHeights = rowHeights;
        this.colWidths = colWidths;
        this.merges = merges == null ? new ArrayList<int[]>() : merges;
        this.truncated = truncated;
        this.styles = styles;
        this.date1904 = date1904;
    }

    public boolean isEmpty() {
        return rowCount == 0 || colCount == 0;
    }

    private int index(Row row, int col) {
        return row == null ? -1 : row.find(col);
    }

    /** Display text of a cell, or null when empty. */
    public String text(int r, int c) {
        if (r < 0 || r >= rowCount) return null;
        Row row = rows[r];
        int i = index(row, c);
        if (i < 0) return null;
        String t = row.texts[i];
        if (t == null && row.kinds[i] == NUMBER) {
            t = style(row.styles[i]).format.format(row.nums[i], date1904);
            row.texts[i] = t;
        }
        return t;
    }

    public boolean isNumber(int r, int c) {
        if (r < 0 || r >= rowCount) return false;
        Row row = rows[r];
        int i = index(row, c);
        return i >= 0 && row.kinds[i] == NUMBER;
    }

    public CellStyle style(int r, int c) {
        if (r < 0 || r >= rowCount) return CellStyle.DEFAULT;
        Row row = rows[r];
        int i = index(row, c);
        return i < 0 ? CellStyle.DEFAULT : style(row.styles[i]);
    }

    private CellStyle style(int idx) {
        return idx >= 0 && idx < styles.length && styles[idx] != null ? styles[idx] : CellStyle.DEFAULT;
    }

    /** True when the cell has a value (not just formatting). */
    public boolean hasCell(int r, int c) {
        if (r < 0 || r >= rowCount) return false;
        Row row = rows[r];
        int i = index(row, c);
        return i >= 0 && row.kinds[i] != EMPTY;
    }

    /** Column letters for a 0-based index: 0 -> A, 26 -> AA. */
    public static String columnName(int c) {
        StringBuilder sb = new StringBuilder();
        int n = c + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }
}
