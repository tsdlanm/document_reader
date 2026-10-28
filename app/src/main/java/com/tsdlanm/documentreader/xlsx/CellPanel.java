package com.tsdlanm.documentreader.xlsx;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tsdlanm.documentreader.R;

/**
 * Bottom panel for the tapped cell. Collapsed it shows the cell reference and one line of the
 * text; tapping it opens the whole text, scrollable and selectable, with a copy button.
 */
public final class CellPanel extends LinearLayout {
    private final float density;
    private final TextView ref;
    private final TextView line;
    private final TextView arrow;
    private final TextView copy;
    private final ScrollView scroll;
    private final TextView full;
    private boolean expanded;
    private String text = "";

    public CellPanel(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setBackgroundColor(Color.WHITE);
        setElevation(8 * density);
        setVisibility(GONE);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(10), dp(8), dp(10));
        header.setOnClickListener(v -> setExpanded(!expanded));

        ref = new TextView(context);
        ref.setTypeface(Typeface.DEFAULT_BOLD);
        ref.setTextColor(0xFF1A73E8);
        ref.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0xFFE8F0FE);
        chip.setCornerRadius(6 * density);
        ref.setBackground(chip);
        ref.setPadding(dp(8), dp(3), dp(8), dp(3));
        header.addView(ref);

        line = new TextView(context);
        line.setSingleLine(true);
        line.setEllipsize(TextUtils.TruncateAt.END);
        line.setTextColor(0xFF202124);
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        line.setPadding(dp(10), 0, dp(6), 0);
        header.addView(line, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        copy = new TextView(context);
        copy.setText(android.R.string.copy);
        copy.setTextColor(0xFF1A73E8);
        copy.setTypeface(Typeface.DEFAULT_BOLD);
        copy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        copy.setPadding(dp(10), dp(6), dp(10), dp(6));
        copy.setOnClickListener(v -> copyText());
        copy.setVisibility(GONE);
        header.addView(copy);

        arrow = new TextView(context);
        arrow.setTextColor(0xFF5F6368);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        arrow.setPadding(dp(8), 0, dp(4), 0);
        header.addView(arrow);
        addView(header);

        full = new TextView(context);
        full.setTextIsSelectable(true);
        full.setTextColor(0xFF202124);
        full.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        full.setLineSpacing(0, 1.15f);
        full.setPadding(dp(16), 0, dp(16), dp(14));
        scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                // At most 40% of the screen, so the sheet stays in view.
                int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.4f);
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(full);
        scroll.setVisibility(GONE);
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setExpanded(false);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Shows a cell, collapsed to one line. */
    public void show(String cellRef, String cellText) {
        text = cellText == null ? "" : cellText;
        ref.setText(cellRef);
        // One line: line breaks inside the cell read as spaces.
        line.setText(text.replace('\n', ' '));
        full.setText(text);
        setVisibility(VISIBLE);
        setExpanded(false);
    }

    public void hide() {
        setExpanded(false);
        setVisibility(GONE);
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expand) {
        expanded = expand && !text.isEmpty();
        line.setVisibility(expanded ? INVISIBLE : VISIBLE);
        scroll.setVisibility(expanded ? VISIBLE : GONE);
        copy.setVisibility(expanded ? VISIBLE : GONE);
        arrow.setVisibility(text.isEmpty() ? GONE : VISIBLE);
        arrow.setText(expanded ? "▾" : "▴");
        if (expanded) scroll.scrollTo(0, 0);
    }

    private void copyText() {
        if (text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("cell", text));
        // Android 13+ shows its own confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(getContext(), R.string.copied, Toast.LENGTH_SHORT).show();
    }

    /** Whether the panel is showing a cell. */
    public boolean isOpen() {
        return getVisibility() == VISIBLE;
    }
}
