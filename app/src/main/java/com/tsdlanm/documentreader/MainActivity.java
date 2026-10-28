package com.tsdlanm.documentreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Home screen: an "Open file" button and the recently opened documents. */
public final class MainActivity extends Activity {
    private static final int REQUEST_OPEN = 1;
    private static final int MENU_CLEAR = 1;
    private static final String GITHUB = "https://github.com/tsdlanm";

    static final String[] MIME_TYPES = {
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
    };

    private final List<RecentFiles.Item> items = new ArrayList<>();
    private RecentAdapter adapter;
    private TextView emptyView;
    private TextView recentLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Start fetching the Office-compatible fonts before a document is opened.
        com.tsdlanm.documentreader.docx.FontMatch.prefetch(this);
        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(pad, pad, pad, pad);
        header.setBackgroundColor(getColor(R.color.surface));

        Button open = new Button(this);
        open.setText(R.string.open_file);
        open.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        open.setTextColor(getColor(R.color.on_accent));
        open.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.accent));
        bg.setCornerRadius(8 * d);
        open.setBackground(bg);
        open.setMinHeight(Math.round(52 * d));
        open.setOnClickListener(v -> pickFile());
        header.addView(open, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView formats = new TextView(this);
        formats.setText(R.string.supported_formats);
        formats.setTextColor(getColor(R.color.text_secondary));
        formats.setGravity(Gravity.CENTER_HORIZONTAL);
        formats.setPadding(0, Math.round(8 * d), 0, 0);
        header.addView(formats);
        root.addView(header);

        recentLabel = new TextView(this);
        recentLabel.setText(R.string.recent_files);
        recentLabel.setTypeface(Typeface.DEFAULT_BOLD);
        recentLabel.setTextColor(getColor(R.color.accent));
        recentLabel.setPadding(pad, pad, pad, Math.round(4 * d));
        root.addView(recentLabel);

        ListView list = new ListView(this);
        adapter = new RecentAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> openUri(items.get(position).uri));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmRemove(items.get(position));
            return true;
        });
        emptyView = new TextView(this);
        emptyView.setText(R.string.no_recent);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(getColor(R.color.text_secondary));
        emptyView.setPadding(pad * 2, pad * 2, pad * 2, pad * 2);
        root.addView(emptyView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Credit line, linking to the author's GitHub.
        TextView credit = new TextView(this);
        credit.setText(R.string.credit);
        credit.setTextColor(getColor(R.color.text_secondary));
        credit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(pad, Math.round(10 * d), pad, Math.round(14 * d));
        credit.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB)));
            } catch (ActivityNotFoundException ignored) {
                // No browser installed.
            }
        });
        root.addView(credit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        items.clear();
        items.addAll(RecentFiles.load(this));
        adapter.notifyDataSetChanged();
        boolean empty = items.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        recentLabel.setVisibility(empty ? View.GONE : View.VISIBLE);
        invalidateOptionsMenu();
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, MIME_TYPES);
        try {
            startActivityForResult(i, REQUEST_OPEN);
        } catch (ActivityNotFoundException e) {
            // Some stripped-down ROMs ship without the system file picker.
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.addCategory(Intent.CATEGORY_OPENABLE);
            g.setType("*/*");
            g.putExtra(Intent.EXTRA_MIME_TYPES, MIME_TYPES);
            try {
                startActivityForResult(Intent.createChooser(g, getString(R.string.open_file)), REQUEST_OPEN);
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, R.string.error_no_picker, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // GET_CONTENT results cannot be persisted; the file then stays out of the recent list.
        }
        openUri(uri);
    }

    private void openUri(Uri uri) {
        Intent i = new Intent(this, ViewerActivity.class);
        i.setData(uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
    }

    private void confirmRemove(final RecentFiles.Item item) {
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(new CharSequence[]{getString(R.string.remove_recent)}, (dialog, which) -> {
                    RecentFiles.remove(this, item.uri);
                    try {
                        getContentResolver().releasePersistableUriPermission(item.uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) {
                    }
                    refresh();
                })
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (!items.isEmpty()) menu.add(0, MENU_CLEAR, 0, R.string.clear_recent);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_CLEAR) {
            for (RecentFiles.Item it : items) {
                try {
                    getContentResolver().releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                }
            }
            RecentFiles.clear(this);
            refresh();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final class RecentAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
            RecentFiles.Item it = items.get(position);
            TextView t1 = v.findViewById(android.R.id.text1);
            TextView t2 = v.findViewById(android.R.id.text2);
            t1.setText(it.name);
            t1.setSingleLine(true);
            t1.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            String type = DocumentType.labelFor(it.name, null);
            CharSequence when = DateUtils.getRelativeTimeSpanString(it.time, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS);
            t2.setText(type.isEmpty() ? when : type + "  ·  " + when);
            t1.setTextColor(getColor(R.color.text_primary));
            t2.setTextColor(getColor(R.color.text_secondary));
            return v;
        }
    }
}
