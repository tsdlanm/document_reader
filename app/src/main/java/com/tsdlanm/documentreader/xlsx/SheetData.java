package com.tsdlanm.documentreader.xlsx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Cells of one worksheet, stored compactly per row. Numbers are formatted lazily when drawn. */
public final class SheetData {
    static final byte TEXT = 0, NUMBER = 1, EMPTY = 2;

    static final class Row {
        int size;
        int[] cols = new int[4];
        byte[] kinds = new byte[4];
        double[] nums;
        String[] texts = new String[4];
        int[] styles = new int[4];
        /** Custom height in points, or 0 for the default; -1 for a hidden row. */
        float height;

        void add(int col, byte kind, String text, double num, int style) {
            if (size > 0 && cols[size - 1] >= col) {
                // Cells normally come in column order; replace a duplicate, ignore out-of-order.
                if (cols[size - 1] == col) size--;
                else return;
            }
            if (size == cols.length) grow();
            cols[size] = col;
            kinds[size] = kind;
            texts[size] = text;
            styles[size] = style;
            if (kind == NUMBER) {
                if (nums == null) nums = new double[cols.length];
                nums[size] = num;
            }
            size++;
        }

        private void grow() {
            int n = cols.length * 2;
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
