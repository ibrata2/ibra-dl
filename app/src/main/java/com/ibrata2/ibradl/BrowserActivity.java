package com.ibrata2.ibradl;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Opens YouTube or a music site inside IBRA DL. The gold "تحميل" button downloads
 * whatever song / video page is open right now.
 */
public final class BrowserActivity extends Activity {

    static final String EXTRA_URL = "url", EXTRA_NAME = "name";

    private WebView web;
    private TextView title;
    private ProgressBar bar;
    private TextView grab;
    private boolean busy;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Yt.prepare(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(MainActivity.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xFF111114);
        top.setPadding(dp(6), dp(6), dp(6), dp(6));
        top.addView(btn("close", v -> finish()));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(6), 0, dp(6), 0);
        TextView name = new TextView(this);
        name.setText(getIntent().getStringExtra(EXTRA_NAME));
        name.setTextColor(MainActivity.GOLD);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        title = new TextView(this);
        title.setTextColor(MainActivity.MUTED);
        title.setTextSize(11);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(name);
        t.addView(title);
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(btn("refresh", v -> web.reload()));
        col.addView(top);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(MainActivity.GOLD));
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        col.addView(bar, new LinearLayout.LayoutParams(-1, dp(3)));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36");
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                return scheme == null || !(scheme.equals("http") || scheme.equals("https"));   // stay inside, ignore app links
            }
            @Override public void onPageFinished(WebView v, String url) { title.setText(url); }
            @Override public void doUpdateVisitedHistory(WebView v, String url, boolean reload) { title.setText(url); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                bar.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
        col.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));

        grab = new TextView(this);
        grab.setText("⬇  تحميل");
        grab.setTextColor(Color.BLACK);
        grab.setTextSize(16);
        grab.setTypeface(Typeface.DEFAULT_BOLD);
        grab.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(MainActivity.GOLD);
        g.setCornerRadius(dp(28));
        grab.setBackground(g);
        grab.setElevation(dp(8));
        grab.setPadding(dp(26), dp(14), dp(26), dp(14));
        grab.setOnClickListener(v -> grabCurrent());
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        glp.bottomMargin = dp(22);
        root.addView(grab, glp);

        setContentView(root);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, in) -> {
            androidx.core.graphics.Insets bars = in.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.ime());
            col.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            glp.bottomMargin = dp(22) + bars.bottom;
            grab.setLayoutParams(glp);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });

        String url = getIntent().getStringExtra(EXTRA_URL);
        web.loadUrl(url == null ? "https://m.youtube.com" : url);
    }

    private void grabCurrent() {
        String url = web.getUrl();
        if (url == null || busy) return;
        if (url.contains("youtube.com") && !(url.contains("watch") || url.contains("shorts/") || url.contains("list="))) {
            toast("افتح الأغنية أو الفيديو أولاً، بعدين اضغط تحميل");
            return;
        }
        busy = true;
        grab.setText("⏳  جاري القراءة…");
        Yt.link(this, url, (list, err) -> {
            busy = false;
            grab.setText("⬇  تحميل");
            if (isFinishing()) return;
            if (list == null || list.isEmpty()) {
                toast("ما لكيت أغنية بهالصفحة. افتح الأغنية نفسها وجرّب مرة ثانية.");
                return;
            }
            choose(list);
        });
    }

    private void choose(List<Song> songs) {
        boolean one = songs.size() == 1;
        int n = one ? Yt.CHOICES.length : 4;
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) labels[i] = Yt.CHOICES[i].label;
        new AlertDialog.Builder(this, R.style.Dialog)
                .setTitle(one ? "تحميل: " + songs.get(0).title : "تحميل " + songs.size() + " أغنية")
                .setItems(labels, (d, w) -> {
                    for (Song s : songs) Yt.enqueue(this, s, Yt.CHOICES[w]);
                    toast("بدأ التحميل ⬇ — تابعه من «التحميلات»");
                })
                .setNeutralButton("فتح التحميلات", (d, w) -> startActivity(new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_DOWNLOADS)))
                .show();
    }

    private ImageView btn(String icon, View.OnClickListener l) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(new Icon(icon, MainActivity.TEXT));
        v.setPadding(dp(9), dp(9), dp(9), dp(9));
        v.setOnClickListener(l);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return v;
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    static void open(Activity from, String name, String url) {
        from.startActivity(new Intent(from, BrowserActivity.class).putExtra(EXTRA_NAME, name).putExtra(EXTRA_URL, url));
    }
}
