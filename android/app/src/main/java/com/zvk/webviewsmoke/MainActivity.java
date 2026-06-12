package com.zvk.webviewsmoke;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.view.WindowManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    static final String TAG = "ZVKSMOKE";

    static final String DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    static final String[] ALLOWED_HOSTS = {
        "discord.com", "discordapp.com", "discordapp.net", "discord.gg"
    };

    // Palette
    static final int BG = Color.parseColor("#1e1f22");
    static final int CARD = Color.parseColor("#2b2d31");
    static final int FIELD = Color.parseColor("#1e1f22");
    static final int BLURPLE = Color.parseColor("#5865f2");
    static final int DANGER = Color.parseColor("#da373c");
    static final int NEUTRAL = Color.parseColor("#4e5058");
    static final int TEXT = Color.parseColor("#f2f3f5");
    static final int MUTED = Color.parseColor("#b5bac1");
    static final int FAINT = Color.parseColor("#80848e");
    static final int SEL = Color.parseColor("#3b3d44");

    private WebView web;
    private FrameLayout panel;
    private View pickerScreen, controlScreen, channelScreen;

    private EditText searchBox, contentEdit, afterEdit, beforeEdit, breakEvery, breakMins, limitEdit;
    private Bitmap logoBitmap;
    private ListView list, channelList;
    private TargetAdapter adapter;
    private ChannelAdapter channelAdapter;
    private TextView pickStatus, ctrlStatus, targetLabel, paceLabel, channelTitle, runWarn;
    private Switch swLink, swFile, swEmbed, swLongBreak;
    private SeekBar paceBar;
    private Button scanBtn, deleteBtn, stopBtn;
    private ProgressBar progressBar;
    private int runTarget = 0;
    private boolean lastDryRun = false;

    private String engineSource;
    private boolean running = false;
    private boolean loadStarted = false;

    private final List<Target> all = new ArrayList<>();
    private final List<Target> shown = new ArrayList<>();
    private String selGuildId, selChannelId, selName;
    private boolean selIsGuild;
    private String pendingGuildId, pendingGuildName;
    private final List<String[]> pendingChannels = new ArrayList<>(); // [id, name]

    static final class Target {
        final String kind, id, name;
        Target(String kind, String id, String name) { this.kind = kind; this.id = id; this.name = name; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // For the progress notification on the foreground service (Android 13+).
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 7);
        }

        engineSource = readAsset("zivkord-core.js");
        logoBitmap = loadBitmap("logo.png");

        FrameLayout root = new FrameLayout(this);
        setContentView(root);

        web = new WebView(this);
        root.addView(web, frame());

        panel = new FrameLayout(this);
        panel.setBackgroundColor(BG);
        pickerScreen = buildPickerScreen();
        channelScreen = buildChannelScreen();
        controlScreen = buildControlScreen();
        panel.addView(pickerScreen, frame());
        panel.addView(channelScreen, frame());
        panel.addView(controlScreen, frame());
        channelScreen.setVisibility(View.GONE);
        controlScreen.setVisibility(View.GONE);
        root.addView(panel, frame());
        panel.setVisibility(View.GONE);

        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(DESKTOP_UA);
        s.setMediaPlaybackRequiresUserGesture(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.removeAllCookies(null);
        cm.setAcceptThirdPartyCookies(web, true);
        WebStorage.getInstance().deleteAllData();

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cmsg) {
                String m = cmsg.message();
                if (m != null && m.startsWith("ZIVKORD::")) handleEngineEvent(m.substring(9));
                else if (cmsg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.w(TAG, "PAGE_ERR " + m);
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String host = request.getUrl() != null ? request.getUrl().getHost() : null;
                if (host != null && !isDiscordHost(host)) {
                    Log.i(TAG, "BLOCKED " + host);
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (url != null && url.contains("discord.com")) view.evaluateJavascript(engineSource, null);
                checkLogin(url);
            }
            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) { checkLogin(url); }
            @Override
            public void onPageFinished(WebView view, String url) { checkLogin(url); }
        });

        web.loadUrl("https://discord.com/login");
    }

    @Override
    protected void onDestroy() {
        try { stopService(new Intent(this, DeleteService.class)); } catch (Exception ignored) {}
        try {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
            if (web != null) { web.clearCache(true); web.clearHistory(); }
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ---- screen: picker --------------------------------------------------------

    private View buildPickerScreen() {
        LinearLayout p = col();
        int pad = dp(16);
        p.setPadding(pad, pad, pad, pad);

        p.addView(header("ZiVKord", "Pick a server or DM to clean"));

        searchBox = field("Search your servers and DMs");
        searchBox.setSingleLine(true);
        searchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(s.toString()); }
            public void afterTextChanged(Editable s) {}
        });
        p.addView(searchBox, mt(dp(12)));

        pickStatus = small("Loading...");
        p.addView(pickStatus, mt(dp(8)));

        adapter = new TargetAdapter();
        list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.parseColor("#33000000")));
        list.setDividerHeight(1);
        list.setBackground(round(CARD, 10));
        list.setOnItemClickListener((parent, view, pos, id) -> {
            Target t = shown.get(pos);
            if ("guild".equals(t.kind)) {
                // Servers: ask which channel (or all) before going to the panel.
                pendingGuildId = t.id; pendingGuildName = t.name;
                setStatus("Loading channels in " + t.name + "...");
                web.evaluateJavascript(
                    "window.__ZIVKORD__ && window.__ZIVKORD__.listChannels('" + t.id + "')", null);
            } else {
                selGuildId = null; selChannelId = t.id; selName = t.name; selIsGuild = false;
                adapter.notifyDataSetChanged();
                showControl();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, 0, 1f);
        lp.topMargin = dp(10);
        p.addView(list, lp);

        Button coffee = button("☕  Buy me a coffee?", NEUTRAL, TEXT, v -> showCoffee());
        p.addView(coffee, mt(dp(8)));
        return p;
    }

    private static final String BTC_ADDRESS = "bc1qwr5cxthphfjekjl28m0sk8vyh363d6fdyzevjl";

    private void showCoffee() {
        new AlertDialog.Builder(this)
            .setTitle("Buy me a coffee?")
            .setMessage("ZiVKord is free and always will be - no ads, no paywalls. If it saved "
                + "you some hassle, a little Bitcoin is appreciated. No pressure either way.\n\n"
                + BTC_ADDRESS)
            .setPositiveButton("Copy address", (d, w) -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("BTC address", BTC_ADDRESS));
                setStatus("Bitcoin address copied. Thank you!");
            })
            .setNegativeButton("Close", null)
            .show();
    }

    // ---- screen: channel chooser (for servers) ---------------------------------

    private View buildChannelScreen() {
        LinearLayout p = col();
        int pad = dp(16);
        p.setPadding(pad, pad, pad, pad);

        LinearLayout hr = new LinearLayout(this);
        hr.setOrientation(LinearLayout.HORIZONTAL);
        hr.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("←", NEUTRAL, TEXT, v -> showPicker());
        back.setTextSize(26f);
        back.setTypeface(back.getTypeface(), android.graphics.Typeface.BOLD);
        back.setPadding(0, 0, 0, 0);
        back.setIncludeFontPadding(false);
        // Fixed footprint so the bigger glyph doesn't grow the button.
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(52), dp(48));
        blp.rightMargin = dp(10);
        hr.addView(back, blp);
        channelTitle = new TextView(this);
        channelTitle.setText("Pick a channel");
        channelTitle.setTextColor(BLURPLE);
        channelTitle.setTextSize(20f);
        channelTitle.setTypeface(channelTitle.getTypeface(), android.graphics.Typeface.BOLD);
        hr.addView(channelTitle);
        p.addView(hr);

        channelAdapter = new ChannelAdapter();
        channelList = new ListView(this);
        channelList.setAdapter(channelAdapter);
        channelList.setBackground(round(CARD, 10));
        channelList.setDivider(new android.graphics.drawable.ColorDrawable(Color.parseColor("#33000000")));
        channelList.setDividerHeight(1);
        channelList.setOnItemClickListener((parent, view, pos, id) -> {
            selGuildId = pendingGuildId;
            selIsGuild = true;
            if (pos == 0) { selChannelId = null; selName = pendingGuildName + " (all channels)"; }
            else {
                String[] c = pendingChannels.get(pos - 1);
                selChannelId = c[0];
                selName = pendingGuildName + " #" + c[1];
            }
            adapter.notifyDataSetChanged();
            showControl();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, 0, 1f);
        lp.topMargin = dp(10);
        p.addView(channelList, lp);
        return p;
    }

    private final class ChannelAdapter extends BaseAdapter {
        public int getCount() { return pendingChannels.size() + 1; } // +1 for "all channels"
        public Object getItem(int i) { return i; }
        public long getItemId(int i) { return i; }
        public View getView(int pos, View convert, ViewGroup parent) {
            TextView tv = (convert instanceof TextView) ? (TextView) convert : new TextView(MainActivity.this);
            if (pos == 0) {
                tv.setText("All channels in " + pendingGuildName);
                tv.setTextColor(BLURPLE);
            } else {
                tv.setText("#  " + pendingChannels.get(pos - 1)[1]);
                tv.setTextColor(TEXT);
            }
            tv.setTextSize(15f);
            int q = dp(14); tv.setPadding(q, q, q, q);
            tv.setBackgroundColor(Color.TRANSPARENT);
            return tv;
        }
    }

    // ---- screen: control panel -------------------------------------------------

    private View buildControlScreen() {
        LinearLayout outer = col();
        int pad = dp(16);
        outer.setPadding(pad, pad, pad, pad);

        outer.addView(header("ZiVKord", null));

        targetLabel = new TextView(this);
        targetLabel.setTextColor(TEXT);
        targetLabel.setTextSize(16f);
        outer.addView(targetLabel, mt(dp(2)));

        Button change = button("Change target", NEUTRAL, TEXT, v -> showPicker());
        outer.addView(change, mt(dp(8)));

        ScrollView sv = new ScrollView(this);
        LinearLayout opts = col();
        opts.setPadding(0, dp(10), 0, dp(10));

        opts.addView(sectionLabel("Filters (optional)"));
        contentEdit = field("Only messages containing this text");
        contentEdit.setSingleLine(true);
        opts.addView(contentEdit, mt(dp(8)));

        swLink = toggle(opts, "Only messages with links");
        swFile = toggle(opts, "Only messages with files");
        swEmbed = toggle(opts, "Only messages with embeds");

        opts.addView(sectionLabel("Date range (optional, YYYY-MM-DD)"), mt(dp(14)));
        afterEdit = field("After this date");
        afterEdit.setSingleLine(true);
        opts.addView(afterEdit, mt(dp(8)));
        beforeEdit = field("Before this date");
        beforeEdit.setSingleLine(true);
        opts.addView(beforeEdit, mt(dp(8)));

        opts.addView(sectionLabel("Limit"), mt(dp(14)));
        LinearLayout limRow = new LinearLayout(this);
        limRow.setOrientation(LinearLayout.HORIZONTAL);
        limRow.setGravity(Gravity.CENTER_VERTICAL);
        limRow.addView(small("Only the most recent"));
        limitEdit = numField("");
        limitEdit.setHint("0");
        limitEdit.setHintTextColor(FAINT);
        addNum(limRow, limitEdit);
        limRow.addView(small("messages (0 = all)"));
        opts.addView(limRow, mt(dp(6)));

        opts.addView(sectionLabel("Pacing"), mt(dp(14)));
        paceLabel = small("");
        opts.addView(paceLabel, mt(dp(2)));
        paceBar = new SeekBar(this);
        paceBar.setMax(100);
        paceBar.setProgress(70);
        paceBar.getProgressDrawable().setColorFilter(BLURPLE, android.graphics.PorterDuff.Mode.SRC_IN);
        paceBar.setThumbTintList(ColorStateList.valueOf(BLURPLE));
        paceBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean fromUser) { updatePaceLabel(); }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        opts.addView(paceBar, mt(dp(4)));

        swLongBreak = toggle(opts, "Take a longer break between batches");
        swLongBreak.setChecked(true);
        LinearLayout breakRow = new LinearLayout(this);
        breakRow.setOrientation(LinearLayout.HORIZONTAL);
        breakRow.setGravity(Gravity.CENTER_VERTICAL);
        breakRow.addView(small("Every"));
        breakEvery = numField("25");
        addNum(breakRow, breakEvery);
        breakRow.addView(small("deletes, rest"));
        breakMins = numField("1");
        addNum(breakRow, breakMins);
        breakRow.addView(small("min"));
        opts.addView(breakRow, mt(dp(6)));
        updatePaceLabel();

        sv.addView(opts);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(MP, 0, 1f);
        outer.addView(sv, svLp);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        if (progressBar.getProgressDrawable() != null)
            progressBar.getProgressDrawable().setColorFilter(BLURPLE, android.graphics.PorterDuff.Mode.SRC_IN);
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(BLURPLE));
        progressBar.setVisibility(View.GONE);
        outer.addView(progressBar, mt(dp(8)));

        runWarn = new TextView(this);
        runWarn.setText("KEEP THIS SCREEN OPEN WHILE IT RUNS. Leaving the app or locking "
            + "the phone will stop the job and log you out - you'd have to start over.");
        runWarn.setTextColor(DANGER);
        runWarn.setTextSize(13f);
        runWarn.setTypeface(runWarn.getTypeface(), android.graphics.Typeface.BOLD);
        runWarn.setGravity(Gravity.CENTER);
        runWarn.setVisibility(View.GONE);
        outer.addView(runWarn, mt(dp(8)));

        ctrlStatus = small("Scan to preview, Delete to remove.");
        outer.addView(ctrlStatus, mt(dp(8)));

        TextView warn = new TextView(this);
        warn.setText("ALWAYS SCAN BEFORE DELETION");
        warn.setTextColor(DANGER);
        warn.setTextSize(13f);
        warn.setTypeface(warn.getTypeface(), android.graphics.Typeface.BOLD);
        warn.setGravity(Gravity.CENTER);
        outer.addView(warn, mt(dp(8)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scanBtn = button("Scan", BLURPLE, Color.WHITE, v -> startRun(true));
        deleteBtn = button("Delete", DANGER, Color.WHITE, v -> confirmDelete());
        stopBtn = button("Stop", NEUTRAL, TEXT, v -> {
            web.evaluateJavascript("window.__ZIVKORD__ && window.__ZIVKORD__.stop();", null);
            setStatus("Stopping...");
        });
        stopBtn.setEnabled(false);
        LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, WC, 1f);
        cell.leftMargin = dp(3); cell.rightMargin = dp(3);
        row.addView(scanBtn, cell);
        row.addView(deleteBtn, new LinearLayout.LayoutParams(cell));
        row.addView(stopBtn, new LinearLayout.LayoutParams(cell));
        outer.addView(row, mt(dp(8)));

        Button coffee = button("☕  Buy me a coffee?", NEUTRAL, TEXT, v -> showCoffee());
        outer.addView(coffee, mt(dp(6)));
        Button logout = button("Switch account / log out", NEUTRAL, MUTED, v -> logout());
        outer.addView(logout, mt(dp(6)));
        return outer;
    }

    private void showPicker() {
        runOnUiThread(() -> {
            controlScreen.setVisibility(View.GONE);
            channelScreen.setVisibility(View.GONE);
            pickerScreen.setVisibility(View.VISIBLE);
        });
    }

    private void showChannelScreen() {
        runOnUiThread(() -> {
            channelTitle.setText("Channels in " + pendingGuildName);
            channelAdapter.notifyDataSetChanged();
            pickerScreen.setVisibility(View.GONE);
            controlScreen.setVisibility(View.GONE);
            channelScreen.setVisibility(View.VISIBLE);
        });
    }

    private void showControl() {
        runOnUiThread(() -> {
            targetLabel.setText((selIsGuild ? "Server: " : "DM: ") + selName);
            ctrlStatus.setText("Scan to preview, Delete to remove.");
            pickerScreen.setVisibility(View.GONE);
            channelScreen.setVisibility(View.GONE);
            controlScreen.setVisibility(View.VISIBLE);
        });
    }

    private void updatePaceLabel() {
        int[] d = paceDelays();
        paceLabel.setText(String.format("About %.1f-%.1fs between deletes  (left = slowest, right = fastest)",
            d[0] / 1000.0, d[1] / 1000.0));
    }

    // seekbar 0 (left, slowest) .. 100 (right, fastest). Max delay runs 5.0s down
    // to 0.8s; min is a bit under that for human-like jitter.
    private int[] paceDelays() {
        int pr = paceBar != null ? paceBar.getProgress() : 70;
        int max = 5000 - (int) Math.round(pr / 100.0 * 4200); // 5000ms (left) .. 800ms (right)
        int min = (int) Math.round(max * 0.8);
        return new int[]{ min, max };
    }

    private Bitmap loadBitmap(String name) {
        try (InputStream is = getAssets().open(name)) { return BitmapFactory.decodeStream(is); }
        catch (Exception e) { Log.w(TAG, "no logo: " + e.getMessage()); return null; }
    }

    private EditText numField(String def) {
        EditText e = new EditText(this);
        e.setText(def);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setTextColor(TEXT); e.setTextSize(15f); e.setGravity(Gravity.CENTER);
        e.setBackground(boxBg(FIELD, 6));
        int p = dp(8); e.setPadding(p, p, p, p);
        doneKey(e);
        return e;
    }

    private void addNum(LinearLayout row, EditText e) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(60), WC);
        lp.leftMargin = dp(6); lp.rightMargin = dp(6);
        row.addView(e, lp);
    }

    // ---- styled component helpers ----------------------------------------------

    private LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }

    private View header(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (logoBitmap != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(logoBitmap);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(44), dp(44));
            ilp.rightMargin = dp(10);
            row.addView(iv, ilp);
        }
        LinearLayout textCol = col();
        TextView t = new TextView(this);
        t.setText(title); t.setTextColor(BLURPLE); t.setTextSize(24f);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        textCol.addView(t);
        if (subtitle != null) {
            TextView sub = new TextView(this);
            sub.setText(subtitle); sub.setTextColor(FAINT); sub.setTextSize(13f);
            textCol.addView(sub);
        }
        row.addView(textCol);
        return row;
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text.toUpperCase());
        tv.setTextColor(FAINT); tv.setTextSize(11f);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private TextView small(String text) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextColor(MUTED); tv.setTextSize(13f);
        return tv;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(FAINT); e.setTextColor(TEXT); e.setTextSize(15f);
        e.setBackground(boxBg(FIELD, 8));
        e.setSingleLine(true);
        int p = dp(12); e.setPadding(p, p, p, p);
        doneKey(e);
        return e;
    }

    // Enter / Done dismisses the keyboard and drops focus, instead of jumping to
    // the next field.
    private void doneKey(EditText e) {
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                hideKeyboard(v);
                v.clearFocus();
                return true;
            }
            return false;
        });
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private Switch toggle(LinearLayout parent, String label) {
        Switch sw = new Switch(this);
        sw.setText(label); sw.setTextColor(TEXT); sw.setTextSize(14f);
        sw.setThumbTintList(ColorStateList.valueOf(Color.parseColor("#b5bac1")));
        sw.setTrackTintList(ColorStateList.valueOf(BLURPLE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, WC);
        lp.topMargin = dp(6);
        parent.addView(sw, lp);
        return sw;
    }

    private Button button(String text, int bgColor, int textColor, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setTextColor(textColor);
        b.setBackground(round(bgColor, 8));
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radiusDp));
        return g;
    }

    // Filled + visible outline, for text input fields.
    private GradientDrawable boxBg(int fill, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1.5f), Color.parseColor("#5c5f66"));
        return g;
    }

    private final class TargetAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int pos, View convert, ViewGroup parent) {
            TextView tv = (convert instanceof TextView) ? (TextView) convert : new TextView(MainActivity.this);
            Target t = shown.get(pos);
            tv.setText(("guild".equals(t.kind) ? "#  " : "@  ") + t.name);
            tv.setTextColor(TEXT); tv.setTextSize(15f);
            int q = dp(14); tv.setPadding(q, q, q, q);
            boolean isSel = t.id.equals(selGuildId) || t.id.equals(selChannelId);
            tv.setBackgroundColor(isSel ? SEL : Color.TRANSPARENT);
            return tv;
        }
    }

    private void applyFilter(String q) {
        String needle = q.trim().toLowerCase();
        shown.clear();
        for (Target t : all)
            if (needle.isEmpty() || (t.name != null && t.name.toLowerCase().contains(needle))) shown.add(t);
        adapter.notifyDataSetChanged();
    }

    // ---- login / visibility ----------------------------------------------------

    private void checkLogin(String url) { setLoggedIn(url != null && url.contains("/channels/")); }

    private void setLoggedIn(boolean in) {
        runOnUiThread(() -> {
            panel.setVisibility(in ? View.VISIBLE : View.GONE);
            if (in && !loadStarted) {
                loadStarted = true;
                showPicker();
                setStatus("Loading your servers and DMs...");
                loadTargets();
            }
        });
    }

    private void logout() {
        selGuildId = selChannelId = selName = null; selIsGuild = false;
        loadStarted = false;
        all.clear(); shown.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        CookieManager.getInstance().removeAllCookies(null);
        WebStorage.getInstance().deleteAllData();
        panel.setVisibility(View.GONE);
        web.loadUrl("https://discord.com/login");
    }

    private void confirmDelete() {
        if (selGuildId == null && selChannelId == null) { setStatus("Pick a server or DM first."); return; }
        String what = selIsGuild
            ? ("your messages in " + selName)
            : ("your messages in the DM with " + selName);
        new AlertDialog.Builder(this)
            .setTitle("Delete your messages?")
            .setMessage("This permanently deletes " + what + " (matching your filters). It cannot be "
                + "undone. Scan first if you're unsure.")
            .setPositiveButton("Delete", (d, w) -> startRun(false))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void loadTargets() {
        web.evaluateJavascript(
            "(function(){if(!window.__ZIVKORD__)return 'NO_ENGINE';window.__ZIVKORD__.listTargets();return 'LOADING';})()",
            value -> { if ("\"NO_ENGINE\"".equals(value)) setStatus("Log in to Discord first."); });
    }

    // ---- engine driving --------------------------------------------------------

    private void startRun(boolean dryRun) {
        if (running) { setStatus("Already running. Stop first."); return; }
        if (selGuildId == null && selChannelId == null) { setStatus("Pick a server or DM first."); return; }
        try {
            int[] pace = paceDelays();
            JSONObject o = new JSONObject();
            o.put("guildId", selGuildId != null ? selGuildId : JSONObject.NULL);
            o.put("channelId", selChannelId != null ? selChannelId : JSONObject.NULL);
            o.put("dryRun", dryRun);
            o.put("content", contentEdit.getText().toString().trim());
            o.put("hasLink", swLink.isChecked());
            o.put("hasFile", swFile.isChecked());
            o.put("hasEmbed", swEmbed.isChecked());
            o.put("includePinned", true);   // pinned + age-restricted are allowed by default
            o.put("includeNsfw", true);
            o.put("afterDate", afterEdit.getText().toString().trim());
            o.put("beforeDate", beforeEdit.getText().toString().trim());
            o.put("minDelay", pace[0]);
            o.put("maxDelay", pace[1]);
            o.put("limit", parseInt(limitEdit.getText().toString(), 0));
            boolean lb = swLongBreak.isChecked();
            o.put("batchSize", lb ? parseInt(breakEvery.getText().toString(), 25) : 0);
            o.put("batchRestMin", lb ? parseDouble(breakMins.getText().toString(), 1) : 0);
            String js = "(function(){if(!window.__ZIVKORD__)return 'NO_ENGINE';"
                + "window.__ZIVKORD__.run(" + o.toString() + ");return 'STARTED';})()";
            web.evaluateJavascript(js, value -> {
                if ("\"NO_ENGINE\"".equals(value)) setStatus("Engine not loaded. Log in first.");
                else if ("\"STARTED\"".equals(value)) setRunning(true);
            });
        } catch (Exception e) {
            setStatus("Could not start: " + e.getMessage());
        }
    }

    // ---- engine events ---------------------------------------------------------

    private void handleEngineEvent(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String type = o.optString("type");
            JSONObject st = o.optJSONObject("stats");
            switch (type) {
                case "ready": Log.i(TAG, "engine ready"); break;
                case "targets": {
                    JSONArray arr = o.optJSONArray("targets");
                    all.clear();
                    if (arr != null) for (int i = 0; i < arr.length(); i++) {
                        JSONObject t = arr.getJSONObject(i);
                        all.add(new Target(t.optString("kind"), t.optString("id"), t.optString("name")));
                    }
                    runOnUiThread(() -> {
                        applyFilter(searchBox != null ? searchBox.getText().toString() : "");
                        setStatus(all.isEmpty() ? "No servers or DMs found."
                            : ("Loaded " + all.size() + " servers and DMs. Pick one."));
                    });
                    break;
                }
                case "channels": {
                    JSONArray arr = o.optJSONArray("channels");
                    pendingChannels.clear();
                    if (arr != null) for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.getJSONObject(i);
                        pendingChannels.add(new String[]{ c.optString("id"), c.optString("name") });
                    }
                    showChannelScreen();
                    break;
                }
                case "started":
                    setRunning(true);
                    lastDryRun = o.optBoolean("dryRun");
                    runTarget = 0;
                    showProgress(true); // indeterminate while collecting matches
                    setStatus(lastDryRun ? "Scanning your messages..." : "Finding matching messages...");
                    break;
                case "found": {
                    int total = o.optInt("total");
                    int lim = parseInt(limitEdit.getText().toString(), 0);
                    runTarget = (lim > 0 && lim < total) ? lim : total;
                    setBarProgress(0);
                    setStatus("Found " + total + " matching message" + (total == 1 ? "" : "s")
                        + (lim > 0 && lim < total ? (" - acting on the most recent " + lim) : "") + ".");
                    break;
                }
                case "would-delete":
                    setBarProgress(runTarget > 0 ? st.optInt("deleted") * 100 / runTarget : 0);
                    setStatus("Scanning... " + st.optInt("deleted") + " of " + runTarget);
                    break;
                case "deleted":
                    setBarProgress(runTarget > 0 ? st.optInt("deleted") * 100 / runTarget : 0);
                    setStatus("Deleting... " + st.optInt("deleted") + " of " + runTarget
                        + " (skipped " + st.optInt("skipped") + ", failed " + st.optInt("failed") + ")");
                    break;
                case "ratelimited": setStatus("Rate-limited, waiting " + o.optDouble("seconds") + "s..."); break;
                case "indexing": setStatus("Discord indexing, waiting " + o.optDouble("seconds") + "s..."); break;
                case "breather": setStatus("Brief human-like pause..."); break;
                case "cooldown": setStatus("Long break (rested after " + o.optInt("deleted") + " deletes)..."); break;
                case "done":
                    // NOTE: done/stopped carry stats at the TOP level (emit('done', stats)),
                    // not under a nested "stats" key like the per-message events.
                    setRunning(false);
                    setBarProgress(100);
                    if (lastDryRun)
                        setStatus("Scan complete. " + o.optInt("deleted") + " message(s) match and would be deleted.");
                    else
                        setStatus("Job complete! Removed " + o.optInt("deleted") + " of " + o.optInt("found")
                            + " (skipped " + o.optInt("skipped") + ", failed " + o.optInt("failed") + ").");
                    break;
                case "stopped":
                    setRunning(false); hideProgress();
                    setStatus("Stopped. Removed " + o.optInt("deleted") + ".");
                    break;
                case "error":
                    setRunning(false); hideProgress();
                    if (all.isEmpty()) loadStarted = false;
                    setStatus("Error: " + o.optString("message"));
                    break;
                default: break;
            }
        } catch (Exception e) { Log.w(TAG, "bad engine event: " + json, e); }
    }

    // ---- helpers ---------------------------------------------------------------

    private static boolean isDiscordHost(String host) {
        String h = host.toLowerCase();
        for (String d : ALLOWED_HOSTS) if (h.equals(d) || h.endsWith("." + d)) return true;
        return false;
    }

    private void setRunning(boolean r) {
        runOnUiThread(() -> {
            running = r;
            scanBtn.setEnabled(!r); deleteBtn.setEnabled(!r); stopBtn.setEnabled(r);
            if (runWarn != null) runWarn.setVisibility(r ? View.VISIBLE : View.GONE);
            if (r) {
                // Keep the screen on (so the WebView isn't throttled) and start the
                // foreground service (so the OS won't kill the run + shows progress).
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                serviceCmd(DeleteService.ACTION_START, "Working...");
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                serviceCmd(DeleteService.ACTION_STOP, null);
            }
        });
    }

    private void setStatus(String text) {
        runOnUiThread(() -> {
            if (pickStatus != null) pickStatus.setText(text);
            if (ctrlStatus != null) ctrlStatus.setText(text);
        });
        if (running) serviceCmd(DeleteService.ACTION_UPDATE, text);
        Log.i(TAG, "STATUS " + text);
    }

    private void serviceCmd(String action, String text) {
        try {
            Intent i = new Intent(this, DeleteService.class).setAction(action);
            if (text != null) i.putExtra(DeleteService.EXTRA_TEXT, text);
            if (DeleteService.ACTION_STOP.equals(action)) {
                startService(i); // already-running service handles STOP -> stopSelf
            } else if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "service cmd failed: " + e.getMessage());
        }
    }

    private void showProgress(boolean indeterminate) {
        runOnUiThread(() -> { progressBar.setVisibility(View.VISIBLE); progressBar.setIndeterminate(indeterminate); });
    }
    private void setBarProgress(int pct) {
        runOnUiThread(() -> { progressBar.setIndeterminate(false); progressBar.setProgress(Math.max(0, Math.min(100, pct))); });
    }
    private void hideProgress() { runOnUiThread(() -> progressBar.setVisibility(View.GONE)); }

    private static int parseInt(String s, int def) {
        try { return Math.max(0, Integer.parseInt(s.trim())); } catch (Exception e) { return def; }
    }
    private static double parseDouble(String s, double def) {
        try { double d = Double.parseDouble(s.trim()); return d < 0 ? def : d; } catch (Exception e) { return def; }
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    static final int MP = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WC = ViewGroup.LayoutParams.WRAP_CONTENT;
    private FrameLayout.LayoutParams frame() { return new FrameLayout.LayoutParams(MP, MP); }
    private LinearLayout.LayoutParams mt(int m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, WC); lp.topMargin = m; return lp;
    }

    private String readAsset(String name) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(getAssets().open(name)))) {
            String line; while ((line = br.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception e) { Log.e(TAG, "could not read asset " + name, e); }
        return sb.toString();
    }
}
