package com.ibrata2.ibradl;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public final class MainActivity extends Activity {

    static final String EXTRA_TAB = "tab";
    static final String EXTRA_OPEN_PLAYER = "openPlayer";
    static final int TAB_HOME = 0, TAB_LIBRARY = 1, TAB_PLAYLISTS = 2, TAB_DOWNLOADS = 3, TAB_ABOUT = 4;

    static final int BG = 0xFF0B0B0D, CARD = 0xFF16161A, CARD2 = 0xFF202026, GOLD = 0xFFD4AF37, GOLD_DIM = 0x26D4AF37,
            TEXT = 0xFFF5F5F5, MUTED = 0xFF9A9AA2, LINE = 0x1FFFFFFF;

    static final String SITE = "https://ibraprogram.netlify.app/";
    static final String TELEGRAM = "https://t.me/ibraprogram";
    static final String TIKTOK = "https://www.tiktok.com/@ibraprogram";

    private static final String[] GENRES = {"اغاني عراقية جديدة", "اغاني عربية", "اغاني خليجية", "ردح عراقي", "اغاني حزينة",
            "ريمكس عربي", "اغاني تركية", "English songs", "قرآن كريم", "اناشيد"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private Store store;
    private MediaController ctl;
    private ListenableFuture<MediaController> ctlFuture;

    private FrameLayout content;
    private LinearLayout nav, mini;
    private TextView headerTitle, dlBadge;
    private LinearLayout headerBar, fullCol, mainCol;
    private int tab = -1;

    // search state (kept while switching tabs)
    private final List<Song> results = new ArrayList<>();
    private String lastQuery = "";
    private boolean searching;
    private String searchError = "";

    // library state
    private boolean libDownloads = true;
    private final List<Song> libSongs = new ArrayList<>();
    private String openPlaylist;     // playlist detail page, or "recent"

    // mini player
    private ImageView miniArt, miniPlay;
    private TextView miniTitle, miniSub;
    private ProgressBar miniProgress;
    private String miniFor = "";

    // full player
    private FrameLayout full;
    private ImageView fArt, fPlay, fRepeat, fShuffle, fFav;
    private TextView fTitle, fArtist, fPos, fDur, fSleep;
    private SeekBar fSeek;
    private boolean seeking;
    private String fullFor = "";

    private RecyclerView.Adapter<?> activeAdapter;
    private Runnable pageTick;
    private int seenDlVersion = -1;

    // ================================================================== lifecycle
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        View rootView = buildRoot();
        setContentView(rootView);
        applyInsets(rootView);
        Yt.prepare(this);
        askPermissions();
        int t = getIntent().getIntExtra(EXTRA_TAB, TAB_HOME);
        showTab(t);
        handleIntent(getIntent());
        ui.post(tick);
        ui.postDelayed(() -> Updater.check(this, true), 4000);   // quiet check on start
    }

    @Override protected void onStart() {
        super.onStart();
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        ctlFuture = new MediaController.Builder(this, token).buildAsync();
        ctlFuture.addListener(() -> {
            try {
                ctl = ctlFuture.get();
                ctl.addListener(new Player.Listener() {
                    @Override public void onEvents(@NonNull Player p, @NonNull Player.Events e) { refreshPlayer(); }
                });
                refreshPlayer();
                if (getIntent().getBooleanExtra(EXTRA_OPEN_PLAYER, false)) { getIntent().removeExtra(EXTRA_OPEN_PLAYER); openFull(); }
            } catch (Exception ignored) { }
        }, MoreExecutors.directExecutor());
    }

    @Override protected void onStop() {
        if (ctlFuture != null) MediaController.releaseFuture(ctlFuture);
        ctl = null;
        super.onStop();
    }

    @Override protected void onDestroy() { ui.removeCallbacksAndMessages(null); super.onDestroy(); }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (i.hasExtra(EXTRA_TAB)) showTab(i.getIntExtra(EXTRA_TAB, TAB_HOME));
        if (i.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) { i.removeExtra(EXTRA_OPEN_PLAYER); openFull(); }
        handleIntent(i);
    }

    @Override public void onBackPressed() {
        if (full.getVisibility() == View.VISIBLE) { closeFull(); return; }
        if (tab == TAB_PLAYLISTS && openPlaylist != null) { openPlaylist = null; showTab(TAB_PLAYLISTS, true); return; }
        if (tab != TAB_HOME) { showTab(TAB_HOME); return; }
        moveTaskToBack(true);   // keep the music going, like pressing Home
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP)
            android.util.Log.i("IBRADL", "touch up " + (int) ev.getX() + "," + (int) ev.getY() + " fullVisible=" + (full.getVisibility() == View.VISIBLE));
        return super.dispatchTouchEvent(ev);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
            if (pageTick != null) pageTick.run();
            int active = 0;
            synchronized (Yt.jobs) { for (Yt.Job j : Yt.jobs) if (j.active()) active++; }
            dlBadge.setVisibility(active > 0 ? View.VISIBLE : View.GONE);
            dlBadge.setText(String.valueOf(active));
            ui.postDelayed(this, 500);
        }
    };

    private void askPermissions() {
        List<String> want = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            want.add(Manifest.permission.POST_NOTIFICATIONS);
            want.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            want.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= 29) want.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        List<String> missing = new ArrayList<>();
        for (String p : want) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 7);
    }

    @Override public void onRequestPermissionsResult(int code, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (tab == TAB_LIBRARY) showTab(TAB_LIBRARY, true);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            String url = Yt.extractUrl(i.getStringExtra(Intent.EXTRA_TEXT));
            i.setAction(null);
            if (url != null) openLink(url);
        } else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            Uri u = i.getData();
            i.setAction(null);
            List<Song> one = new ArrayList<>();
            one.add(new Song(u.toString(), displayName(u), "", "", "file".equals(u.getScheme()) ? u.getPath() : "", 0));
            ui.postDelayed(() -> play(one, 0), 600);
        }
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return stripExt(c.getString(0));
        } catch (Exception ignored) { }
        String s = u.getLastPathSegment();
        return s == null ? "ملف صوتي" : stripExt(s);
    }

    // ================================================================== root layout
    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        mainCol = col;
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(14), dp(18), dp(8));
        headerBar = header;
        TextView logo = tv("IBRA DL", 22, GOLD, true);
        logo.setLetterSpacing(0.06f);
        logo.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        header.addView(logo);
        View sp = new View(this);
        header.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        headerTitle = tv("", 15, MUTED, false);
        header.addView(headerTitle);
        ImageView hu = iconBtn("refresh", GOLD, 38, v -> { toast("جاري البحث عن تحديث…"); Updater.check(this, false); });
        header.addView(hu);
        col.addView(header);

        content = new FrameLayout(this);
        col.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        col.addView(buildMini());

        nav = new LinearLayout(this);
        nav.setBackgroundColor(0xFF111114);
        nav.setPadding(0, dp(6), 0, dp(6));
        String[][] tabs = {{"home", "الرئيسية"}, {"library", "مكتبتي"}, {"playlist", "القوائم"}, {"download", "التحميلات"}, {"info", "حول"}};
        for (int k = 0; k < tabs.length; k++) {
            final int idx = k;
            FrameLayout cell = new FrameLayout(this);
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(new Icon(tabs[k][0], MUTED));
            b.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView l = tv(tabs[k][1], 11, MUTED, false);
            l.setGravity(Gravity.CENTER);
            b.addView(l);
            cell.addView(b, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            if (k == TAB_DOWNLOADS) {
                dlBadge = tv("", 10, Color.BLACK, true);
                dlBadge.setGravity(Gravity.CENTER);
                dlBadge.setBackground(round(GOLD, 9));
                dlBadge.setVisibility(View.GONE);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                lp.setMargins(dp(22), 0, 0, 0);
                cell.addView(dlBadge, lp);
            }
            cell.setOnClickListener(v -> { if (idx == TAB_PLAYLISTS) openPlaylist = null; showTab(idx, true); });
            nav.addView(cell, new LinearLayout.LayoutParams(0, dp(52), 1));
        }
        col.addView(nav);

        full = buildFull();
        full.setVisibility(View.GONE);
        root.addView(full, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    /**
     * Draw edge-to-edge and keep our bars clear of the phone's status bar, its navigation
     * buttons / gesture bar and the keyboard (Android 15 no longer does this for us).
     */
    private void applyInsets(View root) {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, in) -> {
            androidx.core.graphics.Insets bars = in.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            int ime = in.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
            boolean kb = ime > bars.bottom;
            headerBar.setPadding(dp(18) + bars.left, dp(14) + bars.top, dp(18) + bars.right, dp(8));
            // keyboard open: lift the whole page above it and hide the tab bar
            mainCol.setPadding(0, 0, 0, kb ? ime : 0);
            nav.setPadding(bars.left, dp(6), bars.right, dp(6) + bars.bottom);
            nav.setVisibility(kb ? View.GONE : View.VISIBLE);
            fullCol.setPadding(dp(22) + bars.left, dp(12) + bars.top, dp(22) + bars.right, dp(22) + bars.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
    }

    private void showTab(int t) { showTab(t, false); }

    private void showTab(int t, boolean force) {
        if (t == tab && !force) return;
        tab = t;
        pageTick = null;
        activeAdapter = null;
        for (int k = 0; k < nav.getChildCount(); k++) {
            boolean on = k == t;
            LinearLayout b = (LinearLayout) ((FrameLayout) nav.getChildAt(k)).getChildAt(0);
            ImageView ic = (ImageView) b.getChildAt(0);
            String[] names = {"home", "library", "playlist", "download", "info"};
            ic.setImageDrawable(new Icon(names[k], on ? GOLD : MUTED));
            ((TextView) b.getChildAt(1)).setTextColor(on ? GOLD : MUTED);
        }
        content.removeAllViews();
        View page;
        switch (t) {
            case TAB_LIBRARY: page = pageLibrary(); headerTitle.setText("مكتبتي"); break;
            case TAB_PLAYLISTS: page = openPlaylist == null ? pagePlaylists() : pagePlaylist(openPlaylist); headerTitle.setText("قوائم التشغيل"); break;
            case TAB_DOWNLOADS: page = pageDownloads(); headerTitle.setText("التحميلات"); break;
            case TAB_ABOUT: page = pageAbout(); headerTitle.setText("حول التطبيق"); break;
            default: page = pageHome(); headerTitle.setText("بحث واستماع"); break;
        }
        content.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    // ================================================================== home / search
    private EditText searchBox;
    private TextView homeStatus;
    private ProgressBar homeSpin;
    private SongAdapter homeAdapter;

    private View pageHome() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(round(CARD2, 26));
        bar.setPadding(dp(14), 0, dp(6), 0);
        ImageView si = new ImageView(this);
        si.setImageDrawable(new Icon("search", MUTED));
        bar.addView(si, new LinearLayout.LayoutParams(dp(22), dp(22)));
        searchBox = new EditText(this);
        searchBox.setHint("ابحث عن أغنية أو فنان، أو الصق رابط");
        searchBox.setHintTextColor(MUTED);
        searchBox.setTextColor(TEXT);
        searchBox.setTextSize(15);
        searchBox.setSingleLine(true);
        searchBox.setBackground(null);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setText(lastQuery);
        searchBox.setPadding(dp(10), dp(12), dp(10), dp(12));
        searchBox.setOnEditorActionListener((v, a, e) -> {
            if (a == EditorInfo.IME_ACTION_SEARCH || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { doSearch(v.getText().toString()); return true; }
            return false;
        });
        bar.addView(searchBox, new LinearLayout.LayoutParams(0, -2, 1));
        TextView go = pill("بحث", true);
        go.setOnClickListener(v -> doSearch(searchBox.getText().toString()));
        bar.addView(go);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.setMargins(dp(14), dp(4), dp(14), dp(8));
        col.addView(bar, blp);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(dp(10), 0, dp(10), 0);
        for (String g : GENRES) {
            TextView c = pill(g, g.equals(lastQuery));
            c.setOnClickListener(v -> { searchBox.setText(g); doSearch(g); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(4), 0, dp(4), 0);
            chips.addView(c, lp);
        }
        hs.addView(chips);
        col.addView(hs);

        LinearLayout st = new LinearLayout(this);
        st.setGravity(Gravity.CENTER);
        st.setPadding(dp(16), dp(8), dp(16), dp(4));
        homeSpin = new ProgressBar(this);
        homeSpin.setIndeterminateTintList(ColorStateList.valueOf(GOLD));
        st.addView(homeSpin, new LinearLayout.LayoutParams(dp(22), dp(22)));
        homeStatus = tv("", 13, MUTED, false);
        homeStatus.setPadding(dp(8), 0, dp(8), 0);
        st.addView(homeStatus);
        col.addView(st);

        homeAdapter = new SongAdapter(results, null);
        col.addView(list(homeAdapter), new LinearLayout.LayoutParams(-1, 0, 1));
        activeAdapter = homeAdapter;
        updateHomeStatus();
        // post: never rebuild the page while it is still being built (that stacked two pages and the
        // empty one on top swallowed every tap on the results)
        if (results.isEmpty() && !searching && searchError.isEmpty()) ui.post(() -> doSearch(GENRES[0]));
        return col;
    }

    private void updateHomeStatus() {
        if (homeStatus == null || tab != TAB_HOME) return;
        homeSpin.setVisibility(searching ? View.VISIBLE : View.GONE);
        if (searching) homeStatus.setText(Yt.state == Yt.READY ? "جاري البحث عن «" + lastQuery + "»…" : "جاري تجهيز المحرك لأول مرة… (ثواني)");
        else if (!searchError.isEmpty()) homeStatus.setText("تعذر البحث: " + searchError + "\nتأكد من الإنترنت أو حدّث المحرك من صفحة «حول».");
        else if (!results.isEmpty()) homeStatus.setText(results.size() + " نتيجة — اضغط للاستماع، أو ⋮ للتحميل والإضافة");
        else homeStatus.setText("");
        homeStatus.setVisibility(homeStatus.getText().length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void doSearch(String q) {
        q = q.trim();
        if (q.isEmpty()) return;
        hideKeyboard();
        String url = Yt.extractUrl(q);
        if (url != null) { openLink(url); return; }
        lastQuery = q;
        searching = true;
        searchError = "";
        results.clear();
        if (tab == TAB_HOME && homeAdapter != null) { homeAdapter.notifyDataSetChanged(); updateHomeStatus(); }
        final String query = q;
        Yt.search(this, q, (list, err) -> {
            if (!query.equals(lastQuery)) return;
            searching = false;
            results.clear();
            if (list != null) results.addAll(list);
            searchError = err == null ? "" : err;
            if (tab == TAB_HOME) { homeAdapter.notifyDataSetChanged(); updateHomeStatus(); }
        });
    }

    /** A shared or pasted link: one video → choose play/download, a playlist → list its songs. */
    private void openLink(String url) {
        showTab(TAB_HOME);
        searching = true;
        searchError = "";
        lastQuery = url;
        if (searchBox != null) searchBox.setText(url);
        updateHomeStatus();
        Yt.link(this, url, (list, err) -> {
            searching = false;
            if (list == null || list.isEmpty()) {
                searchError = err == null ? "الرابط غير مدعوم" : err;
                updateHomeStatus();
                return;
            }
            results.clear();
            results.addAll(list);
            if (tab == TAB_HOME) { homeAdapter.notifyDataSetChanged(); updateHomeStatus(); }
            if (list.size() == 1) {
                Song s = list.get(0);
                new AlertDialog.Builder(this, R.style.Dialog)
                        .setTitle(s.title)
                        .setItems(new String[]{"▶  استماع الآن", "⬇  تحميل…", "＋  إضافة إلى قائمة تشغيل…"}, (d, w) -> {
                            if (w == 0) play(list, 0);
                            else if (w == 1) downloadDialog(s);
                            else addToPlaylistDialog(s);
                        }).show();
            }
        });
    }

    // ================================================================== library
    private View pageLibrary() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(dp(14), dp(4), dp(14), dp(6));
        TextView a = pill("تنزيلاتي", libDownloads), b = pill("كل أغاني الجهاز", !libDownloads);
        a.setOnClickListener(v -> { libDownloads = true; showTab(TAB_LIBRARY, true); });
        b.setOnClickListener(v -> { libDownloads = false; showTab(TAB_LIBRARY, true); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, dp(8), 0);
        seg.addView(a, lp);
        seg.addView(b, lp);
        col.addView(seg);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(dp(14), 0, dp(14), dp(6));
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = tv("جاري التحميل…", 13, MUTED, false);
        actions.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
        TextView playAll = pill("▶ تشغيل الكل", true), shuf = pill("عشوائي", false);
        playAll.setOnClickListener(v -> { if (!libSongs.isEmpty()) { setShuffle(false); play(new ArrayList<>(libSongs), 0); } });
        shuf.setOnClickListener(v -> { if (!libSongs.isEmpty()) shufflePlay(new ArrayList<>(libSongs)); });
        actions.addView(playAll, lp);
        actions.addView(shuf);
        col.addView(actions);

        SongAdapter ad = new SongAdapter(libSongs, null);
        ad.libraryMode = true;
        col.addView(list(ad), new LinearLayout.LayoutParams(-1, 0, 1));
        activeAdapter = ad;
        final boolean dls = libDownloads;
        bg.execute(() -> {
            List<Song> found = dls ? scanDownloads() : scanDevice();
            ui.post(() -> {
                if (tab != TAB_LIBRARY || libDownloads != dls) return;
                libSongs.clear();
                libSongs.addAll(found);
                ad.notifyDataSetChanged();
                if (found.isEmpty()) count.setText(dls ? "ما في تنزيلات بعد — ابحث عن أغنية واضغط ⋮ ← تحميل" : "ما لكيت أغاني على الجهاز (تأكد من صلاحية الوصول للصوتيات)");
                else count.setText(found.size() + " أغنية");
            });
        });
        return col;
    }

    private List<Song> scanDownloads() {
        List<Song> out = new ArrayList<>();
        File[] files = Yt.outDir().listFiles();
        if (files == null) return out;
        List<File> list = new ArrayList<>();
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && (Yt.isAudio(n) || n.endsWith(".mp4")) && !n.endsWith(".part")) list.add(f);
        }
        Collections.sort(list, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        for (File f : list) out.add(fileSong(f));
        return out;
    }

    static Song fileSong(File f) {
        String name = stripExt(f.getName()).replaceAll("\\s*\\[[A-Za-z0-9_-]{11}]$", "");
        return new Song(Uri.fromFile(f).toString(), name, "تنزيلاتي", "", f.getAbsolutePath(), 0);
    }

    private List<Song> scanDevice() {
        List<Song> out = new ArrayList<>();
        String[] cols = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA};
        try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                MediaStore.Audio.Media.DURATION + ">= 20000", null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE")) {
            if (c == null) return out;
            while (c.moveToNext()) {
                Uri u = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
                String artist = c.getString(2);
                if (artist == null || "<unknown>".equals(artist)) artist = "";
                out.add(new Song(u.toString(), c.getString(1), artist, "", c.getString(4), c.getLong(3)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ================================================================== playlists
    private View pagePlaylists() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(4), dp(14), 0);
        TextView add = pill("＋  قائمة تشغيل جديدة", true);
        add.setGravity(Gravity.CENTER);
        add.setPadding(dp(16), dp(12), dp(16), dp(12));
        add.setOnClickListener(v -> askText("قائمة تشغيل جديدة", "", "مثلاً: أغاني السيارة", name -> {
            store.create(name);
            showTab(TAB_PLAYLISTS, true);
        }));
        col.addView(add, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(0, dp(8), 0, dp(16));
        for (Store.Playlist p : store.playlists()) {
            items.addView(playlistRow(Store.FAV.equals(p.id) ? "heartFill" : "playlist", p.name, p.songs.size() + " أغنية",
                    v -> { openPlaylist = p.id; showTab(TAB_PLAYLISTS, true); }));
        }
        items.addView(playlistRow("recent", "سمعتها مؤخراً", store.recent().size() + " أغنية",
                v -> { openPlaylist = "recent"; showTab(TAB_PLAYLISTS, true); }));
        sv.addView(items);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return col;
    }

    private View playlistRow(String icon, String name, String sub, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(round(CARD, 14));
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new Icon(icon, GOLD));
        ic.setBackground(round(GOLD_DIM, 12));
        ic.setPadding(dp(10), dp(10), dp(10), dp(10));
        r.addView(ic, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.addView(tv(name, 16, TEXT, true));
        t.addView(tv(sub, 12, MUTED, false));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView ch = new ImageView(this);
        ch.setImageDrawable(new Icon("back", MUTED));
        ch.setRotation(0);
        r.addView(ch, new LinearLayout.LayoutParams(dp(18), dp(18)));
        r.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        r.setLayoutParams(lp);
        return r;
    }

    private View pagePlaylist(String id) {
        boolean recent = "recent".equals(id);
        Store.Playlist p = recent ? null : store.get(id);
        if (!recent && p == null) { openPlaylist = null; return pagePlaylists(); }
        List<Song> songs = recent ? store.recent() : p.songs;
        String name = recent ? "سمعتها مؤخراً" : p.name;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), 0, dp(14), dp(6));
        ImageView back = iconBtn("back", TEXT, 40, v -> { openPlaylist = null; showTab(TAB_PLAYLISTS, true); });
        back.setRotation(180);
        head.addView(back);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(tv(name, 19, TEXT, true));
        t.addView(tv(songs.size() + " أغنية", 12, MUTED, false));
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(iconBtn("more", TEXT, 40, v -> {
            List<String> opts = new ArrayList<>();
            if (recent) opts.add("مسح السجل");
            else {
                opts.add("إعادة تسمية");
                if (!Store.FAV.equals(id)) opts.add("حذف القائمة");
            }
            new AlertDialog.Builder(this, R.style.Dialog).setItems(opts.toArray(new String[0]), (d, w) -> {
                String o = opts.get(w);
                if (o.equals("مسح السجل")) { store.clearRecent(); showTab(TAB_PLAYLISTS, true); }
                else if (o.equals("إعادة تسمية")) askText("إعادة تسمية", name, "", n -> { store.rename(id, n); showTab(TAB_PLAYLISTS, true); });
                else confirm("حذف القائمة «" + name + "»؟", () -> { store.delete(id); openPlaylist = null; showTab(TAB_PLAYLISTS, true); });
            }).show();
        }));
        col.addView(head);

        LinearLayout acts = new LinearLayout(this);
        acts.setPadding(dp(14), 0, dp(14), dp(8));
        TextView pa = pill("▶ تشغيل", true), sh = pill("عشوائي", false);
        pa.setOnClickListener(v -> { if (!songs.isEmpty()) { setShuffle(false); play(new ArrayList<>(songs), 0); } });
        sh.setOnClickListener(v -> { if (!songs.isEmpty()) shufflePlay(new ArrayList<>(songs)); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, dp(8), 0);
        acts.addView(pa, lp);
        acts.addView(sh, lp);
        col.addView(acts);

        if (songs.isEmpty()) {
            TextView e = tv(recent ? "ما سمعت شي بعد." : "القائمة فارغة.\nمن البحث أو مكتبتي اضغط ⋮ ← «إضافة إلى قائمة تشغيل».", 14, MUTED, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(dp(24), dp(40), dp(24), 0);
            col.addView(e);
            return col;
        }
        SongAdapter ad = new SongAdapter(songs, recent ? null : id);
        col.addView(list(ad), new LinearLayout.LayoutParams(-1, 0, 1));
        activeAdapter = ad;
        return col;
    }

    // ================================================================== downloads
    private View pageDownloads() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(4), dp(14), 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(round(CARD2, 26));
        bar.setPadding(dp(14), 0, dp(6), 0);
        EditText link = new EditText(this);
        link.setHint("الصق رابط يوتيوب للتحميل");
        link.setHintTextColor(MUTED);
        link.setTextColor(TEXT);
        link.setTextSize(14);
        link.setSingleLine(true);
        link.setBackground(null);
        link.setPadding(dp(4), dp(12), dp(8), dp(12));
        bar.addView(link, new LinearLayout.LayoutParams(0, -2, 1));
        TextView paste = pill("لصق", false);
        paste.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                link.setText(t);
            }
        });
        TextView go = pill("تحميل", true);
        go.setOnClickListener(v -> {
            String url = Yt.extractUrl(link.getText().toString());
            if (url == null) { toast("الصق رابط صحيح"); return; }
            hideKeyboard();
            toast("جاري قراءة الرابط…");
            Yt.link(this, url, (list, err) -> {
                if (list == null || list.isEmpty()) { toast("تعذر: " + (err == null ? "رابط غير مدعوم" : err)); return; }
                if (list.size() == 1) downloadDialog(list.get(0));
                else downloadAllDialog(list);
            });
        });
        bar.addView(paste);
        bar.addView(go);
        col.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(0, dp(8), 0, dp(4));
        TextView where = tv("المكان: Download/IBRA DL", 12, MUTED, false);
        info.addView(where, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = pill("مسح المنتهية", false);
        clear.setOnClickListener(v -> { Yt.clearFinished(); seenDlVersion = -1; });
        info.addView(clear);
        col.addView(info);

        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(sitesSection());
        TextView mine = tv("تحميلاتك", 15, TEXT, true);
        mine.setPadding(dp(2), dp(16), dp(2), dp(2));
        body.addView(mine);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(0, dp(4), 0, dp(16));
        body.addView(items);
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        Map<Integer, Object[]> live = new HashMap<>();
        seenDlVersion = -1;
        pageTick = () -> {
            if (seenDlVersion != Yt.version) {
                seenDlVersion = Yt.version;
                items.removeAllViews();
                live.clear();
                List<Yt.Job> js;
                synchronized (Yt.jobs) { js = new ArrayList<>(Yt.jobs); }
                if (js.isEmpty()) {
                    TextView e = tv("ما في تحميلات بعد. افتح يوتيوب من الأعلى، شغّل الأغنية واضغط «⬇ تحميل»،\nأو من «الرئيسية» اضغط ⋮ ← تحميل.", 13, MUTED, false);
                    e.setGravity(Gravity.CENTER);
                    e.setPadding(dp(16), dp(12), dp(16), 0);
                    items.addView(e);
                }
                for (Yt.Job j : js) items.addView(jobRow(j, live));
            }
            for (Map.Entry<Integer, Object[]> e : live.entrySet()) {
                Yt.Job j = (Yt.Job) e.getValue()[2];
                ((ProgressBar) e.getValue()[0]).setProgress(Math.round(j.progress));
                ((TextView) e.getValue()[1]).setText(jobStatus(j));
            }
        };
        pageTick.run();
        return col;
    }

    /** Sites the downloader opens inside the app. YouTube first. {name, url, colour, initials} */
    private static final String[][] SITES = {
            {"يوتيوب ميوزك", "https://music.youtube.com", "#FF0033", "YM"},
            {"ساوند كلاود", "https://m.soundcloud.com", "#FF5500", "SC"},
            {"أوديوماك", "https://audiomack.com", "#FFA200", "AM"},
            {"مكس كلاود", "https://www.mixcloud.com", "#5000FF", "MC"},
            {"باندكامب", "https://bandcamp.com", "#1DA0C3", "BC"},
            {"جامندو", "https://www.jamendo.com", "#E4196B", "JA"},
            {"أرشيف الصوتيات", "https://archive.org/details/audio", "#4A4A4A", "AR"},
            {"ديلي موشن", "https://www.dailymotion.com", "#0066DC", "DM"},
            {"فيميو", "https://vimeo.com", "#1AB7EA", "VI"},
            {"تيك توك", "https://www.tiktok.com", "#FE2C55", "TT"},
            {"انستغرام", "https://www.instagram.com", "#E1306C", "IG"},
            {"فيسبوك", "https://m.facebook.com", "#1877F2", "FB"},
    };

    private View sitesSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // YouTube: big card first
        LinearLayout yt = new LinearLayout(this);
        yt.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable ytBg = new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, new int[]{0xFF3A0A10, 0xFF1A1A1F});
        ytBg.setCornerRadius(dp(18));
        ytBg.setStroke(dp(1), 0x55FF0033);
        yt.setBackground(ytBg);
        yt.setPadding(dp(14), dp(14), dp(14), dp(14));
        ImageView logo = new ImageView(this);
        logo.setImageDrawable(new Icon("yt", Color.WHITE));
        yt.addView(logo, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding(dp(12), 0, dp(8), 0);
        tt.addView(tv("يوتيوب", 19, TEXT, true));
        TextView hint = tv("افتح يوتيوب، شغّل الأغنية واضغط «⬇ تحميل»", 12, MUTED, false);
        tt.addView(hint);
        yt.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        TextView open = tv("فتح", 14, Color.WHITE, true);
        open.setBackground(round(0xFFFF0033, 18));
        open.setPadding(dp(16), dp(8), dp(16), dp(8));
        yt.addView(open);
        yt.setOnClickListener(v -> BrowserActivity.open(this, "يوتيوب", "https://m.youtube.com"));
        LinearLayout.LayoutParams ylp = new LinearLayout.LayoutParams(-1, -2);
        ylp.setMargins(0, dp(4), 0, dp(10));
        box.addView(yt, ylp);

        TextView more = tv("مواقع موسيقى وأغاني", 15, TEXT, true);
        more.setPadding(dp(2), dp(4), dp(2), dp(6));
        box.addView(more);

        int perRow = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density >= 560 ? 4 : 3;
        LinearLayout row = null;
        for (int i = 0; i < SITES.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                box.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            String[] site = SITES[i];
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setBackground(round(CARD, 14));
            cell.setPadding(dp(4), dp(12), dp(4), dp(10));
            TextView badge = tv(site[3], 14, Color.WHITE, true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(round(Color.parseColor(site[2]), 22));
            cell.addView(badge, new LinearLayout.LayoutParams(dp(44), dp(44)));
            TextView label = tv(site[0], 12, TEXT, false);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(0, dp(6), 0, 0);
            cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
            cell.setOnClickListener(v -> BrowserActivity.open(this, site[0], site[1]));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1);
            clp.setMargins(dp(3), dp(3), dp(3), dp(3));
            row.addView(cell, clp);
        }
        int rest = SITES.length % perRow;
        if (rest != 0) for (int k = rest; k < perRow; k++) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        return box;
    }

    private View jobRow(Yt.Job j, Map<Integer, Object[]> live) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(round(CARD, 14));
        r.setPadding(dp(10), dp(10), dp(10), dp(10));
        ImageView art = thumb(46);
        Img.load(this, art, j.song, artFallback());
        r.addView(art);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(10), 0, dp(10), 0);
        TextView title = tv(j.song.title, 14, TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(title);
        TextView st = tv(jobStatus(j), 12, "failed".equals(j.status) ? 0xFFFF6B6B : MUTED, false);
        st.setMaxLines(2);
        t.addView(st);
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(Math.round(j.progress));
        pb.setProgressTintList(ColorStateList.valueOf(GOLD));
        pb.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        pb.setVisibility(j.active() ? View.VISIBLE : View.GONE);
        t.addView(pb, new LinearLayout.LayoutParams(-1, dp(6)));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (j.active()) {
            r.addView(iconBtn("close", MUTED, 36, v -> Yt.cancel(j)));
            live.put(j.id, new Object[]{pb, st, j});
        } else if ("done".equals(j.status) && !j.file.isEmpty()) {
            r.addView(iconBtn("play", GOLD, 36, v -> {
                List<Song> one = new ArrayList<>();
                one.add(fileSong(new File(j.file)));
                play(one, 0);
            }));
        } else if ("failed".equals(j.status) || "canceled".equals(j.status)) {
            r.addView(iconBtn("refresh", GOLD, 36, v -> Yt.enqueue(this, j.song, j.choice)));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        r.setLayoutParams(lp);
        return r;
    }

    private String jobStatus(Yt.Job j) {
        String q = j.choice.label;
        switch (j.status) {
            case "queued": return "بالانتظار · " + q;
            case "running": return Math.round(j.progress) + "% · " + q + (j.eta > 0 ? " · باقي " + fmt(j.eta * 1000) : "");
            case "merging": return "جاري التحويل… · " + q;
            case "done": return "✓ اكتمل · " + q;
            case "canceled": return "أُلغي";
            default: return "فشل: " + j.error;
        }
    }

    // ================================================================== about
    private View pageAbout() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(20), dp(10), dp(20), dp(30));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        col.addView(logo, new LinearLayout.LayoutParams(dp(96), dp(96)));
        TextView name = tv("IBRA DL", 26, GOLD, true);
        name.setPadding(0, dp(8), 0, 0);
        col.addView(name);
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        col.addView(tv("الإصدار " + ver, 13, MUTED, false));
        TextView upd = pill("⟳  تحديث التطبيق", true);
        upd.setGravity(Gravity.CENTER);
        upd.setPadding(dp(22), dp(11), dp(22), dp(11));
        upd.setOnClickListener(v -> { toast("جاري البحث عن تحديث…"); Updater.check(this, false); });
        LinearLayout.LayoutParams ulp0 = new LinearLayout.LayoutParams(-2, -2);
        ulp0.setMargins(0, dp(10), 0, 0);
        col.addView(upd, ulp0);
        TextView desc = tv("ابحث واستمع لأي أغنية من يوتيوب، حمّلها MP3 أو M4A أو فيديو، شغّل أغاني جهازك، "
                + "واعمل قوائم تشغيل خاصة بيك. الأغنية تبقى تشتغل بالخلفية حتى لو طلعت من التطبيق، "
                + "وتكدر تكرر الأغنية نفسها أو القائمة من الإشعار مباشرة. بدون إعلانات.", 14, TEXT, false);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(0, 1.25f);
        desc.setPadding(0, dp(14), 0, dp(18));
        col.addView(desc);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(round(CARD, 18));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.addView(tv("برمجة وتطوير ابراهيم الخزرجي", 16, GOLD, true));
        String[][] links = {{"موقع تطبيقاتي", SITE}, {"تيليكرام  t.me/ibraprogram", TELEGRAM}, {"تيك توك  ibraprogram", TIKTOK}};
        for (String[] l : links) {
            TextView b = pill(l[0], l[1].equals(SITE));
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(16), dp(11), dp(16), dp(11));
            b.setOnClickListener(v -> openUrl(l[1]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(10), 0, 0);
            card.addView(b, lp);
        }
        col.addView(card, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout eng = new LinearLayout(this);
        eng.setOrientation(LinearLayout.VERTICAL);
        eng.setBackground(round(CARD, 18));
        eng.setPadding(dp(16), dp(14), dp(16), dp(14));
        eng.addView(tv("محرك البحث والتحميل", 15, TEXT, true));
        TextView ev = tv(Yt.state == Yt.READY ? "yt-dlp " + Yt.engineVersion(this) : "يتجهز…", 12, MUTED, false);
        eng.addView(ev);
        TextView up = pill("تحديث المحرك", false);
        up.setGravity(Gravity.CENTER);
        up.setOnClickListener(v -> {
            ev.setText("جاري التحديث…");
            Yt.updateEngine(this, (r, err) -> ev.setText(err == null ? r : "تعذر التحديث: " + err));
        });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-1, -2);
        ulp.setMargins(0, dp(10), 0, 0);
        eng.addView(up, ulp);
        eng.addView(tv("إذا وقف البحث أو التحميل يوم ما، اضغط «تحديث المحرك» لأن يوتيوب يغيّر نظامه باستمرار.", 12, MUTED, false));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.setMargins(0, dp(14), 0, 0);
        col.addView(eng, elp);
        sv.addView(col);
        return sv;
    }

    // ================================================================== mini player
    private View buildMini() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(0xFF15151A);
        miniProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        miniProgress.setMax(1000);
        miniProgress.setProgressTintList(ColorStateList.valueOf(GOLD));
        miniProgress.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        miniProgress.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        wrap.addView(miniProgress, new LinearLayout.LayoutParams(-1, dp(3)));
        mini = new LinearLayout(this);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setPadding(dp(12), dp(8), dp(8), dp(8));
        miniArt = thumb(44);
        mini.addView(miniArt);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(10), 0, dp(6), 0);
        miniTitle = tv("", 14, TEXT, true);
        miniTitle.setMaxLines(1);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniSub = tv("", 12, MUTED, false);
        miniSub.setMaxLines(1);
        t.addView(miniTitle);
        t.addView(miniSub);
        mini.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout btns = new LinearLayout(this);
        btns.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        btns.addView(iconBtn("prev", TEXT, 40, v -> act("previous")));
        miniPlay = iconBtn("play", GOLD, 44, v -> act("playPause"));
        btns.addView(miniPlay);
        btns.addView(iconBtn("next", TEXT, 40, v -> act("next")));
        mini.addView(btns);
        mini.setOnClickListener(v -> openFull());
        wrap.addView(mini);
        wrap.setVisibility(View.GONE);
        wrap.setTag("miniWrap");
        return wrap;
    }

    // ================================================================== full player
    private FrameLayout buildFull() {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF2A2412, 0xFF0B0B0D, 0xFF0B0B0D}));
        f.setClickable(true);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(22), dp(12), dp(22), dp(22));
        fullCol = col;

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(iconBtn("down", TEXT, 42, v -> closeFull()));
        TextView now = tv("يتم التشغيل الآن", 14, MUTED, false);
        now.setGravity(Gravity.CENTER);
        top.addView(now, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(iconBtn("queue", TEXT, 42, v -> queueDialog()));
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        fArt = new ImageView(this);
        fArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        fArt.setBackground(round(CARD2, 22));
        fArt.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        fArt.setClipToOutline(true);
        fArt.setElevation(dp(10));
        int side = Math.min(Math.min(getResources().getDisplayMetrics().widthPixels - dp(70), dp(380)),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.40f));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(side, side);
        alp.setMargins(0, dp(18), 0, dp(22));
        col.addView(fArt, alp);

        LinearLayout tt = new LinearLayout(this);
        tt.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        fTitle = tv("", 20, TEXT, true);
        fTitle.setMaxLines(2);
        fTitle.setEllipsize(TextUtils.TruncateAt.END);
        fArtist = tv("", 14, MUTED, false);
        fArtist.setMaxLines(1);
        tcol.addView(fTitle);
        tcol.addView(fArtist);
        tt.addView(tcol, new LinearLayout.LayoutParams(0, -2, 1));
        fFav = iconBtn("heart", TEXT, 44, v -> {
            Song s = current();
            if (s == null) return;
            boolean on = store.toggleFav(s);
            toast(on ? "أضيفت للمفضلة ❤" : "أزيلت من المفضلة");
            fullFor = "";
            refreshPlayer();
        });
        tt.addView(fFav);
        col.addView(tt, new LinearLayout.LayoutParams(-1, -2));

        fSeek = new SeekBar(this);
        fSeek.setMax(1000);
        fSeek.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        fSeek.setProgressTintList(ColorStateList.valueOf(GOLD));
        fSeek.setThumbTintList(ColorStateList.valueOf(GOLD));
        fSeek.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
        fSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && ctl != null && ctl.getDuration() > 0) fPos.setText(fmt(ctl.getDuration() * p / 1000));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (ctl != null && ctl.getDuration() > 0) ctl.seekTo(ctl.getDuration() * s.getProgress() / 1000);
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(0, dp(18), 0, 0);
        col.addView(fSeek, slp);
        LinearLayout times = new LinearLayout(this);
        times.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        times.setPadding(dp(14), 0, dp(14), 0);
        fPos = tv("0:00", 12, MUTED, false);
        fDur = tv("0:00", 12, MUTED, false);
        times.addView(fPos, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(fDur);
        col.addView(times, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout ctrls = new LinearLayout(this);
        ctrls.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        ctrls.setGravity(Gravity.CENTER);
        fShuffle = iconBtn("shuffle", MUTED, 46, v -> {
            if (ctl == null) return;
            ctl.setShuffleModeEnabled(!ctl.getShuffleModeEnabled());
            toast(ctl.getShuffleModeEnabled() ? "تشغيل عشوائي" : "تشغيل بالترتيب");
        });
        fRepeat = iconBtn("repeat", MUTED, 46, v -> {
            if (ctl == null) return;
            int m = ctl.getRepeatMode();
            int n = m == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ALL : m == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF;
            ctl.setRepeatMode(n);
            toast(n == Player.REPEAT_MODE_ONE ? "🔂 تكرار الأغنية نفسها" : n == Player.REPEAT_MODE_ALL ? "🔁 تكرار القائمة" : "بدون تكرار");
        });
        fPlay = new ImageView(this);
        fPlay.setBackground(round(GOLD, 36));
        fPlay.setPadding(dp(18), dp(18), dp(18), dp(18));
        fPlay.setOnClickListener(v -> act("playPause"));
        ctrls.addView(fShuffle);
        ctrls.addView(iconBtn("prev", TEXT, 58, v -> act("previous")));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(72), dp(72));
        plp.setMargins(dp(10), 0, dp(10), 0);
        ctrls.addView(fPlay, plp);
        ctrls.addView(iconBtn("next", TEXT, 58, v -> act("next")));
        ctrls.addView(fRepeat);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.setMargins(0, dp(14), 0, dp(10));
        col.addView(ctrls, clp);

        LinearLayout extra = new LinearLayout(this);
        extra.setGravity(Gravity.CENTER);
        extra.addView(chipBtn("download", "تحميل", v -> { Song s = current(); if (s != null) { if (s.isYoutube()) downloadDialog(s); else toast("هذه الأغنية موجودة على جهازك"); } }));
        extra.addView(chipBtn("plus", "قائمة", v -> { Song s = current(); if (s != null) addToPlaylistDialog(s); }));
        LinearLayout sleep = chipBtn("timer", "مؤقت", v -> sleepDialog());
        fSleep = (TextView) sleep.getChildAt(1);
        extra.addView(sleep);
        extra.addView(chipBtn("share", "مشاركة", v -> { Song s = current(); if (s != null) share(s); }));
        col.addView(extra, new LinearLayout.LayoutParams(-1, -2));

        sv.addView(col);
        f.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    private LinearLayout chipBtn(String icon, String label, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(8), dp(6), dp(8));
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new Icon(icon, TEXT));
        b.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));
        b.addView(tv(label, 12, MUTED, false));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        return b;
    }

    private void openFull() {
        if (ctl == null || ctl.getMediaItemCount() == 0) return;
        fullFor = "";
        refreshPlayer();
        full.setVisibility(View.VISIBLE);
        full.setTranslationY(full.getHeight() > 0 ? full.getHeight() : dp(600));
        full.animate().translationY(0).setDuration(220).start();
    }

    private void closeFull() {
        full.animate().translationY(full.getHeight()).setDuration(200).withEndAction(() -> full.setVisibility(View.GONE)).start();
    }

    private Song current() { return ctl == null ? null : Song.of(ctl.getCurrentMediaItem()); }

    private void refreshPlayer() {
        View wrap = content.getRootView().findViewWithTag("miniWrap");
        Song s = current();
        if (ctl != null) android.util.Log.i("IBRADL", "ui state=" + ctl.getPlaybackState() + " items=" + ctl.getMediaItemCount() + " playWhenReady=" + ctl.getPlayWhenReady());
        if (s == null || ctl == null) { if (wrap != null) wrap.setVisibility(View.GONE); return; }
        if (wrap != null) wrap.setVisibility(View.VISIBLE);
        boolean playing = ctl.getPlayWhenReady() && ctl.getPlaybackState() != Player.STATE_ENDED;
        miniPlay.setImageDrawable(new Icon(playing ? "pause" : "play", GOLD));
        fPlay.setImageDrawable(new Icon(playing ? "pause" : "play", Color.BLACK));
        if (!s.id.equals(miniFor)) {
            miniFor = s.id;
            miniTitle.setText(s.title);
            Img.load(this, miniArt, s, artFallback());
            if (activeAdapter != null) activeAdapter.notifyDataSetChanged();
        }
        boolean buffering = ctl.getPlaybackState() == Player.STATE_BUFFERING;
        miniSub.setText(buffering ? "جاري التحميل…" : s.artist);
        if (!s.id.equals(fullFor)) {
            fullFor = s.id;
            fTitle.setText(s.title);
            Img.load(this, fArt, s, bigFallback());
            fFav.setImageDrawable(new Icon(store.isFav(s) ? "heartFill" : "heart", store.isFav(s) ? GOLD : TEXT));
        }
        fArtist.setText(buffering ? "جاري جلب الأغنية…" : s.artist);
        int rm = ctl.getRepeatMode();
        fRepeat.setImageDrawable(new Icon(rm == Player.REPEAT_MODE_ONE ? "repeat1" : "repeat", rm == Player.REPEAT_MODE_OFF ? MUTED : GOLD));
        fShuffle.setImageDrawable(new Icon("shuffle", ctl.getShuffleModeEnabled() ? GOLD : MUTED));
        if (ctl.getPlayerError() != null && ctl.getPlaybackState() == Player.STATE_IDLE) {
            miniSub.setText("تعذر التشغيل — اضغط ▶ لإعادة المحاولة");
        }
    }

    private void updateProgress() {
        if (ctl == null || ctl.getMediaItemCount() == 0) return;
        long d = ctl.getDuration(), p = ctl.getCurrentPosition();
        int frac = d > 0 ? (int) (p * 1000 / d) : 0;
        miniProgress.setProgress(frac);
        if (full.getVisibility() == View.VISIBLE && !seeking) {
            fSeek.setProgress(frac);
            fPos.setText(fmt(p));
            fDur.setText(d > 0 ? fmt(d) : "--:--");
        }
        long left = PlaybackService.sleepAt - System.currentTimeMillis();
        fSleep.setText(left > 0 ? fmt(left) : "مؤقت");
        fSleep.setTextColor(left > 0 ? GOLD : MUTED);
    }

    // ================================================================== playback actions
    private void play(List<Song> songs, int index) {
        android.util.Log.i("IBRADL", "play index=" + index + " of " + songs.size() + " ctl=" + (ctl != null));
        if (index < 0 || index >= songs.size()) return;
        if (ctl == null) { toast("المشغل يتجهز… جرّب بعد ثانية"); return; }
        List<MediaItem> items = new ArrayList<>();
        for (Song s : songs) items.add(s.item());
        ctl.setMediaItems(items, index, 0);
        ctl.prepare();
        ctl.play();
        if (songs.get(index).isYoutube() && Yt.state != Yt.READY) toast("أول تشغيل ياخذ ثواني لتجهيز المحرك…");
    }

    private void shufflePlay(List<Song> songs) {
        if (ctl == null) return;
        setShuffle(true);
        play(songs, new java.util.Random().nextInt(songs.size()));
    }

    private void setShuffle(boolean on) { if (ctl != null) ctl.setShuffleModeEnabled(on); }

    private void act(String a) {
        if (ctl == null) return;
        switch (a) {
            case "playPause":
                if (ctl.isPlaying()) ctl.pause();
                else {
                    if (ctl.getPlaybackState() == Player.STATE_ENDED) ctl.seekTo(0);
                    ctl.prepare();
                    ctl.play();
                }
                break;
            case "next": if (ctl.hasNextMediaItem()) ctl.seekToNextMediaItem(); else ctl.seekTo(0, 0); break;
            case "previous":
                if (ctl.getCurrentPosition() > 4000 || !ctl.hasPreviousMediaItem()) ctl.seekTo(0); else ctl.seekToPreviousMediaItem();
                break;
            default: break;
        }
    }

    private void playNext(Song s) {
        if (ctl == null) return;
        if (ctl.getMediaItemCount() == 0) { List<Song> one = new ArrayList<>(); one.add(s); play(one, 0); return; }
        ctl.addMediaItem(Math.min(ctl.getCurrentMediaItemIndex() + 1, ctl.getMediaItemCount()), s.item());
        toast("راح تشتغل بعد الأغنية الحالية");
    }

    private void addToQueue(Song s) {
        if (ctl == null) return;
        if (ctl.getMediaItemCount() == 0) { List<Song> one = new ArrayList<>(); one.add(s); play(one, 0); return; }
        ctl.addMediaItem(s.item());
        toast("أضيفت لآخر قائمة الانتظار");
    }

    private void queueDialog() {
        if (ctl == null || ctl.getMediaItemCount() == 0) return;
        int n = ctl.getMediaItemCount();
        String[] names = new String[n];
        int cur = ctl.getCurrentMediaItemIndex();
        for (int i = 0; i < n; i++) {
            Song s = Song.of(ctl.getMediaItemAt(i));
            names[i] = (i == cur ? "▶  " : (i + 1) + ".  ") + s.title;
        }
        new AlertDialog.Builder(this, R.style.Dialog)
                .setTitle("قائمة الانتظار (" + n + ")")
                .setItems(names, (d, w) -> { ctl.seekTo(w, 0); ctl.play(); })
                .setPositiveButton("حفظ كقائمة تشغيل", (d, w) -> askText("حفظ قائمة الانتظار", "", "اسم القائمة", name -> {
                    Store.Playlist p = store.create(name);
                    for (int i = 0; i < ctl.getMediaItemCount(); i++) store.add(p.id, Song.of(ctl.getMediaItemAt(i)));
                    toast("تم الحفظ ✓");
                }))
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void sleepDialog() {
        String[] opts = {"إيقاف المؤقت", "15 دقيقة", "30 دقيقة", "45 دقيقة", "60 دقيقة", "90 دقيقة", "120 دقيقة"};
        int[] mins = {0, 15, 30, 45, 60, 90, 120};
        new AlertDialog.Builder(this, R.style.Dialog).setTitle("إيقاف التشغيل بعد…").setItems(opts, (d, w) -> {
            if (ctl == null) return;
            Bundle args = new Bundle();
            args.putInt("min", mins[w]);
            ctl.sendCustomCommand(new SessionCommand(PlaybackService.CMD_SLEEP, Bundle.EMPTY), args);
            toast(mins[w] == 0 ? "أوقف المؤقت" : "الموسيقى راح تتوقف بعد " + mins[w] + " دقيقة");
        }).show();
    }

    // ================================================================== dialogs
    private void songMenu(Song s, String playlistId, List<Song> list, int pos) {
        List<String> o = new ArrayList<>();
        o.add("▶  تشغيل الآن");
        o.add("⏭  تشغيل التالي");
        o.add("☰  إضافة لقائمة الانتظار");
        o.add("＋  إضافة إلى قائمة تشغيل…");
        o.add(store.isFav(s) ? "♡  إزالة من المفضلة" : "❤  إضافة للمفضلة");
        if (s.isYoutube()) { o.add("⬇  تحميل…"); o.add("↗  مشاركة الرابط"); }
        if (playlistId != null) {
            if (pos > 0) o.add("↑  نقل للأعلى");
            if (pos < list.size() - 1) o.add("↓  نقل للأسفل");
            o.add("✕  إزالة من القائمة");
        }
        if (!s.path.isEmpty() && s.path.contains("/IBRA DL/")) o.add("⊘  حذف الملف من الجهاز");
        new AlertDialog.Builder(this, R.style.Dialog).setTitle(s.title).setItems(o.toArray(new String[0]), (d, w) -> {
            String c = o.get(w).substring(3);
            switch (c) {
                case "تشغيل الآن": play(list, pos); break;
                case "تشغيل التالي": playNext(s); break;
                case "إضافة لقائمة الانتظار": addToQueue(s); break;
                case "إضافة إلى قائمة تشغيل…": addToPlaylistDialog(s); break;
                case "إزالة من المفضلة": case "إضافة للمفضلة":
                    toast(store.toggleFav(s) ? "أضيفت للمفضلة ❤" : "أزيلت من المفضلة");
                    if (Store.FAV.equals(playlistId)) showTab(TAB_PLAYLISTS, true);
                    break;
                case "تحميل…": downloadDialog(s); break;
                case "مشاركة الرابط": share(s); break;
                case "نقل للأعلى": store.move(playlistId, pos, pos - 1); showTab(TAB_PLAYLISTS, true); break;
                case "نقل للأسفل": store.move(playlistId, pos, pos + 1); showTab(TAB_PLAYLISTS, true); break;
                case "إزالة من القائمة": store.remove(playlistId, s); showTab(TAB_PLAYLISTS, true); break;
                case "حذف الملف من الجهاز":
                    confirm("حذف «" + s.title + "» من الجهاز؟", () -> {
                        File f = new File(s.path);
                        boolean ok = f.delete();
                        MediaScannerConnection.scanFile(this, new String[]{s.path}, null, null);
                        toast(ok ? "انحذف ✓" : "تعذر الحذف");
                        if (tab == TAB_LIBRARY) showTab(TAB_LIBRARY, true);
                    });
                    break;
                default: break;
            }
        }).show();
    }

    private void downloadDialog(Song s) {
        String[] labels = new String[Yt.CHOICES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = Yt.CHOICES[i].label;
        new AlertDialog.Builder(this, R.style.Dialog).setTitle("تحميل: " + s.title).setItems(labels, (d, w) -> {
            Yt.enqueue(this, s, Yt.CHOICES[w]);
            toast("بدأ التحميل ⬇ — تابعه من «التحميلات»");
        }).show();
    }

    private void downloadAllDialog(List<Song> songs) {
        String[] labels = new String[4];
        for (int i = 0; i < 4; i++) labels[i] = Yt.CHOICES[i].label;
        new AlertDialog.Builder(this, R.style.Dialog).setTitle("تحميل " + songs.size() + " أغنية").setItems(labels, (d, w) -> {
            for (Song s : songs) Yt.enqueue(this, s, Yt.CHOICES[w]);
            toast("انضافت " + songs.size() + " أغنية للتحميل");
        }).show();
    }

    private void addToPlaylistDialog(Song s) {
        List<Store.Playlist> all = store.playlists();
        String[] names = new String[all.size() + 1];
        names[0] = "＋  قائمة جديدة…";
        for (int i = 0; i < all.size(); i++) names[i + 1] = all.get(i).name + "  (" + all.get(i).songs.size() + ")";
        new AlertDialog.Builder(this, R.style.Dialog).setTitle("إضافة إلى قائمة تشغيل").setItems(names, (d, w) -> {
            if (w == 0) askText("قائمة تشغيل جديدة", "", "اسم القائمة", name -> {
                Store.Playlist p = store.create(name);
                store.add(p.id, s);
                toast("أضيفت إلى «" + name + "» ✓");
            });
            else {
                Store.Playlist p = all.get(w - 1);
                toast(store.add(p.id, s) ? "أضيفت إلى «" + p.name + "» ✓" : "موجودة أصلاً في «" + p.name + "»");
            }
        }).show();
    }

    interface TextDone { void run(String s); }

    private void askText(String title, String value, String hint, TextDone done) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e);
        new AlertDialog.Builder(this, R.style.Dialog).setTitle(title).setView(box)
                .setPositiveButton("حفظ", (d, w) -> { String t = e.getText().toString().trim(); if (!t.isEmpty()) done.run(t); })
                .setNegativeButton("إلغاء", null).show();
        e.requestFocus();
    }

    private void confirm(String msg, Runnable yes) {
        new AlertDialog.Builder(this, R.style.Dialog).setMessage(msg)
                .setPositiveButton("نعم", (d, w) -> yes.run()).setNegativeButton("لا", null).show();
    }

    private void share(Song s) {
        if (!s.isYoutube()) { toast("المشاركة متاحة لأغاني يوتيوب"); return; }
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, s.title + "\n" + s.watchUrl() + "\n\nعبر تطبيق IBRA DL — " + SITE);
        startActivity(Intent.createChooser(i, "مشاركة"));
    }

    private void openUrl(String u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } catch (Exception e) { toast(u); }
    }

    // ================================================================== song list
    private RecyclerView list(SongAdapter a) {
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(a);
        rv.setClipToPadding(false);
        rv.setPadding(0, 0, 0, dp(12));
        return rv;
    }

    private final class SongAdapter extends RecyclerView.Adapter<SongAdapter.VH> {
        final List<Song> songs;
        final String playlistId;
        boolean libraryMode;

        SongAdapter(List<Song> songs, String playlistId) { this.songs = songs; this.playlistId = playlistId; }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView art, more;
            final TextView title, sub;
            VH(LinearLayout r, ImageView art, TextView title, TextView sub, ImageView more) {
                super(r); this.art = art; this.title = title; this.sub = sub; this.more = more;
            }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LinearLayout r = new LinearLayout(MainActivity.this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(14), dp(7), dp(4), dp(7));
            r.setBackground(ripple());
            ImageView art = thumb(54);
            r.addView(art);
            LinearLayout t = new LinearLayout(MainActivity.this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setPadding(dp(12), 0, dp(4), 0);
            TextView title = tv("", 15, TEXT, false);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            TextView sub = tv("", 12, MUTED, false);
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            t.addView(title);
            t.addView(sub);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            ImageView more = iconBtn("more", MUTED, 40, null);
            r.addView(more);
            r.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new VH(r, art, title, sub, more);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Song s = songs.get(pos);
            boolean now = s.id.equals(miniFor);
            h.title.setText(s.title);
            h.title.setTextColor(now ? GOLD : TEXT);
            h.title.setTypeface(null, now ? Typeface.BOLD : Typeface.NORMAL);
            String d = s.duration > 0 ? fmt(s.duration) : "";
            String sub = s.artist + (d.isEmpty() ? "" : (s.artist.isEmpty() ? "" : "  ·  ") + d);
            if (s.isYoutube() && Yt.isDownloaded(s.videoId())) sub = "✓ محمّلة  ·  " + sub;
            h.sub.setText(sub);
            Img.load(MainActivity.this, h.art, s, artFallback());
            h.itemView.setOnClickListener(v -> play(new ArrayList<>(songs), h.getBindingAdapterPosition()));
            h.more.setOnClickListener(v -> songMenu(s, playlistId, new ArrayList<>(songs), h.getBindingAdapterPosition()));
            h.itemView.setOnLongClickListener(v -> { songMenu(s, playlistId, new ArrayList<>(songs), h.getBindingAdapterPosition()); return true; });
        }

        @Override public int getItemCount() { return songs.size(); }
    }

    // ================================================================== ui helpers
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private Drawable ripple() {
        return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(GOLD_DIM), null, new android.graphics.drawable.ColorDrawable(Color.WHITE));
    }

    private TextView pill(String text, boolean on) {
        TextView t = tv(text, 13, on ? Color.BLACK : TEXT, on);
        t.setBackground(round(on ? GOLD : CARD2, 20));
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        return t;
    }

    private ImageView iconBtn(String icon, int color, int sizeDp, View.OnClickListener l) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(new Icon(icon, color));
        int pad = Math.round(sizeDp * 0.22f);
        v.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        v.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(GOLD_DIM), null, null));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        if (l != null) v.setOnClickListener(l);
        return v;
    }

    private ImageView thumb(int sizeDp) {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setBackground(round(CARD2, 10));
        v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        v.setClipToOutline(true);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    private Drawable artFallback() {
        LayerDrawable d = new LayerDrawable(new Drawable[]{round(GOLD_DIM, 10), new Icon("music", GOLD)});
        int in = dp(12);
        d.setLayerInset(1, in, in, in, in);
        return d;
    }

    private Drawable bigFallback() {
        LayerDrawable d = new LayerDrawable(new Drawable[]{round(0xFF1D1A10, 22), new Icon("music", GOLD)});
        int in = dp(80);
        d.setLayerInset(1, in, in, in, in);
        return d;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (im != null && f != null) im.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }
}
