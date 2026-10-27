package com.tsdlanm.documentreader;

import android.Manifest;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.tsdlanm.documentreader.docx.DocModel;
import com.tsdlanm.documentreader.docx.DocPageView;
import com.tsdlanm.documentreader.docx.DocxAdapter;
import com.tsdlanm.documentreader.docx.DocxParser;
import com.tsdlanm.documentreader.docx.PageLayout;
import com.tsdlanm.documentreader.pdf.PdfEngine;
import com.tsdlanm.documentreader.pdf.PdfView;
import com.tsdlanm.documentreader.util.AndroidXml;
import com.tsdlanm.documentreader.util.Tasks;
import com.tsdlanm.documentreader.xlsx.SheetData;
import com.tsdlanm.documentreader.xlsx.SheetView;
import com.tsdlanm.documentreader.xlsx.XlsxWorkbook;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/** Opens one document and shows it with the matching viewer. */
public final class ViewerActivity extends Activity {
    private static final int REQUEST_STORAGE = 2;
    private static final int MENU_GO_TO = 1, MENU_BIGGER = 2, MENU_SMALLER = 3, MENU_MODE = 4;

    /** Everything produced by the background load; owned by the activity once delivered. */
    private static final class Loaded {
        DocumentType type = DocumentType.UNKNOWN;
        int error;
        boolean notFound;
        PdfEngine pdf;
        ZipFile zip;
        File temp;
        DocModel.Document doc;
        /** Print layout of {@link #doc} (laid out after loading); null if it failed or is empty. */
        PageLayout pages;
        XlsxWorkbook workbook;
        SheetData firstSheet;
    }

    private Uri uri;
    private String name;
    private FrameLayout root;
    private ProgressBar progress;
    private TextView message;
    private boolean destroyed;

    private Loaded loaded;
    private PdfView pdfView;
    private DocPageView docPages;
    private ListView docList;
    private DocxAdapter docAdapter;
    private SheetView sheetView;
    private TextView cellBar;
    private TextView sheetMessage;
    private LinearLayout tabs;
    private int currentSheet;
    private int sheetToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uri = uriFrom(getIntent());
        if (uri == null) {
            finish();
            return;
        }
        ActionBar bar = getActionBar();
        if (bar != null) bar.setDisplayHomeAsUpEnabled(true);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFFEEEEEE);
        progress = new ProgressBar(this);
        root.addView(progress, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        message = new TextView(this);
        message.setGravity(Gravity.CENTER);
        message.setTextColor(0xFF616161);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        int pad = dp(32);
        message.setPadding(pad, pad, pad, pad);
        message.setVisibility(View.GONE);
        root.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);

        name = FileSource.displayName(this, uri);
        setTitle(TextUtils.isEmpty(name) ? getString(R.string.app_name) : name);

        if (needsStoragePermission()) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_STORAGE);
        } else {
            load();
        }
    }

    private static Uri uriFrom(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) return (Uri) stream;
        }
        return intent.getData();
    }

    private boolean needsStoragePermission() {
        return "file".equals(uri.getScheme()) && Build.VERSION.SDK_INT <= 32
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_STORAGE) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) load();
        else showMessage(getString(R.string.error_permission));
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------- loading

    private void load() {
        progress.setVisibility(View.VISIBLE);
        message.setVisibility(View.GONE);
        final Context app = getApplicationContext();
        final Uri target = uri;
        final String label = DocumentType.labelFor(name, getIntent().getType());
        Tasks.io().execute(() -> {
            final Loaded r = loadInBackground(app, target, label);
            Tasks.main(() -> onLoaded(r));
        });
    }

    private static Loaded loadInBackground(Context ctx, Uri uri, String label) {
        Loaded r = new Loaded();
        ContentResolver cr = ctx.getContentResolver();
        try {
            byte[] head = new byte[1024];
            int n = FileSource.readHead(cr, uri, head);
            r.type = DocumentType.sniff(head, n);
            switch (r.type) {
                case PDF:
                    openPdf(ctx, uri, r);
                    break;
                case ZIP:
                    r.temp = FileSource.localFile(ctx, uri);
                    FileSource.clearCache(ctx, r.temp);
                    r.zip = new ZipFile(r.temp);
                    if (DocxParser.isDocx(r.zip)) {
                        r.type = DocumentType.DOCX;
                        r.doc = DocxParser.parse(r.zip, AndroidXml.INSTANCE);
                        // Pages are laid out later, while the first ones are already shown.
                        if (!r.doc.blocks.isEmpty()) r.pages = PageLayout.create(r.doc);
                    } else if (XlsxWorkbook.isXlsx(r.zip)) {
                        r.type = DocumentType.XLSX;
                        r.workbook = XlsxWorkbook.open(r.zip, AndroidXml.INSTANCE);
                        if (!r.workbook.sheets.isEmpty()) r.firstSheet = r.workbook.loadSheet(0, maxCells());
                    } else {
                        r.error = R.string.error_unsupported;
                    }
                    break;
                case LEGACY_OFFICE:
                    r.error = "Excel".equals(label) ? R.string.error_legacy_xls : R.string.error_legacy_doc;
                    break;
                default:
                    r.error = R.string.error_unsupported;
            }
        } catch (FileNotFoundException | SecurityException e) {
            r.error = R.string.error_not_found;
            r.notFound = true;
        } catch (OutOfMemoryError e) {
            r.error = R.string.error_too_large;
        } catch (Exception e) {
            r.error = R.string.error_open;
        }
        if (r.error != 0) closeLoaded(ctx, r);
        return r;
    }

    private static void openPdf(Context ctx, Uri uri, Loaded r) throws IOException {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
        } catch (FileNotFoundException | SecurityException e) {
            // Fall through to the copy below.
        }
        if (pfd != null) {
            try {
                r.pdf = PdfEngine.open(pfd);
                return;
            } catch (SecurityException e) {
                r.error = R.string.error_pdf_password;
                closeQuietly(pfd);
                return;
            } catch (IOException | RuntimeException e) {
                // Often a non-seekable stream (e.g. from a messaging app); retry on a local copy.
                closeQuietly(pfd);
            }
        }
        r.temp = FileSource.localFile(ctx, uri);
        FileSource.clearCache(ctx, r.temp);
        ParcelFileDescriptor local = ParcelFileDescriptor.open(r.temp, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            r.pdf = PdfEngine.open(local);
        } catch (SecurityException e) {
            closeQuietly(local);
            r.error = R.string.error_pdf_password;
        } catch (IOException | RuntimeException e) {
            closeQuietly(local);
            throw e;
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        try {
            pfd.close();
        } catch (IOException ignored) {
        }
    }

    /** Cells kept in memory per sheet, scaled to the heap so low-RAM phones do not run out. */
    private static long maxCells() {
        long perCell = 48;
        return Math.max(50_000L, Math.min(3_000_000L, Runtime.getRuntime().maxMemory() / 4 / perCell));
    }

    private static void closeLoaded(Context ctx, Loaded r) {
        if (r.pdf != null) r.pdf.close();
        if (r.zip != null) {
            try {
                r.zip.close();
            } catch (IOException ignored) {
            }
        }
        if (r.temp != null && FileSource.isCacheCopy(ctx, r.temp)) {
            //noinspection ResultOfMethodCallIgnored
            r.temp.delete();
        }
        r.pdf = null;
        r.zip = null;
        r.doc = null;
        r.pages = null;
        r.workbook = null;
        r.firstSheet = null;
    }

    private void onLoaded(Loaded r) {
        if (destroyed) {
            closeLoaded(getApplicationContext(), r);
            return;
        }
        loaded = r;
        progress.setVisibility(View.GONE);
        if (r.error != 0) {
            if (r.notFound) RecentFiles.remove(this, uri);
            showMessage(getString(r.error));
            return;
        }
        rememberRecent();
        switch (r.type) {
            case PDF:
                showPdf(r.pdf);
                break;
            case DOCX:
                showDocx(r);
                break;
            case XLSX:
                showXlsx(r);
                break;
            default:
                break;
        }
        invalidateOptionsMenu();
    }

    private void rememberRecent() {
        boolean persisted = "file".equals(uri.getScheme());
        if (!persisted) {
            for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(uri) && p.isReadPermission()) {
                    persisted = true;
                    break;
                }
            }
        }
        if (!persisted) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                persisted = true;
            } catch (SecurityException ignored) {
                // Temporary access only (e.g. opened from another app): cannot reopen later.
            }
        }
        if (persisted) RecentFiles.add(this, uri, TextUtils.isEmpty(name) ? uri.toString() : name);
    }

    private void showMessage(String text) {
        progress.setVisibility(View.GONE);
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    // ----------------------------------------------------------------- PDF

    private void showPdf(final PdfEngine engine) {
        pdfView = new PdfView(this);
        pdfView.setEngine(engine);
        pdfView.setOnPageChangeListener(page -> {
            ActionBar bar = getActionBar();
            if (bar != null) bar.setSubtitle(getString(R.string.page_of, page + 1, engine.pageCount));
        });
        root.addView(pdfView, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void askPage() {
        if (pdfView == null && docPages == null) return;
        final int count = pdfView != null ? pdfView.getPageCount() : docPages.getPageCount();
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(getString(R.string.page_hint, count));
        FrameLayout box = new FrameLayout(this);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.go_to_page)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    try {
                        int page = Math.max(1, Math.min(count, Integer.parseInt(input.getText().toString().trim())));
                        if (pdfView != null) pdfView.goToPage(page - 1);
                        else if (docPages != null) docPages.goToPage(page - 1);
                    } catch (NumberFormatException ignored) {
                    }
                })
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
    }

    // ---------------------------------------------------------------- DOCX

    private void showDocx(Loaded r) {
        if (r.doc.blocks.isEmpty()) {
            showMessage(getString(R.string.empty_document));
            return;
        }
        if (r.pages != null) showDocPages();
        else showDocList();
    }

    /** Pages laid out so far (UI thread), kept for switching back from reading mode. */
    private final List<PageLayout.Page> laidOut = new ArrayList<>();
    private boolean layoutStarted, layoutDone;

    /** Print layout: pages like in Word. */
    private void showDocPages() {
        docPages = new DocPageView(this);
        docPages.setDocument(loaded.pages, loaded.zip);
        docPages.addPages(laidOut);
        docPages.setOnPageChangeListener(page -> updatePageSubtitle());
        root.addView(docPages, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (!layoutStarted) startLayout(loaded.pages);
    }

    /** Lays the pages out in the background, showing each batch as soon as it is ready. */
    private void startLayout(final PageLayout l) {
        layoutStarted = true;
        Tasks.io().execute(() -> {
            try {
                l.layout(batch -> Tasks.main(() -> {
                    if (destroyed || loaded == null || loaded.pages != l) return;
                    laidOut.addAll(batch);
                    if (docPages != null) docPages.addPages(batch);
                    updatePageSubtitle();
                    invalidateOptionsMenu();
                }));
                Tasks.main(() -> {
                    if (destroyed || loaded == null || loaded.pages != l) return;
                    layoutDone = true;
                    updatePageSubtitle();
                });
            } catch (RuntimeException | OutOfMemoryError e) {
                // Page layout failed: fall back to the flowing reading mode.
                Tasks.main(() -> {
                    if (destroyed || loaded == null || loaded.pages != l) return;
                    if (docPages != null) toggleDocMode();
                    loaded.pages = null;
                    invalidateOptionsMenu();
                });
            }
        });
    }

    private void updatePageSubtitle() {
        ActionBar bar = getActionBar();
        if (bar == null || docPages == null || laidOut.isEmpty()) return;
        String text = getString(R.string.page_of, docPages.getCurrentPage() + 1, laidOut.size());
        // More pages are still on the way.
        bar.setSubtitle(layoutDone ? text : text + "…");
    }

    /** Switches between print layout and the flowing reading mode. */
    private void toggleDocMode() {
        if (docPages != null) {
            root.removeView(docPages);
            docPages.release();
            docPages = null;
            ActionBar bar = getActionBar();
            if (bar != null) bar.setSubtitle(null);
            showDocList();
        } else if (loaded.pages != null) {
            root.removeView(docList);
            if (docAdapter != null) docAdapter.release();
            docList = null;
            docAdapter = null;
            showDocPages();
        }
        invalidateOptionsMenu();
    }

    /** Reading mode: text reflowed to the screen width. */
    private void showDocList() {
        docList = new ListView(this);
        docList.setBackgroundColor(Color.WHITE);
        docList.setDivider(null);
        docList.setSelector(android.R.color.transparent);
        docList.setFastScrollEnabled(true);
        docList.setClipToPadding(false);
        int pad = dp(14);
        docList.setPadding(pad, dp(10), pad, dp(24));
        docList.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        root.addView(docList, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        bindDocAdapter(1f);
    }

    private void bindDocAdapter(float scale) {
        int pad = dp(14);
        int width = getResources().getDisplayMetrics().widthPixels - 2 * pad;
        int first = docList.getFirstVisiblePosition();
        View top = docList.getChildAt(0);
        int offset = top == null ? 0 : top.getTop() - docList.getPaddingTop();
        if (docAdapter != null) docAdapter.release();
        docAdapter = new DocxAdapter(this, loaded.doc, loaded.zip, width);
        docAdapter.setTextScale(scale);
        docList.setAdapter(docAdapter);
        docList.setSelectionFromTop(first, offset);
    }

    // ---------------------------------------------------------------- XLSX

    private void showXlsx(Loaded r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);

        cellBar = new TextView(this);
        cellBar.setSingleLine(true);
        cellBar.setEllipsize(TextUtils.TruncateAt.END);
        cellBar.setTextColor(0xFF202124);
        cellBar.setBackgroundColor(0xFFF8F9FA);
        cellBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        cellBar.setText(R.string.tap_cell);
        box.addView(cellBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout sheetArea = new FrameLayout(this);
        sheetView = new SheetView(this);
        sheetView.setOnCellSelectedListener((row, col, text) -> {
            String ref = SheetData.columnName(col) + (row + 1);
            cellBar.setText(text == null || text.isEmpty() ? ref : ref + "   " + text);
        });
        sheetArea.addView(sheetView);
        sheetMessage = new TextView(this);
        sheetMessage.setGravity(Gravity.CENTER);
        sheetMessage.setTextColor(0xFF757575);
        sheetMessage.setText(R.string.sheet_empty);
        sheetMessage.setVisibility(View.GONE);
        sheetArea.addView(sheetMessage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        box.addView(sheetArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (r.workbook.sheets.size() > 1) {
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setBackgroundColor(0xFFF1F3F4);
            tabs = new LinearLayout(this);
            tabs.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < r.workbook.sheets.size(); i++) {
                final int index = i;
                TextView tab = new TextView(this);
                tab.setText(r.workbook.sheets.get(i).name);
                tab.setPadding(dp(16), dp(12), dp(16), dp(12));
                tab.setOnClickListener(v -> selectSheet(index));
                tabs.addView(tab);
            }
            scroll.addView(tabs);
            box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        root.addView(box, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        if (r.workbook.sheets.isEmpty()) {
            sheetMessage.setVisibility(View.VISIBLE);
        } else {
            showSheet(0, r.firstSheet);
            r.firstSheet = null;
        }
    }

    private void selectSheet(final int index) {
        if (index == currentSheet && sheetToken > 0) return;
        final int token = ++sheetToken;
        currentSheet = index;
        highlightTab();
        sheetView.setData(null);
        sheetMessage.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        progress.bringToFront();
        final XlsxWorkbook wb = loaded.workbook;
        Tasks.io().execute(() -> {
            SheetData data = null;
            try {
                data = wb.loadSheet(index, maxCells());
            } catch (Exception | OutOfMemoryError ignored) {
                // Shown as an empty sheet with an error below.
            }
            final SheetData result = data;
            Tasks.main(() -> {
                if (destroyed || token != sheetToken) return;
                progress.setVisibility(View.GONE);
                if (result == null) {
                    sheetMessage.setText(R.string.error_open);
                    sheetMessage.setVisibility(View.VISIBLE);
                    return;
                }
                showSheet(index, result);
            });
        });
    }

    private void showSheet(int index, SheetData data) {
        currentSheet = index;
        if (sheetToken == 0) sheetToken = 1;
        highlightTab();
        sheetView.setData(data);
        cellBar.setText(R.string.tap_cell);
        sheetMessage.setText(R.string.sheet_empty);
        sheetMessage.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
        if (data.truncated) Toast.makeText(this, R.string.sheet_truncated, Toast.LENGTH_LONG).show();
    }

    private void highlightTab() {
        if (tabs == null) return;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView t = (TextView) tabs.getChildAt(i);
            boolean sel = i == currentSheet;
            t.setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setTextColor(sel ? 0xFF1565C0 : 0xFF5F6368);
            t.setBackgroundColor(sel ? Color.WHITE : 0x00000000);
        }
    }

    // ---------------------------------------------------------------- menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if ((pdfView != null && pdfView.getPageCount() > 1) || (docPages != null && docPages.getPageCount() > 1)) {
            menu.add(0, MENU_GO_TO, 0, R.string.go_to_page).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
        if (docAdapter != null) {
            menu.add(0, MENU_SMALLER, 0, R.string.text_smaller).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            menu.add(0, MENU_BIGGER, 0, R.string.text_bigger).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
        if (loaded != null && loaded.pages != null && (docPages != null || docList != null)) {
            menu.add(0, MENU_MODE, 0, docPages != null ? R.string.reading_mode : R.string.page_mode)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            case MENU_GO_TO:
                askPage();
                return true;
            case MENU_MODE:
                toggleDocMode();
                return true;
            case MENU_BIGGER:
                if (docAdapter != null) docAdapter.setTextScale(docAdapter.getTextScale() + 0.15f);
                return true;
            case MENU_SMALLER:
                if (docAdapter != null) docAdapter.setTextScale(docAdapter.getTextScale() - 0.15f);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Rotation is handled in place (no reload); tables and images need the new width.
        if (docList != null && docAdapter != null) bindDocAdapter(docAdapter.getTextScale());
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (pdfView != null) pdfView.release();
        if (docPages != null) docPages.release();
        if (docAdapter != null) docAdapter.release();
        if (loaded != null) closeLoaded(getApplicationContext(), loaded);
        super.onDestroy();
    }
}
