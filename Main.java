package com.poc.gctune;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GC Tune - minimal terminal on top of the Game Cube uid1000 command channel.
 *
 * The command is smuggled into the SurfaceFlinger layer name; FpsManager runs
 *   sh -c  dumpsys SurfaceFlinger --latency "<layer>"
 * so the $(...) block executes as uid 1000. Its stdout+stderr are base64
 * encoded and parked in Settings.System (gc_tune_ack = "N|base64"), which the
 * poller decodes back into the console.
 */
public class Main extends Activity {

    private static final String PKG = "com.poc.gctune";
    private static final String ACK_KEY = "gc_tune_ack";

    /**
     * Bootstrap of the uid1000 watcher, prepended to every payload while the
     * daemon toggle is on. No-op when a watcher is already alive (orphaned
     * watcher = ppid 1, "gctw" in cmdline). The watcher blocks on
     * `logcat -s GCTune:I` - idle cost is exactly zero - and on a WAKE line
     * runs `dumpsys SurfaceFlinger --latency "<our layer>"`, which re-parses
     * the layer name and executes the payload as uid1000, byte identical to
     * the Game Cube panel path. Lives until reboot; the next panel pull after
     * a reboot respawns it.
     *
     * Both branches spawn without post-hoc killing: the spawned program waits
     * 0.3s, then the lowest gctw pid wins - a racing twin (two panel execs
     * seeing no watcher at once) simply exits before starting its loop, so
     * exactly one watcher reaches the pipeline. Transient exec-chain lines
     * (`--latency com.poc...`) are filtered out so the sh/dumpsys carrying
     * the payload text are never counted; ps prints argv without the shell's
     * quote characters, hence the quote-less pattern.
     *
     * Note: this string sits inside the layer name, i.e. inside the $( ... )
     * block that FpsManager expands within double quotes. The shell parses
     * $( ... ) as an independent context, so quoting here is safe; the user
     * command itself still goes through executeCurrentCommand's " -> ' pass.
     */
    private static final String DAEMON_BOOT =
            "if ps -A -o PPID,ARGS 2>/dev/null|grep \"[g]ctw\"|grep -vq -- \"--latency com.poc\"; then :; "
            + "else setsid sh -c 'W=gctw;sleep 0.3;"
            + "w=$(ps -A -o PID,ARGS 2>/dev/null|grep \"[g]ctw\"|grep -v -- \"--latency com.poc\""
            + "|awk \"{print \\$1}\"|sort -n|head -n 1);"
            + "[ -n \"$w\" ]&&[ \"$w\" != \"$$\" ]&&exit 0;"
            + "while :;do logcat -s GCTune:I 2>/dev/null"
            + "|while IFS= read -r l;do case $l in *WAKE*)"
            + "n=$(dumpsys SurfaceFlinger --list 2>/dev/null|grep -m1 com.poc.gctune/x);"
            + "[ -n \"$n\" ]&&sh -c \"dumpsys SurfaceFlinger --latency \\\"$n\\\"\";;"
            + "esac;done;done' >/dev/null 2>&1 </dev/null & fi;";
    private static final String BENIGN_TITLE = "GC Tune";
    private static final int REQ_IMPORT_FILE = 2001;
    private static final int REQ_PICK_ITZ = 2002;
    private static final int RC_UNKNOWN = -999;

    /** Staging location of the picked .itz (public Download/gctune/theme.itz first). */
    private static final String STAGE_NAME = "theme.itz";
    private static final String STAGE_DIR = "gctune";
    private static final float NAV_H_DP = 62f;

    /** Appended by the payload after the user command; travels inside the blob. */
    private static final String RC_MARK = "__RC__";
    private static final Pattern RC_PAT = Pattern.compile(RC_MARK + "(-?\\d+)\\s*$");
    private static final String NO_OUTPUT = "(команда выполнена без текстового вывода)";
    private static final int MAX_ENTRIES = 60;

    private Handler h;
    private ContentResolver cr;
    private SharedPreferences prefs;

    private ImageView statusDot;
    private TextView statusText;
    private EditText cmdInput;
    private ImageView runBtn;
    private TextView logView;
    private ScrollView logScroll;

    private M3Icons.Icon iconPlay, iconStop, iconDotOn, iconDotOff;

    private final List<Entry> entries = new ArrayList<>();
    private int ackCounter = 1000000;
    private int curAck = 0;
    private long deadline = 0;
    private boolean busy = false;
    private int timeoutSec = 30;

    // ------------------------------------------------------- navigation pages
    private View shellPage, themePage, iconsPage, settingsPage;
    private View pasteBtn;
    private final View[] navItems = new View[4];
    private final ImageView[] navIcons = new ImageView[4];
    private final TextView[] navLabels = new TextView[4];
    private int page = 0;

    // ---------------------------------------------------------------- theme
    private TextView themeFileText, themeStatusText;
    private View themeHint, installBtn;
    private ObjectAnimator hintPulse;
    private String stagedPath;
    private String stagedMd5;
    private long stagedBytes;
    private boolean staging = false;
    private boolean themeRun = false;
    private EditText settingsTimeout;

    // ---------------------------------------------------------------- icons
    private TextView iconsStatusText;
    private LinearLayout iconsPreviewRow;
    private EditText iconsColorInput;
    private int iconsColor = 0xFF3D7D56;
    // fine tuning of the recolor (percent, see IconsTinter.compose)
    private int iconsDesat = 100;
    private int iconsLighten = 0;
    private int iconsStrength = 100;
    // icon pack source ("" = system icons)
    private IconPackLoader packLoader;
    private String iconsPackPkg = "";
    private String iconsPackLabel = "";
    private TextView iconsSourceValue;
    // IconPack Studio style: background layer + foreground icon
    private boolean iconsBgEnabled = false;
    private int iconsBgColor = 0xFFFFFFFF;
    private int iconsFgScale = 76;
    private int iconsRadius = 25;
    private EditText iconsBgInput;
    private SeekBar sbDesat, sbLighten, sbStrength, sbFgScale, sbRadius;
    // icon-style flip state (see toggleIconsStyle)
    private String pendingOldStyle = null;
    private boolean styleToggleOk = true;
    private boolean iconsRun = false;
    private List<String> iconsPackages = new ArrayList<>();
    private List<Drawable> iconsOriginals = new ArrayList<>();
    private List<Drawable> iconsTinted = new ArrayList<>();

    /** One console line: either a plain status note or a command with its result. */
    private static final class Entry {
        boolean status;
        String cmd;
        String out;
        int rc = RC_UNKNOWN;
        boolean pending;
        boolean failed;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            h.postDelayed(this, 1500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        h = new Handler(Looper.getMainLooper());
        cr = getContentResolver();
        prefs = getSharedPreferences("gctune_m3", MODE_PRIVATE);

        iconPlay = M3Icons.play(Ui.ACCENT, Ui.dp(this, 20));
        iconStop = M3Icons.stop(Ui.TEXT, Ui.dp(this, 18));
        iconDotOn = M3Icons.dot(Ui.ACCENT, Ui.dp(this, 9));
        iconDotOff = M3Icons.dotHollow(Ui.MUTED, Ui.dp(this, 9));

        // ACK delivery + retries live in ChannelService (survives backgrounding)
        ChannelService.cb = new ChannelService.Cb() {
            @Override
            public void onDone(final boolean ok, final String out) {
                h.post(new Runnable() {
                    @Override public void run() { finishRun(ok, out); }
                });
            }
        };

        buildUi();
        setWinTitle(BENIGN_TITLE);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.postDelayed(ticker, 400L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ChannelService.cb = null;
        if (hintPulse != null) hintPulse.cancel();
    }

    // ------------------------------------------------------------------ UI
    private void buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.BG);

        shellPage = buildShellPage();
        themePage = buildThemePage();
        iconsPage = buildIconsPage();
        settingsPage = buildSettingsPage();

        FrameLayout pages = new FrameLayout(this);
        pages.addView(shellPage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(themePage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(iconsPage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(settingsPage, new FrameLayout.LayoutParams(-1, -1));
        themePage.setVisibility(View.GONE);
        iconsPage.setVisibility(View.GONE);
        settingsPage.setVisibility(View.GONE);
        FrameLayout.LayoutParams pagesLp = new FrameLayout.LayoutParams(-1, -1);
        pagesLp.bottomMargin = Ui.dp(this, NAV_H_DP);
        frame.addView(pages, pagesLp);

        pasteBtn = pasteButton();
        FrameLayout.LayoutParams pasteLp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        pasteLp.rightMargin = Ui.dp(this, 20);
        pasteLp.bottomMargin = Ui.dp(this, NAV_H_DP + 24);
        frame.addView(pasteBtn, pasteLp);

        LinearLayout nav = navBar();
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        frame.addView(nav, navLp);

        setContentView(frame);
        switchPage(0);
    }

    /** Console tab: top bar + status + input + log. */
    private View buildShellPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = Ui.col(this);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 30));
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -2));

        root.addView(topBar());
        root.addView(titleRow());
        root.addView(inputRow());
        root.addView(consoleCard());
        return scroll;
    }

    private View pasteButton() {
        TextView paste = Ui.label(this, "Вставить", 13f, Ui.ACCENT);
        paste.setGravity(Gravity.CENTER);
        paste.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));
        paste.setBackground(Ui.pressable(this, Ui.PRIMARY, Ui.PRIMARY_PRESSED, 16f));
        paste.setCompoundDrawablesWithIntrinsicBounds(
                M3Icons.clipboard(Ui.ACCENT, Ui.dp(this, 17)), null, null, null);
        paste.setCompoundDrawablePadding(Ui.dp(this, 8));
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pasteFromClipboard(); }
        });
        return paste;
    }

    // ------------------------------------------------------------- navigation
    private LinearLayout navBar() {
        LinearLayout wrap = Ui.col(this);
        wrap.setBackgroundColor(Ui.SURFACE);

        View divider = new View(this);
        divider.setBackgroundColor(Ui.BORDER_SOFT);
        wrap.addView(divider, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));

        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 9));

        navItems[0] = navItem(0, M3Icons.terminal(Ui.MUTED, Ui.dp(this, 20)), "Shell");
        navItems[1] = navItem(1, M3Icons.palette(Ui.MUTED, Ui.dp(this, 20)), "Тема");
        navItems[2] = navItem(2, M3Icons.grid(Ui.MUTED, Ui.dp(this, 20)), "Иконки");
        navItems[3] = navItem(3, M3Icons.tune(Ui.MUTED, Ui.dp(this, 20)), "Настройки");
        for (View v : navItems) row.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));

        wrap.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }

    private View navItem(final int idx, M3Icons.Icon icon, String label) {
        LinearLayout item = Ui.col(this);
        item.setGravity(Gravity.CENTER);
        item.setPadding(Ui.dp(this, 4), Ui.dp(this, 7), Ui.dp(this, 4), Ui.dp(this, 7));

        ImageView ic = new ImageView(this);
        ic.setImageDrawable(icon);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        item.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 21), Ui.dp(this, 21)));

        TextView lb = Ui.label(this, label, 10.5f, Ui.MUTED);
        lb.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(-2, -2);
        lbLp.topMargin = Ui.dp(this, 3);
        item.addView(lb, lbLp);

        navIcons[idx] = ic;
        navLabels[idx] = lb;
        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchPage(idx); }
        });
        return item;
    }

    private void switchPage(int idx) {
        page = idx;
        shellPage.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        themePage.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        iconsPage.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);
        if (pasteBtn != null) pasteBtn.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 4; i++) {
            boolean sel = i == idx;
            Drawable d = navIcons[i].getDrawable();
            if (d != null) d.setTint(sel ? Ui.ACCENT : Ui.MUTED);
            navLabels[i].setTextColor(sel ? Ui.ACCENT : Ui.MUTED);
            navItems[i].setBackground(sel
                    ? Ui.pressable(this, Ui.PRIMARY, Ui.PRIMARY_PRESSED, 14f)
                    : Ui.shape(this, 0x00000000, 14f, 0, 0));
        }
    }

    private View topBar() {
        LinearLayout row = Ui.row(this);

        ImageView importBtn = iconButton(
                M3Icons.folder(Ui.ACCENT, Ui.dp(this, 21)),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { pickScriptFile(); }
                });
        row.addView(importBtn, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        ImageView clearBtn = iconButton(
                M3Icons.trash(Ui.ACCENT, Ui.dp(this, 21)),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        entries.clear();
                        renderLog();
                    }
                });
        LinearLayout.LayoutParams clearLp =
                new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        clearLp.leftMargin = Ui.dp(this, 14);
        row.addView(clearBtn, clearLp);

        row.addView(new View(this), Ui.grow());

        ImageView settingsBtn = iconButton(
                M3Icons.tune(Ui.ACCENT, Ui.dp(this, 21)),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { switchPage(2); }
                });
        row.addView(settingsBtn, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 20);
        row.setLayoutParams(lp);
        return row;
    }

    private ImageView iconButton(M3Icons.Icon icon, View.OnClickListener l) {
        ImageView b = new ImageView(this);
        b.setImageDrawable(icon);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setBackground(Ui.pressable(this, Ui.PRIMARY, Ui.PRIMARY_PRESSED, 22f));
        int p = Ui.dp(this, 11);
        b.setPadding(p, p, p, p);
        b.setOnClickListener(l);
        return b;
    }

    private View titleRow() {
        LinearLayout row = Ui.row(this);

        TextView title = Ui.label(this, "GC Tune", 26f, Ui.TEXT);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        statusDot = new ImageView(this);
        statusDot.setImageDrawable(iconDotOff);
        statusDot.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(-2, -2);
        dotLp.rightMargin = Ui.dp(this, 6);
        row.addView(statusDot, dotLp);

        statusText = Ui.text(this, "", 11f, Ui.MUTED);
        row.addView(statusText);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 18);
        row.setLayoutParams(lp);
        return row;
    }

    private View inputRow() {
        LinearLayout row = Ui.row(this);

        LinearLayout box = Ui.col(this);
        box.setBackground(Ui.shape(this, Ui.INPUT_BG, 12f, Ui.BORDER, 1.4f));
        box.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 6));

        cmdInput = new EditText(this);
        cmdInput.setBackground(null);
        cmdInput.setTextColor(Ui.TEXT);
        cmdInput.setHintTextColor(Ui.MUTED);
        cmdInput.setHint("Команда или скрипт");
        cmdInput.setTypeface(Typeface.MONOSPACE);
        cmdInput.setTextSize(14f);
        cmdInput.setGravity(Gravity.TOP | Gravity.START);
        cmdInput.setMinLines(2);
        cmdInput.setMaxLines(8);
        cmdInput.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        box.addView(cmdInput, new LinearLayout.LayoutParams(-1, -2));

        row.addView(box, new LinearLayout.LayoutParams(0, -2, 1f));

        runBtn = new ImageView(this);
        runBtn.setImageDrawable(iconPlay);
        runBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        runBtn.setBackground(Ui.pressable(this, Ui.PRIMARY, Ui.PRIMARY_PRESSED, 16f));
        int p = Ui.dp(this, 13);
        runBtn.setPadding(p, p, p, p);
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (busy) cancelRun(); else executeCurrentCommand();
            }
        });
        LinearLayout.LayoutParams runLp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        runLp.leftMargin = Ui.dp(this, 10);
        row.addView(runBtn, runLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 14);
        lp.bottomMargin = Ui.dp(this, 18);
        row.setLayoutParams(lp);
        return row;
    }

    private View consoleCard() {
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        card.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));

        logScroll = new ScrollView(this);
        logScroll.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        logScroll.setVerticalScrollBarEnabled(false);

        logView = new TextView(this);
        logView.setTextColor(Ui.TEXT);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12f);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);

        card.addView(logScroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, 250)));
        return card;
    }

    // ----------------------------------------------------------------- theme
    private View buildThemePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = Ui.col(this);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 30));
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -2));

        TextView title = Ui.label(this, "Тема", 26f, Ui.TEXT);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.bottomMargin = Ui.dp(this, 16);
        root.addView(title, titleLp);

        themeHint = hintCard();
        themeHint.setVisibility(View.GONE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.bottomMargin = Ui.dp(this, 14);
        root.addView(themeHint, hintLp);

        // ---- file card
        LinearLayout fileCard = Ui.col(this);
        fileCard.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        fileCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        fileCard.addView(Ui.label(this, "Файл темы (.itz)", 12.5f, Ui.MUTED));

        themeFileText = Ui.text(this, "файл не выбран", 14f, Ui.TEXT);
        LinearLayout.LayoutParams fileLp = new LinearLayout.LayoutParams(-2, -2);
        fileLp.topMargin = Ui.dp(this, 6);
        fileCard.addView(themeFileText, fileLp);

        View choose = pillButton(M3Icons.folder(Ui.ACCENT, Ui.dp(this, 18)), "Выбрать .itz",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { pickThemeFile(); }
                });
        LinearLayout.LayoutParams chooseLp = new LinearLayout.LayoutParams(-1, -2);
        chooseLp.topMargin = Ui.dp(this, 12);
        fileCard.addView(choose, chooseLp);

        LinearLayout.LayoutParams fileCardLp = new LinearLayout.LayoutParams(-1, -2);
        fileCardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(fileCard, fileCardLp);

        // ---- install button
        View install = pillButton(M3Icons.play(Ui.ACCENT, Ui.dp(this, 18)),
                "Установить и открыть iTheme",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { runThemeInstall(); }
                });
        installBtn = install;
        LinearLayout.LayoutParams installLp = new LinearLayout.LayoutParams(-1, -2);
        installLp.bottomMargin = Ui.dp(this, 14);
        root.addView(install, installLp);

        // ---- status card
        LinearLayout statusCard = Ui.col(this);
        statusCard.setBackground(Ui.shape(this, Ui.INPUT_BG, 12f, Ui.BORDER, 1.2f));
        statusCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        themeStatusText = Ui.text(this, "Выберите .itz и нажмите «Установить»", 13f, Ui.MUTED);
        statusCard.addView(themeStatusText);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.bottomMargin = Ui.dp(this, 14);
        root.addView(statusCard, statusLp);

        TextView note = Ui.text(this,
                "Выбранный файл используется по его настоящему пути (если возможно); иначе копия "
                        + "кладётся в Download/gctune. Команда через канал Game Cube заменяет только "
                        + ".itz в папке темы .../theme/.dwd/.../T, сверяет md5 и открывает iTheme. "
                        + "Выполнится при открытом режиме игры.", 11.5f, Ui.MUTED);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.bottomMargin = Ui.dp(this, 24);
        root.addView(note, noteLp);

        return scroll;
    }

    private View pillButton(M3Icons.Icon icon, String label, View.OnClickListener onClick) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER);
        row.setBackground(Ui.pressable(this, Ui.PRIMARY, Ui.PRIMARY_PRESSED, 16f));
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 13), Ui.dp(this, 16), Ui.dp(this, 13));

        ImageView ic = new ImageView(this);
        ic.setImageDrawable(icon);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));

        TextView lb = Ui.label(this, label, 14f, Ui.TEXT);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(-2, -2);
        lbLp.leftMargin = Ui.dp(this, 9);
        row.addView(lb, lbLp);

        row.setOnClickListener(onClick);
        return row;
    }

    /** "Open the game mode" hint with an arrow, pulses while a command is pending. */
    private View hintCard() {
        LinearLayout card = Ui.row(this);
        card.setBackground(Ui.shape(this, 0x1482E5A6, 12f, Ui.ACCENT, 1.2f));
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 13), Ui.dp(this, 14), Ui.dp(this, 13));

        ImageView arrow = new ImageView(this);
        arrow.setImageDrawable(M3Icons.arrowUpRight(Ui.ACCENT, Ui.dp(this, 26)));
        arrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));

        LinearLayout col = Ui.col(this);
        col.addView(Ui.label(this, "Откройте режим игры", 15f, Ui.ACCENT));
        TextView sub = Ui.text(this,
                "Команда выполнится сама, как только откроете шторку Game Cube — "
                        + "FpsManager запустит её автоматически.", 12f, Ui.TEXT);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = Ui.dp(this, 4);
        col.addView(sub, subLp);

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, -2, 1f);
        colLp.leftMargin = Ui.dp(this, 12);
        card.addView(col, colLp);
        return card;
    }

    // -------------------------------------------------------- theme: picking
    private void pickThemeFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/octet-stream", "application/zip", "application/x-zip-compressed", "*/*"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Файл темы (.itz)"), REQ_PICK_ITZ);
        } catch (Throwable t) {
            toast("Проводник недоступен");
        }
    }

    /**
     * Prepares the picked .itz for the uid 1000 channel: prefers the ORIGINAL file
     * path (resolved from the SAF uri) so the command works on the user's own file,
     * falls back to copying into Download/gctune via MediaStore (no permission needed).
     * stagedPath is nulled while staging so an install can never use a stale file.
     */
    private void stageThemeFile(final Uri src) {
        if (staging) {
            toast("Файл ещё готовится");
            return;
        }
        staging = true;
        stagedPath = null;
        stagedMd5 = null;
        themeFileText.setText("подготовка…");
        themeStatus("Читаю файл…", Ui.MUTED);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String picked = queryDisplayName(src);
                    String path = null;
                    String md5 = null;

                    // 1) the picked file itself, if it already lives on /storage
                    String real = resolveRealPath(src);
                    if (real != null && real.startsWith("/")
                            && (real.startsWith("/storage/") || real.startsWith("/sdcard/"))
                            && !real.contains("/Android/") && new File(real).isFile()) {
                        try {
                            md5 = md5File(new File(real));
                            path = real;
                        } catch (Throwable t) {
                            Log.e("GCTune", "direct stage failed: " + t);
                            path = null;
                        }
                    }

                    // 2) copy into a location the channel can read
                    if (path == null) {
                        String staged = null;
                        Throwable firstErr = null;
                        if (Build.VERSION.SDK_INT >= 29) {
                            try {
                                staged = copyToDownloads(src);
                            } catch (Throwable t) {
                                Log.e("GCTune", "MediaStore stage failed: " + t);
                                firstErr = t;
                            }
                        }
                        if (staged == null) {
                            try {
                                staged = copyToAppFiles(src);
                            } catch (Throwable t) {
                                if (firstErr == null) firstErr = t;
                            }
                        }
                        if (staged == null) {
                            throw firstErr != null ? firstErr : new Exception("копирование не удалось");
                        }
                        // reading it back proves the reported path really holds the copy
                        md5 = md5File(new File(staged));
                        path = staged;
                    }

                    // 2.5) id-substitution: custom .itz files keep the ids of paid
                    // store themes as placeholders, so iTheme shows a price on them.
                    // Stage a copy with neutral ids instead of the picked file.
                    String idNote = null;
                    if (path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".itz")) {
                        File dl = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS);
                        File dst = new File(dl, STAGE_DIR + "/" + STAGE_NAME);
                        File tmp = new File(dl, STAGE_DIR + "/" + STAGE_NAME + ".tmp");
                        try {
                            int n = ThemePatcher.patch(new File(path), tmp);
                            if (n > 0) {
                                // rename replaces the destination atomically (same dir)
                                if (!tmp.renameTo(dst)) {
                                    throw new Exception("не удалось записать " + dst.getName());
                                }
                                path = dst.getAbsolutePath();
                                md5 = md5File(dst);
                                idNote = "id подменены: " + n + " → "
                                        + ThemePatcher.FREE_ID + "/" + ThemePatcher.FREE_UID;
                            } else {
                                tmp.delete();
                                idNote = "description.xml без id — файл не тронут";
                            }
                        } catch (Throwable t) {
                            Log.e("GCTune", "id patch failed: " + t);
                            tmp.delete();
                            idNote = "id НЕ подменены: " + t.getMessage();
                        }
                    }

                    final String fp = path;
                    final String idNoteF = idNote;
                    final String fm = md5;
                    final long size = new File(path).length();
                    final String sizeTxt = humanSize(size);
                    h.post(new Runnable() {
                        @Override public void run() {
                            staging = false;
                            stagedPath = fp;
                            stagedMd5 = fm;
                            themeFileText.setText(picked + " · " + sizeTxt + "\n" + fp);
                            themeStatus("Файл готов, md5 " + fm + " — можно устанавливать"
                                    + (idNoteF != null ? "\n" + idNoteF : ""), Ui.TEXT);
                        }
                    });
                } catch (final Throwable t) {
                    h.post(new Runnable() {
                        @Override public void run() {
                            staging = false;
                            stagedPath = null;
                            stagedMd5 = null;
                            themeFileText.setText("файл не выбран");
                            themeStatus("Ошибка подготовки: " + t.getMessage(), Ui.ERROR);
                        }
                    });
                }
            }
        }).start();
    }

    /** Best-effort filesystem path behind a SAF/content uri (null if none). */
    private String resolveRealPath(Uri uri) {
        if (uri == null) return null;
        if ("file".equals(uri.getScheme())) return uri.getPath();
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DATA}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String p = c.getString(0);
                if (p != null && !p.isEmpty()) return p;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** Streaming MD5 of a local file (also used to verify what the channel reads). */
    private static String md5File(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        InputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(32);
        for (byte b : d) sb.append(String.format(java.util.Locale.US, "%02x", b));
        return sb.toString();
    }

    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return STAGE_NAME;
    }

    /** API 29+: public Download/gctune/theme.itz via MediaStore (no storage permission). */
    private String copyToDownloads(Uri src) throws Exception {
        ContentResolver r = getContentResolver();
        String rel = Environment.DIRECTORY_DOWNLOADS + "/" + STAGE_DIR;

        Cursor old = r.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                        + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{STAGE_NAME, rel + "/"}, null);
        if (old != null) {
            while (old.moveToNext()) {
                try {
                    r.delete(ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, old.getLong(0)), null, null);
                } catch (Throwable ignored) {}
            }
            old.close();
        }

        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, STAGE_NAME);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
        Uri out = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (out == null) throw new Exception("MediaStore insert failed");

        stagedBytes = 0;
        InputStream in = r.openInputStream(src);
        if (in == null) throw new Exception("источник недоступен");
        OutputStream os = r.openOutputStream(out);
        if (os == null) {
            in.close();
            throw new Exception("хранилище недоступно");
        }
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                stagedBytes += n;
            }
            os.flush();
        } finally {
            try { os.close(); } catch (Throwable ignored) {}
            try { in.close(); } catch (Throwable ignored) {}
        }

        String path = null;
        Cursor q = r.query(out, new String[]{MediaStore.MediaColumns.DATA}, null, null, null);
        if (q != null) {
            if (q.moveToFirst()) path = q.getString(0);
            q.close();
        }
        if (path == null || path.isEmpty()) {
            path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    + "/" + STAGE_DIR + "/" + STAGE_NAME;
        }
        return path;
    }

    /** Fallback: app-specific external dir (needs no permission on any API level). */
    private String copyToAppFiles(Uri src) throws Exception {
        File dir = getExternalFilesDir(null);
        if (dir == null) throw new Exception("нет внешнего хранилища");
        File f = new File(dir, STAGE_NAME);

        InputStream in = getContentResolver().openInputStream(src);
        if (in == null) throw new Exception("источник недоступен");
        OutputStream os = new FileOutputStream(f);
        stagedBytes = 0;
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                stagedBytes += n;
            }
            os.flush();
        } finally {
            try { os.close(); } catch (Throwable ignored) {}
            try { in.close(); } catch (Throwable ignored) {}
        }
        return f.getAbsolutePath();
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1048576L) {
            return String.format(java.util.Locale.US, "%.1f МБ", bytes / 1048576.0);
        }
        if (bytes >= 1024L) return (bytes / 1024L) + " КБ";
        return bytes + " Б";
    }

    // ---------------------------------------------------------- theme: install
    private void runThemeInstall() {
        if (busy) {
            toast("Дождитесь текущей команды");
            return;
        }
        if (staging) {
            toast("Файл ещё готовится — подождите");
            return;
        }
        if (stagedPath == null || stagedPath.isEmpty()) {
            toast("Сначала выберите .itz");
            return;
        }
        themeRun = true;
        themeStatus("Команда отправлена — откройте режим игры…", Ui.ACCENT);
        showHint();
        if (!apply(themeCommand())) {
            themeRun = false;
            hideHint();
            themeStatus("GC Tune не в списке игр — добавьте приложение и повторите", Ui.ERROR);
        }
    }

    /**
     * Install command: probes the staged source (and verifies its md5 against the one
     * computed at staging time, so a stale file is reported instead of silently used),
     * replaces every .itz inside the theme package's .../T directory only, verifies
     * each copy, then restarts iTheme and opens it. Package name is derived from the
     * .dwd path letters - no hard-coded device, theme or file names.
     */
    private String themeCommand() {
        String src = (stagedPath == null ? "" : stagedPath).replace("'", "");
        String expect = stagedMd5 == null ? "" : stagedMd5;
        return "EXPECT=" + expect + "; S=; for p in '" + src + "'"
                + " /storage/emulated/0/Download/" + STAGE_DIR + "/" + STAGE_NAME
                + " /sdcard/Download/" + STAGE_DIR + "/" + STAGE_NAME
                + " /storage/emulated/0/Android/data/" + PKG + "/files/" + STAGE_NAME
                + "; do [ -r \"$p\" ] && S=\"$p\" && break; done;"
                + "if [ -z \"$S\" ]; then echo SRC_UNREADABLE;"
                + " else A=$(md5sum \"$S\" | cut -d' ' -f1);"
                + " if [ -n \"$EXPECT\" ] && [ \"$A\" != \"$EXPECT\" ]; then echo SRC_STALE:$A;"
                + " else echo SRC_OK;"
                + " LIST=$(find /data/bbkcore -name '*.itz' -path '*/T/*' 2>/dev/null);"
                + " if [ -z \"$LIST\" ]; then echo NO_THEME_FOUND;"
                + " else T1=$(echo \"$LIST\" | head -n1); P=${T1%/*}; P=${P%/*}; P=${P##*/.dwd/};"
                + " LE=$(echo \"$P\" | tr -d /);"
                + " PKG=$(pm list packages 2>/dev/null | sed 's/^package://' | while read q;"
                + " do [ \"$(echo \"$q\" | tr -d .)\" = \"$LE\" ] && echo \"$q\"; done | head -n1);"
                + " case \"$PKG\" in *.*.*) am force-stop \"$PKG\";; esac;"
                + " echo \"$LIST\" | while read T; do cat \"$S\" > \"$T\" 2>/dev/null;"
                + " B=$(md5sum \"$T\" | cut -d' ' -f1);"
                + " if [ \"$A\" = \"$B\" ]; then echo OK:$T; else echo FAIL:$T; fi; done;"
                + " echo FOUND=$(echo \"$LIST\" | wc -l);"
                + " case \"$PKG\" in *.*.*) "
                + " ACT=$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER \"$PKG\" 2>/dev/null | tail -n1);"
                + " echo ACT=$ACT;"
                + " O=$(am start -n \"$ACT\" 2>&1 | tail -n3); echo \"$O\";"
                + " echo \"$O\" | grep -q Starting && echo APP_OPENED || {"
                + " M=$(monkey -p \"$PKG\" -c android.intent.category.LAUNCHER 1 2>&1 | tail -n2); echo \"$M\";"
                + " echo \"$M\" | grep -q 'Events injected' && echo APP_OPENED || echo APP_FAILED; };;"
                + " *) echo NO_PKG;; esac;"
                + " fi; fi; fi";
    }

    private void themeStatus(String s, int color) {
        if (themeStatusText == null) return;
        themeStatusText.setText(s);
        themeStatusText.setTextColor(color);
    }

    private void showHint() {
        if (themeHint == null) return;
        themeHint.setVisibility(View.VISIBLE);
        if (hintPulse == null) {
            hintPulse = ObjectAnimator.ofFloat(themeHint, View.ALPHA, 1f, 0.35f);
            hintPulse.setDuration(700);
            hintPulse.setRepeatCount(ValueAnimator.INFINITE);
            hintPulse.setRepeatMode(ValueAnimator.REVERSE);
        }
        hintPulse.start();
    }

    private void hideHint() {
        if (themeHint == null) return;
        if (hintPulse != null) hintPulse.cancel();
        themeHint.setAlpha(1f);
        themeHint.setVisibility(View.GONE);
    }

    /** Turns the uid 1000 output of the theme command into a human status line. */
    private void themeResult(boolean ok, Entry e) {
        themeRun = false;
        hideHint();
        String out = (ok && e != null && e.out != null) ? e.out : "";
        if (!ok) {
            themeStatus("Нет ответа от uid 1000 — откройте режим игры и повторите", Ui.ERROR);
        } else if (out.contains("SRC_UNREADABLE")) {
            themeStatus("uid 1000 не может прочитать файл-источник", Ui.ERROR);
        } else if (out.contains("SRC_STALE")) {
            themeStatus("Источник не совпадает с выбранным файлом — повторите выбор", Ui.ERROR);
        } else if (out.contains("NO_THEME_FOUND")) {
            themeStatus("В папке .../theme/.dwd/.../T нет ни одного .itz", Ui.ERROR);
        } else if (out.contains("NO_PKG")) {
            themeStatus("Пакет iTheme определить не удалось — подробности в консоли", Ui.ERROR);
        } else if (!out.contains("OK:")) {
            themeStatus("Запись не удалась — вывод в консоли (вкладка Shell)", Ui.ERROR);
        } else {
            int n = countOccurrences(out, "OK:");
            int fails = countOccurrences(out, "FAIL:");
            String base = "Заменено файлов: " + n + (fails > 0 ? ", ошибок: " + fails : "");
            if (out.contains("APP_FAILED")) {
                themeStatus(base + " — iTheme не открылся, вывод в консоли", Ui.ERROR);
            } else if (out.contains("APP_OPENED")) {
                themeStatus(base + ", iTheme открыт — применяйте тему", Ui.ACCENT);
            } else {
                themeStatus(base + " — подробности в консоли", Ui.TEXT);
            }
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int i = 0, n = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    // ---------------------------------------------------------------- icons
    private View buildIconsPage() {
        // saved tuning survives restarts
        iconsColor = prefs.getInt("icons_color", iconsColor);
        iconsDesat = prefs.getInt("icons_desat", iconsDesat);
        iconsLighten = prefs.getInt("icons_lighten", iconsLighten);
        iconsStrength = prefs.getInt("icons_strength", iconsStrength);
        iconsPackPkg = prefs.getString("icons_pack", iconsPackPkg);
        iconsPackLabel = prefs.getString("icons_pack_label", iconsPackLabel);
        iconsBgEnabled = prefs.getBoolean("icons_bg", iconsBgEnabled);
        iconsBgColor = prefs.getInt("icons_bg_color", iconsBgColor);
        iconsFgScale = prefs.getInt("icons_fg_scale", iconsFgScale);
        iconsRadius = prefs.getInt("icons_radius", iconsRadius);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = Ui.col(this);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 30));
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -2));

        root.addView(Ui.label(this, "Иконки", 26f, Ui.TEXT));

        // ---- preview card
        LinearLayout previewCard = Ui.col(this);
        previewCard.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        previewCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        previewCard.addView(Ui.label(this, "Предпросмотр", 12.5f, Ui.MUTED));
        iconsPreviewRow = new LinearLayout(this);
        iconsPreviewRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(-1, -2);
        prevLp.topMargin = Ui.dp(this, 10);
        previewCard.addView(iconsPreviewRow, prevLp);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = Ui.dp(this, 16);
        cardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(previewCard, cardLp);

        // ---- icon source: system icons or an imported icon pack
        LinearLayout srcCard = Ui.col(this);
        srcCard.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        srcCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        srcCard.addView(Ui.label(this, "Источник иконок", 12.5f, Ui.MUTED));

        LinearLayout srcRow = new LinearLayout(this);
        srcRow.setOrientation(LinearLayout.HORIZONTAL);
        srcRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srcRowLp = new LinearLayout.LayoutParams(-1, -2);
        srcRowLp.topMargin = Ui.dp(this, 10);
        iconsSourceValue = Ui.text(this, iconsPackPkg.isEmpty() ? "Системные иконки" : iconsPackLabel,
                14f, Ui.TEXT);
        srcRow.addView(iconsSourceValue, new LinearLayout.LayoutParams(0, -2, 1f));
        View pickBtn = pillButton(M3Icons.folder(Ui.ACCENT, Ui.dp(this, 18)), "Пак",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { chooseIconPack(); }
                });
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(-2, -2);
        pickLp.leftMargin = Ui.dp(this, 10);
        srcRow.addView(pickBtn, pickLp);
        srcCard.addView(srcRow);

        LinearLayout.LayoutParams srcCardLp = new LinearLayout.LayoutParams(-1, -2);
        srcCardLp.topMargin = Ui.dp(this, 16);
        srcCardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(srcCard, srcCardLp);

        // ---- color picker
        LinearLayout colorCard = Ui.col(this);
        colorCard.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        colorCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        colorCard.addView(Ui.label(this, "Цвет перекраски", 12.5f, Ui.MUTED));

        // preset swatches
        String[] presets = {"#3D7D56", "#E53935", "#1E88E5", "#FDD835", "#8E24AA",
                "#FB8C00", "#00ACC1", "#F06292", "#5D4037", "#455A64"};
        LinearLayout swatchRow = new LinearLayout(this);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(-1, -2);
        swLp.topMargin = Ui.dp(this, 10);
        colorCard.addView(swatchRow, swLp);
        for (String hex : presets) {
            final int color = parseColor(hex);
            View sw = swatch(color);
            sw.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setIconsColor(color); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
            lp.rightMargin = Ui.dp(this, 8);
            swatchRow.addView(sw, lp);
        }

        // hex input
        LinearLayout hexRow = new LinearLayout(this);
        hexRow.setOrientation(LinearLayout.HORIZONTAL);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hexLp = new LinearLayout.LayoutParams(-1, -2);
        hexLp.topMargin = Ui.dp(this, 12);
        colorCard.addView(hexRow, hexLp);
        hexRow.addView(Ui.label(this, "HEX:", 13f, Ui.MUTED));
        iconsColorInput = new EditText(this);
        iconsColorInput.setText(String.format("#%06X", iconsColor & 0xFFFFFF));
        iconsColorInput.setInputType(InputType.TYPE_CLASS_TEXT);
        iconsColorInput.setTextSize(13f);
        iconsColorInput.setTextColor(Ui.TEXT);
        iconsColorInput.setGravity(Gravity.CENTER);
        iconsColorInput.setBackground(Ui.shape(this, Ui.INPUT_BG, 8f, Ui.BORDER, 1f));
        iconsColorInput.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, -2, 1f);
        inputLp.leftMargin = Ui.dp(this, 10);
        hexRow.addView(iconsColorInput, inputLp);
        iconsColorInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                try {
                    setIconsColor(parseColor(s.toString().trim()));
                } catch (Throwable ignored) {}
            }
        });

        LinearLayout.LayoutParams colorCardLp = new LinearLayout.LayoutParams(-1, -2);
        colorCardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(colorCard, colorCardLp);

        // ---- fine tuning sliders
        LinearLayout tuneCard = Ui.col(this);
        tuneCard.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        tuneCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        tuneCard.addView(Ui.label(this, "Тонкая настройка", 12.5f, Ui.MUTED));
        sbDesat = addSlider(tuneCard, "Обесцвечивание", iconsDesat, new IntSetter() {
            @Override public void set(int v) { iconsDesat = v; }
        });
        sbLighten = addSlider(tuneCard, "Осветление", iconsLighten, new IntSetter() {
            @Override public void set(int v) { iconsLighten = v; }
        });
        sbStrength = addSlider(tuneCard, "Сила перекраски", iconsStrength, new IntSetter() {
            @Override public void set(int v) { iconsStrength = v; }
        });
        View resetBtn = pillButton(M3Icons.tune(Ui.TEXT, Ui.dp(this, 18)), "Сброс настроек",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { resetIconsTuning(); }
                });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(-1, -2);
        resetLp.topMargin = Ui.dp(this, 12);
        tuneCard.addView(resetBtn, resetLp);
        LinearLayout.LayoutParams tuneCardLp = new LinearLayout.LayoutParams(-1, -2);
        tuneCardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(tuneCard, tuneCardLp);

        // ---- IconPack Studio style: background layer + foreground icon
        LinearLayout styleCard = Ui.col(this);
        styleCard.setBackground(Ui.shape(this, Ui.SURFACE, 12f, Ui.BORDER_SOFT, 1f));
        styleCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        styleCard.addView(Ui.label(this, "Стиль: фон + иконка", 12.5f, Ui.MUTED));

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(-1, -2);
        switchLp.topMargin = Ui.dp(this, 8);
        TextView switchLabel = Ui.text(this, "Разделять на слои (как IconPack Studio)", 14f, Ui.TEXT);
        switchRow.addView(switchLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        final Switch sw = new Switch(this);
        sw.setChecked(iconsBgEnabled);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                iconsBgEnabled = checked;
                saveIconsPrefs();
                refreshIconsPreview();
            }
        });
        switchRow.addView(sw);
        styleCard.addView(switchRow, switchLp);

        // background color: presets + hex
        LinearLayout bgRow = new LinearLayout(this);
        bgRow.setOrientation(LinearLayout.HORIZONTAL);
        bgRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bgRowLp = new LinearLayout.LayoutParams(-1, -2);
        bgRowLp.topMargin = Ui.dp(this, 10);
        bgRow.addView(Ui.text(this, "Цвет фона:", 13f, Ui.MUTED));
        String[] bgPresets = {"#FFFFFF", "#F5F5F5", "#212121", "#000000", "#3D7D56", "#1E88E5"};
        for (String hex : bgPresets) {
            final int color = parseColor(hex);
            View s = swatch(color);
            s.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setIconsBgColor(color); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26));
            lp.leftMargin = Ui.dp(this, 6);
            bgRow.addView(s, lp);
        }
        iconsBgInput = new EditText(this);
        iconsBgInput.setText(String.format("#%06X", iconsBgColor & 0xFFFFFF));
        iconsBgInput.setInputType(InputType.TYPE_CLASS_TEXT);
        iconsBgInput.setTextSize(12f);
        iconsBgInput.setTextColor(Ui.TEXT);
        iconsBgInput.setGravity(Gravity.CENTER);
        iconsBgInput.setBackground(Ui.shape(this, Ui.INPUT_BG, 8f, Ui.BORDER, 1f));
        iconsBgInput.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        LinearLayout.LayoutParams bgInputLp = new LinearLayout.LayoutParams(0, -2, 1f);
        bgInputLp.leftMargin = Ui.dp(this, 8);
        bgRow.addView(iconsBgInput, bgInputLp);
        iconsBgInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                try {
                    iconsBgColor = parseColor(s.toString().trim());
                    saveIconsPrefs();
                    refreshIconsPreview();
                } catch (Throwable ignored) {}
            }
        });
        styleCard.addView(bgRow, bgRowLp);

        sbFgScale = addSlider(styleCard, "Размер иконки", 40, 120, iconsFgScale, new IntSetter() {
            @Override public void set(int v) { iconsFgScale = v; }
        });
        sbRadius = addSlider(styleCard, "Скругление фона", 0, 50, iconsRadius, new IntSetter() {
            @Override public void set(int v) { iconsRadius = v; }
        });

        LinearLayout.LayoutParams styleCardLp = new LinearLayout.LayoutParams(-1, -2);
        styleCardLp.bottomMargin = Ui.dp(this, 14);
        root.addView(styleCard, styleCardLp);

        // ---- apply button
        View apply = pillButton(M3Icons.play(Ui.ACCENT, Ui.dp(this, 18)),
                "Перекрасить и применить",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { runIconsApply(); }
                });
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(-1, -2);
        applyLp.bottomMargin = Ui.dp(this, 14);
        root.addView(apply, applyLp);

        // ---- status card
        LinearLayout statusCard = Ui.col(this);
        statusCard.setBackground(Ui.shape(this, Ui.INPUT_BG, 12f, Ui.BORDER, 1.2f));
        statusCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        iconsStatusText = Ui.text(this, "Выберите цвет и нажмите «Перекрасить»", 13f, Ui.MUTED);
        statusCard.addView(iconsStatusText);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.bottomMargin = Ui.dp(this, 14);
        root.addView(statusCard, statusLp);

        TextView note = Ui.text(this,
                "Иконки всех приложений перекрашиваются в выбранный цвет "
                        + "(как в Origin Icons) и применяются через канал Game Cube "
                        + "к /data/bbkcore/theme/icons/. Можно импортировать иконокпак "
                        + "(ADW/Nova, appfilter.xml) и включить режим «фон + иконка» "
                        + "как в IconPack Studio. Выполнится при открытом режиме игры.",
                11.5f, Ui.MUTED);
        root.addView(note);

        // restore a previously imported icon pack in the background
        if (!iconsPackPkg.isEmpty()) {
            final String packPkg = iconsPackPkg;
            new Thread(new Runnable() {
                @Override public void run() {
                    final int n = packLoader().load(packPkg);
                    h.post(new Runnable() {
                        @Override public void run() {
                            if (n > 0) refreshIconsPreview();
                        }
                    });
                }
            }).start();
        }

        return scroll;
    }

    private View swatch(int color) {
        View v = new View(this);
        v.setBackground(Ui.shape(this, color, 17f, 0x33000000, 1f));
        return v;
    }

    private interface IntSetter { void set(int v); }

    /** Slider 0..100 with a percent value label; refreshes preview on release. */
    private SeekBar addSlider(LinearLayout parent, String name, int initial, IntSetter setter) {
        return addSlider(parent, name, 0, 100, initial, setter);
    }

    private SeekBar addSlider(LinearLayout parent, String name, int min, final int max,
                              int initial, final IntSetter setter) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = Ui.text(this, name, 13f, Ui.TEXT);
        final TextView val = Ui.text(this, initial + "%", 13f, Ui.ACCENT);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(val, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout row = Ui.col(this);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
        headLp.topMargin = Ui.dp(this, 12);
        row.addView(head, headLp);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(initial - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = min + p;
                val.setText(v + "%");
                setter.set(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                saveIconsPrefs();
                refreshIconsPreview();
            }
        });
        row.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(row);
        return sb;
    }

    // ------------------------------------------------------------------ icon pack

    private IconPackLoader packLoader() {
        if (packLoader == null) packLoader = new IconPackLoader(this);
        return packLoader;
    }

    private void saveIconsPrefs() {
        prefs.edit()
                .putInt("icons_color", iconsColor)
                .putInt("icons_desat", iconsDesat)
                .putInt("icons_lighten", iconsLighten)
                .putInt("icons_strength", iconsStrength)
                .putString("icons_pack", iconsPackPkg)
                .putString("icons_pack_label", iconsPackLabel)
                .putBoolean("icons_bg", iconsBgEnabled)
                .putInt("icons_bg_color", iconsBgColor)
                .putInt("icons_fg_scale", iconsFgScale)
                .putInt("icons_radius", iconsRadius)
                .apply();
    }

    /** Resets every slider to the default Origin Icons / studio look. */
    private void resetIconsTuning() {
        if (sbDesat != null) sbDesat.setProgress(100);
        if (sbLighten != null) sbLighten.setProgress(0);
        if (sbStrength != null) sbStrength.setProgress(100);
        if (sbFgScale != null) sbFgScale.setProgress(76 - 40);
        if (sbRadius != null) sbRadius.setProgress(25);
        toast("Настройки сброшены");
        saveIconsPrefs();
        refreshIconsPreview();
    }

    private void setIconsBgColor(int color) {
        iconsBgColor = color;
        if (iconsBgInput != null) {
            String hex = String.format("#%06X", color & 0xFFFFFF);
            if (!iconsBgInput.getText().toString().equals(hex)) {
                iconsBgInput.setText(hex);
                iconsBgInput.setSelection(hex.length());
            }
        }
        saveIconsPrefs();
        refreshIconsPreview();
    }

    /** Discovers installed icon packs (ADW/Nova intents) and shows a chooser. */
    private void chooseIconPack() {
        toast("Ищу иконокпаки…");
        new Thread(new Runnable() {
            @Override public void run() {
                final List<IconPackLoader.Pack> packs = packLoader().findPacks();
                h.post(new Runnable() {
                    @Override public void run() {
                        if (packs.isEmpty()) {
                            toast("Иконокпаки не найдены — установите любой пак с Play Market");
                            return;
                        }
                        CharSequence[] items = new CharSequence[packs.size() + 1];
                        items[0] = "Системные иконки (без пака)";
                        for (int i = 0; i < packs.size(); i++) items[i + 1] = packs.get(i).label;
                        new AlertDialog.Builder(Main.this)
                                .setTitle("Иконокпак")
                                .setItems(items, new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        if (w == 0) {
                                            setIconPack("", "");
                                        } else {
                                            IconPackLoader.Pack p = packs.get(w - 1);
                                            setIconPack(p.pkg, p.label);
                                        }
                                    }
                                })
                                .setNegativeButton("Отмена", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private void setIconPack(final String pkg, final String label) {
        iconsPackPkg = pkg;
        iconsPackLabel = label;
        saveIconsPrefs();
        if (iconsSourceValue != null) {
            iconsSourceValue.setText(pkg.isEmpty() ? "Системные иконки" : label);
        }
        if (pkg.isEmpty()) {
            iconsStatus("Источник: системные иконки", Ui.ACCENT);
            refreshIconsPreview();
            return;
        }
        iconsStatus("Загружаю «" + label + "»…", Ui.ACCENT);
        new Thread(new Runnable() {
            @Override public void run() {
                final int n = packLoader().load(pkg);
                h.post(new Runnable() {
                    @Override public void run() {
                        if (n > 0) {
                            iconsStatus("Пак загружен: " + n + " иконок в маппинге", Ui.ACCENT);
                        } else {
                            iconsStatus("Пак не поддерживается (нет appfilter.xml)", Ui.ERROR);
                        }
                        refreshIconsPreview();
                    }
                });
            }
        }).start();
    }

    /** Pack icon for a package when a pack is loaded, else null (use app icon). */
    private Drawable packIconOrNull(String pkg) {
        if (iconsPackPkg.isEmpty()) return null;
        try {
            return packLoader().iconFor(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int parseColor(String s) {
        if (s == null) throw new IllegalArgumentException("null");
        String t = s.trim();
        if (!t.startsWith("#")) t = "#" + t;
        return Color.parseColor(t);
    }

    private void setIconsColor(int color) {
        iconsColor = color;
        if (iconsColorInput != null) {
            String hex = String.format("#%06X", color & 0xFFFFFF);
            if (!iconsColorInput.getText().toString().equals(hex)) {
                iconsColorInput.setText(hex);
                iconsColorInput.setSelection(hex.length());
            }
        }
        refreshIconsPreview();
    }

    /** Loads launcher apps once and renders the preview row with the current color. */
    private void refreshIconsPreview() {
        if (iconsPreviewRow == null) return;
        if (iconsPackages.isEmpty()) {
            loadLauncherApps();
        }
        iconsPreviewRow.removeAllViews();
        iconsTinted.clear();
        int n = Math.min(iconsPackages.size(), 8);
        for (int i = 0; i < n; i++) {
            Drawable orig = iconsOriginals.get(i);
            Drawable src = packIconOrNull(iconsPackages.get(i));
            if (src == null) src = orig;
            Bitmap pb = IconsTinter.compose(src, iconsColor, iconsDesat, iconsLighten, iconsStrength,
                    iconsBgEnabled, iconsBgColor, iconsFgScale, iconsRadius);
            Drawable tinted = (pb != null) ? new BitmapDrawable(getResources(), pb) : orig;
            iconsTinted.add(tinted);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(tinted);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f);
            iconsPreviewRow.addView(iv, lp);
        }
    }

    private void loadLauncherApps() {
        iconsPackages.clear();
        iconsOriginals.clear();
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> list =
                    getPackageManager().queryIntentActivities(intent, 0);
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (android.content.pm.ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(PKG)) continue;
                if (!seen.add(pkg)) continue; // one icon per package
                iconsPackages.add(pkg);
                try {
                    iconsOriginals.add(ri.activityInfo.loadIcon(getPackageManager()));
                } catch (Throwable t) {
                    iconsOriginals.add(null);
                }
            }
        } catch (Throwable t) {
            Log.e("GCTune", "loadLauncherApps failed: " + t);
        }
    }

    private void runIconsApply() {
        if (busy) {
            toast("Дождитесь текущей команды");
            return;
        }
        if (iconsPackages.isEmpty()) {
            loadLauncherApps();
        }
        if (iconsPackages.isEmpty()) {
            iconsStatus("Не удалось получить список приложений", Ui.ERROR);
            return;
        }
        iconsRun = true;
        busy = true;
        // persist the whole recipe
        saveIconsPrefs();
        iconsStatus("Перекрашиваю иконки…", Ui.ACCENT);
        showHint();
        final int total = iconsPackages.size();
        new Thread(new Runnable() {
            @Override public void run() {
                final File dir = new File(getExternalFilesDir(null), "icons");
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                int done = 0;
                int written = 0;
                int failed = 0;
                for (int i = 0; i < iconsPackages.size(); i++) {
                    final String pkg = iconsPackages.get(i);
                    Bitmap bmp = null;
                    try {
                        Drawable orig = iconsOriginals.get(i);
                        Drawable src = packIconOrNull(pkg);
                        if (src == null) src = orig;
                        if (src != null) {
                            bmp = IconsTinter.compose(src, iconsColor, iconsDesat, iconsLighten,
                                    iconsStrength, iconsBgEnabled, iconsBgColor,
                                    iconsFgScale, iconsRadius);
                        }
                    } catch (Throwable t) {
                        Log.e("GCTune", "render " + pkg + " failed: " + t);
                    }
                    if (bmp != null) {
                        try {
                            FileOutputStream os = new FileOutputStream(new File(dir, pkg + ".png"));
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                            os.close();
                            written++;
                        } catch (Throwable t) {
                            Log.e("GCTune", "write " + pkg + " failed: " + t);
                            failed++;
                        }
                        bmp.recycle();
                    } else {
                        failed++;
                    }
                    done++;
                    if (done % 8 == 0 || done == total) {
                        final int d = done;
                        h.post(new Runnable() {
                            @Override public void run() {
                                iconsStatus("Перекрашено " + d + " / " + total, Ui.ACCENT);
                            }
                        });
                    }
                }
                // Origin Icons writes the dialer under its component name too
                // (com.android.dialer.TwelveKeyDialer) — the launcher looks that
                // file up separately; mirror the fallback from IconPackEngine.
                try {
                    File dialer = new File(dir, "com.android.dialer.TwelveKeyDialer.png");
                    File contacts = new File(dir, "com.android.contacts.png");
                    if (!dialer.exists() && contacts.exists()) {
                        java.io.FileInputStream in = new java.io.FileInputStream(contacts);
                        java.io.FileOutputStream os = new java.io.FileOutputStream(dialer);
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                        in.close();
                        os.close();
                    }
                } catch (Throwable ignored) {
                }
                // force the launcher to re-read theme icons; done here, in the
                // app process — "settings put" in the channel NPEs in AppOps
                pendingOldStyle = readIconsStyle();
                styleToggleOk = toggleIconsStyle();
                final int fin = written;
                final int ferr = failed;
                h.post(new Runnable() {
                    @Override public void run() {
                        iconsStatus("Отрисовано " + fin + " из " + total
                                + (ferr > 0 ? " (ошибок: " + ferr + ")" : "")
                                + " — ставлю через канал…", Ui.ACCENT);
                        busy = false;
                        if (!apply(iconsCommand())) {
                            iconsRun = false;
                            hideHint();
                            iconsStatus("GC Tune не в списке игр — добавьте приложение и повторите", Ui.ERROR);
                        }
                    }
                });
            }
        }).start();
    }

    /** Reads the launcher icon-style setting (saved across recolors). */
    private String readIconsStyle() {
        try {
            String v = Settings.Global.getString(getContentResolver(), "theme_icons_style_explore");
            return (v == null || v.isEmpty()) ? "1" : v;
        } catch (Throwable t) {
            return "1";
        }
    }

    /**
     * Flips the launcher icon style 1 -> 2 -> 1 from the app process to force
     * the launcher to re-read /data/bbkcore/theme/icons/. "settings put" run
     * inside the uid1000 channel NPEs in AppOpsService (no calling package),
     * so this needs the WRITE_SECURE_SETTINGS grant (adb pm grant). Returns
     * false when the permission is missing.
     */
    private boolean toggleIconsStyle() {
        try {
            Settings.Global.putInt(getContentResolver(), "theme_icons_style_explore", 1);
            Settings.Global.putInt(getContentResolver(), "theme_icons_style_explore", 2);
            Settings.Global.putInt(getContentResolver(), "theme_icons_style_explore", 1);
            return true;
        } catch (Throwable t) {
            Log.e("GCTune", "toggleIconsStyle failed: " + t);
            return false;
        }
    }

    /** Puts the user's own icon style back after the recolor. */
    private void restoreIconsStyle(String old) {
        try {
            Settings.Global.putInt(getContentResolver(), "theme_icons_style_explore",
                    Integer.parseInt(old.trim()));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Icon recolor command: the uid 1000 channel copies the rendered PNGs into
     * /data/bbkcore/theme/icons/ and restarts the launcher once. The
     * theme_icons_style_explore flip is done by the app itself (see
     * toggleIconsStyle) because "settings put" in the channel NPEs in
     * AppOpsService (no calling package). A lock file aborts duplicate runs
     * (a second copy raced the first one and deleted icons after they were
     * copied — that is why many apps stayed uncolored). chown is omitted on
     * purpose: uid 1000 has no CAP_CHOWN and the launcher reads the files
     * anyway; all diagnostic noise is suppressed so the output stays parseable.
     */
    private String iconsCommand() {
        final StringBuilder sb = new StringBuilder();
        sb.append("COLOR=").append(String.format("#%06X", iconsColor & 0xFFFFFF)).append(";");
        sb.append("SRC=").append(getExternalFilesDir(null).getAbsolutePath()).append("/icons;");
        sb.append("TD=/data/bbkcore/theme;");
        sb.append("LOCK=$TD/.icons_gctune_lock;");
        // Stale locks must die: the lock is a directory and the old "find
        // -mmin -delete" probe is not supported by every toybox build, which
        // left "предыдущая установка ещё идёт" hanging forever. Age is checked
        // with stat/date (both always available), stale (>3 min) is removed.
        sb.append("if ! mkdir $LOCK 2>/dev/null; then ");
        sb.append("ts=$(stat -c %Y $LOCK 2>/dev/null); now=$(date +%s); ");
        sb.append("if [ -n \"$ts\" ] && [ $((now-ts)) -gt 180 ]; then ");
        sb.append("rm -rf $LOCK 2>/dev/null; mkdir $LOCK 2>/dev/null || { echo BUSY; exit 0; }; ");
        sb.append("else echo BUSY; exit 0; fi; fi;");
        sb.append("trap 'rmdir $LOCK 2>/dev/null' EXIT;");
        // icons are rendered by the app into $SRC before this command runs.
        sb.append("echo SRC_ICONS=$(ls \"$SRC\" 2>/dev/null | wc -l);");
        // 1) stage the new icons first so nothing is half-swapped if we bail out
        sb.append("NEW=$TD/.icons_gctune_new;");
        sb.append("rm -rf $NEW 2>/dev/null; mkdir -p $NEW;");
        sb.append("for f in \"$SRC\"/*.png; do [ -f \"$f\" ] && cp \"$f\" $NEW/ 2>/dev/null; done;");
        sb.append("[ -n \"$(ls -A $NEW)\" ] || { echo NO_ICONS; rm -rf $NEW; exit 0; };");
        // 2) ensure the launcher template exists (icon_bg / icon_mask / icon_border)
        sb.append("if [ ! -d $TD/launcher ]; then ");
        sb.append("mkdir -p $TD;");
        // NB: archive members are stored as "./launcher/..." — extracting the bare
        // name "launcher" silently matches nothing (tar exits 0, extracts 0 files)
        sb.append("tar -xzf /sdcard/Download/gctune/origin_theme_template.tgz -C $TD ./launcher 2>/dev/null;");
        sb.append("restorecon -RF $TD 2>/dev/null;");
        sb.append("fi;");
        // 3) The launcher keys its icon cache on theme_icons_style_explore —
        // without a flip it keeps serving the OLD bitmaps for already-cached
        // apps and only "new" icons get recolored (that is why recoloring was
        // partial). Origin Icons purges the cache the same way: empty the icon
        // dir, flip the style around restarts, THEN install the icons and
        // restart once more. The user's own style value is restored at the end
        // (one more observer reload, now with the new icons in place).
        sb.append("OLD=$(settings get system theme_icons_style_explore 2>/dev/null);");
        sb.append("rm -rf $TD/icons 2>/dev/null;");
        sb.append("settings put system theme_icons_style_explore 1 >/dev/null 2>&1;");
        sb.append("settings put global theme_icons_style_explore 1 >/dev/null 2>&1;");
        sb.append("am force-stop com.bbk.launcher2;");
        sb.append("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; sleep 1;");
        sb.append("echo FLIP=$(settings get system theme_icons_style_explore 2>/dev/null);");
        sb.append("settings put system theme_icons_style_explore 2 >/dev/null 2>&1;");
        sb.append("settings put global theme_icons_style_explore 2 >/dev/null 2>&1;");
        sb.append("am force-stop com.bbk.launcher2;");
        sb.append("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; sleep 1;");
        sb.append("settings put system theme_icons_style_explore 1 >/dev/null 2>&1;");
        sb.append("settings put global theme_icons_style_explore 1 >/dev/null 2>&1;");
        sb.append("am force-stop com.bbk.launcher2;");
        sb.append("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; sleep 1;");
        // 4) install the new icons while the launcher just reloaded with an
        // empty theme dir, then bring it up for real
        sb.append("mkdir -p $TD/icons 2>/dev/null;");
        sb.append("for f in $NEW/*.png; do [ -f \"$f\" ] && cp \"$f\" $TD/icons/ 2>/dev/null; done;");
        sb.append("rm -rf $NEW;");
        sb.append("chmod -R 777 $TD/icons 2>/dev/null;");
        sb.append("restorecon -RF $TD/icons 2>/dev/null;");
        sb.append("N=$(ls $TD/icons 2>/dev/null | wc -l);");
        sb.append("if [ -n \"$OLD\" ] && [ \"$OLD\" != null ]; then ");
        sb.append("settings put system theme_icons_style_explore \"$OLD\" >/dev/null 2>&1;");
        sb.append("settings put global theme_icons_style_explore \"$OLD\" >/dev/null 2>&1; fi;");
        sb.append("am force-stop com.bbk.launcher2;");
        sb.append("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1;");
        sb.append("sleep 2;");
        sb.append("echo APPLIED_ICONS=$N;");
        sb.append("echo APPLIED;");
        return sb.toString();
    }

    private void iconsStatus(String s, int color) {
        if (iconsStatusText == null) return;
        iconsStatusText.setText(s);
        iconsStatusText.setTextColor(color);
    }

    /** Turns the uid 1000 output of the icons command into a human status line. */
    private void iconsResult(boolean ok, Entry e) {
        // the launcher restarts finished — give the user's own style back
        if (pendingOldStyle != null) {
            restoreIconsStyle(pendingOldStyle);
            pendingOldStyle = null;
        }
        iconsRun = false;
        hideHint();
        String out = (ok && e != null && e.out != null) ? e.out : "";
        if (!ok) {
            iconsStatus("Нет ответа от uid 1000 — откройте режим игры и повторите", Ui.ERROR);
        } else if (out.contains("NO_ICONS")) {
            iconsStatus("Нет перекрашенных иконок — повторите", Ui.ERROR);
        } else if (out.contains("BUSY")) {
            iconsStatus("Предыдущая установка ещё идёт — подождите и повторите", Ui.ERROR);
        } else if (out.contains("APPLIED")) {
            String n = "";
            int i = out.indexOf("APPLIED_ICONS=");
            if (i >= 0) {
                StringBuilder sb = new StringBuilder();
                for (int j = i + "APPLIED_ICONS=".length(); j < out.length(); j++) {
                    char c = out.charAt(j);
                    if (c < '0' || c > '9') break;
                    sb.append(c);
                }
                if (sb.length() > 0) n = " (" + sb + " иконок)";
            }
            iconsStatus("Иконки применены" + n + " — проверьте лаунчер"
                    + (styleToggleOk ? "" : " (стиль не переключился: нет WRITE_SECURE_SETTINGS)"),
                    Ui.ACCENT);
        } else {
            iconsStatus("Подробности в консоли (вкладка Shell)", Ui.TEXT);
        }
    }

    // --------------------------------------------------------------- settings
    private View buildSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = Ui.col(this);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 30));
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -2));

        TextView title = Ui.label(this, "Настройки", 26f, Ui.TEXT);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.bottomMargin = Ui.dp(this, 10);
        root.addView(title, titleLp);

        root.addView(settingsSwitchRow());
        root.addView(settingsTimeoutRow());
        root.addView(settingsNavRow("Поддерживаемые игры", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(SetupActivity.gameSettingsIntent());
                } catch (Throwable t) {
                    try { startActivity(SetupActivity.gameMainIntent()); } catch (Throwable ignored) {}
                }
            }
        }));
        root.addView(settingsNavRow("Первичная настройка", new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putBoolean("setup_done", false).apply();
                startActivity(new Intent(Main.this, SetupActivity.class));
            }
        }));

        TextView about = Ui.text(this, "GC Tune 1.0 · канал Game Cube", 11f, Ui.MUTED);
        about.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams aboutLp = new LinearLayout.LayoutParams(-2, -2);
        aboutLp.gravity = Gravity.CENTER_HORIZONTAL;
        aboutLp.topMargin = Ui.dp(this, 24);
        root.addView(about, aboutLp);
        return scroll;
    }

    private View settingsSwitchRow() {
        LinearLayout row = settingsRowBase();
        row.addView(Ui.text(this, "Требовать статус игры", 14f, Ui.TEXT),
                new LinearLayout.LayoutParams(0, -2, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean("require_game", true));
        sw.setThumbTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, new int[]{}},
                new int[]{Ui.ACCENT, Ui.MUTED}));
        sw.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, new int[]{}},
                new int[]{Ui.PRIMARY, 0x553D7D56}));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton btn, boolean checked) {
                prefs.edit().putBoolean("require_game", checked).apply();
            }
        });
        row.addView(sw);
        return row;
    }

    private View settingsTimeoutRow() {
        LinearLayout row = settingsRowBase();
        row.addView(Ui.text(this, "Таймаут, сек", 14f, Ui.TEXT),
                new LinearLayout.LayoutParams(0, -2, 1f));

        settingsTimeout = new EditText(this);
        settingsTimeout.setText(String.valueOf(prefs.getInt("timeout_sec", 60)));
        settingsTimeout.setInputType(InputType.TYPE_CLASS_NUMBER);
        settingsTimeout.setTextSize(13f);
        settingsTimeout.setTextColor(Ui.TEXT);
        settingsTimeout.setGravity(Gravity.CENTER);
        settingsTimeout.setBackground(Ui.shape(this, Ui.INPUT_BG, 8f, Ui.BORDER, 1f));
        settingsTimeout.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5));
        settingsTimeout.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { storeSettingsTimeout(); }
        });
        row.addView(settingsTimeout, new LinearLayout.LayoutParams(Ui.dp(this, 64), -2));
        return row;
    }

    private View settingsNavRow(String label, View.OnClickListener onClick) {
        LinearLayout row = settingsRowBase();
        row.addView(Ui.text(this, label, 14f, Ui.TEXT),
                new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(M3Icons.chevron(Ui.MUTED, Ui.dp(this, 18)));
        chevron.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(chevron, new LinearLayout.LayoutParams(-2, -2));

        row.setOnClickListener(onClick);
        row.setClickable(true);
        return row;
    }

    private LinearLayout settingsRowBase() {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 15), 0, Ui.dp(this, 15));
        row.setMinimumHeight(Ui.dp(this, 30));
        return row;
    }

    private void storeSettingsTimeout() {
        if (settingsTimeout == null) return;
        int v;
        try {
            v = Integer.parseInt(settingsTimeout.getText().toString().trim());
        } catch (Exception e) {
            return;
        }
        if (v < 3) v = 3;
        if (v > 90) v = 90;
        prefs.edit().putInt("timeout_sec", v).apply();
    }

    // --------------------------------------------------------------- console
    private void appendStyled(SpannableStringBuilder sb, String text, int color, float sizeSp) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan(Ui.sp(this, sizeSp)), start, sb.length(),
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void renderLog() {
        if (logView == null) return;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (Entry e : entries) {
            if (e.status) {
                appendStyled(sb, e.out + "\n", Ui.MUTED, 12f);
            } else {
                String cmd = e.cmd != null && e.cmd.length() > 96
                        ? e.cmd.substring(0, 96) + "…" : String.valueOf(e.cmd);
                appendStyled(sb, "$ ", Ui.ACCENT, 12f);
                appendStyled(sb, cmd + "\n", Ui.TEXT, 12f);
                if (e.pending) {
                    appendStyled(sb, "выполняется…\n", Ui.MUTED, 11f);
                } else if (e.failed) {
                    appendStyled(sb, "нет ответа от uid 1000\n", Ui.MUTED, 11f);
                } else {
                    appendStyled(sb, e.out + "\n", Ui.TEXT, 12f);
                    if (e.rc != RC_UNKNOWN) {
                        appendStyled(sb, "rc " + e.rc + "\n", Ui.MUTED, 10f);
                    }
                }
            }
            sb.append('\n');
        }
        logView.setText(sb, TextView.BufferType.SPANNABLE);
        logScroll.post(new Runnable() {
            @Override public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private void logStatus(String s) {
        Entry e = new Entry();
        e.status = true;
        e.out = s;
        addEntry(e);
    }

    private Entry addEntry(Entry e) {
        entries.add(e);
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
        renderLog();
        return e;
    }

    private Entry lastCommandEntry() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (!entries.get(i).status) return entries.get(i);
        }
        return null;
    }

    // -------------------------------------------------------------- decoding
    private String decodeOutput(String base64Output) {
        if (base64Output == null || base64Output.trim().isEmpty()) {
            return "";
        }
        try {
            byte[] data = Base64.decode(base64Output, Base64.DEFAULT);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Ошибка декодирования: " + e.getMessage();
        }
    }

    /** Splits "stdout/stderr + __RC__<n>" into its parts. */
    private static void splitResult(String raw, Entry out) {
        if (raw == null) raw = "";
        Matcher m = RC_PAT.matcher(raw);
        if (m.find()) {
            try {
                out.rc = Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
                out.rc = RC_UNKNOWN;
            }
            raw = raw.substring(0, m.start());
        }
        raw = stripTrailing(raw);
        out.out = raw.isEmpty() ? NO_OUTPUT : raw;
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    // ------------------------------------------------------------- file import
    private void pickScriptFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        String[] mimeTypes = {"text/plain", "application/x-sh", "text/x-shellscript", "text/*"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Файл со скриптом (.sh / .txt)"),
                    REQ_IMPORT_FILE);
        } catch (Throwable t) {
            toast("Не удалось открыть проводник");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT_FILE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            importFromFile(data.getData());
        }
        if (requestCode == REQ_PICK_ITZ && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            stageThemeFile(data.getData());
        }
    }

    private void importFromFile(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // double quotes would break the shell channel: normalise them
                if (line.indexOf('"') >= 0) line = line.replace('"', '\'');
                if (sb.length() > 0 && !sb.toString().endsWith(";")) sb.append(";\n");
                sb.append(line);
                lines++;
            }
            if (sb.length() > 0 && !sb.toString().endsWith(";")) sb.append(";");
            cmdInput.setText(sb.toString());
            cmdInput.setSelection(cmdInput.getText().length());
            toast("Импортировано строк: " + lines);
        } catch (Exception e) {
            logStatus("Ошибка импорта: " + e.getMessage());
        }
    }

    private void pasteFromClipboard() {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()
                && clipboard.getPrimaryClip().getItemCount() > 0) {
            CharSequence pasteData = clipboard.getPrimaryClip().getItemAt(0).getText();
            if (pasteData != null) {
                cmdInput.setText(pasteData);
                cmdInput.setSelection(cmdInput.getText().length());
                toast("Вставлено из буфера");
            }
        }
    }

    // -------------------------------------------------------------- execution
    private void executeCurrentCommand() {
        String raw = cmdInput.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("Введите команду");
            return;
        }

        // normalise a multi-line script into a single shell pipeline
        String normalized = raw.replace("\r\n", ";").replace('\n', ';').replace('\r', ';');
        normalized = normalized.replaceAll(";\\s*;", ";");

        if (normalized.indexOf('"') >= 0) {
            normalized = normalized.replace('"', '\'');
            cmdInput.setText(normalized);
        }
        apply(normalized);
    }

    /** Sends the command through the layer-name channel. Returns false if it was not sent. */
    private boolean apply(String cmd) {
        if (busy) return false;

        boolean game = isGame();
        boolean req = prefs.getBoolean("require_game", true);
        if (req && !game) {
            logStatus("GC Tune не в списке игр Game Cube");
            toast("Добавьте GC Tune: Ультраигровой режим → Поддерживаемые игры → Другое");
            refreshStatus();
            return false;
        }

        timeoutSec = prefs.getInt("timeout_sec", 60);
        if (timeoutSec < 3) timeoutSec = 3;
        if (timeoutSec > 90) timeoutSec = 90;

        curAck = ++ackCounter;

        String c = cmd.trim();
        if (!c.endsWith(";") && !c.endsWith("&")) c = c + ";";

        // Body: output travels through the broadcast extras; the settings
        // value is kept as a fallback channel. The user command itself never
        // contains double quotes (executeCurrentCommand strips them), and the
        // generated parts may quote freely - $( ... ) is parsed as its own
        // context inside the double-quoted layer argument.
        String body = "b=$({ " + c
                + " echo " + RC_MARK + "$?; } 2>&1 | base64 -w 0 | head -c 8000);"
                + "b=${b:-Cg==};"
                + "se=$( { settings put system " + ACK_KEY + " " + curAck + "\\|$b 2>&1; echo RC$?; } | base64 -w 0);"
                + "se=${se:-Cg==};"
                + "am broadcast -a com.poc.gctune.ACK --ei n " + curAck
                + " --es o $b --es e $se >/dev/null 2>&1";
        boolean daemon = prefs.getBoolean("daemon_on", true);
        String payload = "x$(" + (daemon ? DAEMON_BOOT : "") + body + ")";

        setWinTitle(payload);

        Entry e = new Entry();
        e.cmd = c;
        e.pending = true;
        addEntry(e);

        busy = true;
        deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        // service owns the layer, the ACK receiver and the wake retries
        ChannelService.start(this, curAck, PKG + "/" + payload, deadline, daemon);
        refreshStatus();
        return true;
    }

    private void cancelRun() {
        ChannelService.stop(this);
        Entry e = lastCommandEntry();
        if (e != null && e.pending) {
            e.pending = false;
            e.failed = true;
            e.out = null;
        }
        busy = false;
        if (themeRun) {
            themeRun = false;
            hideHint();
            themeStatus("Отменено", Ui.MUTED);
        }
        setWinTitle(BENIGN_TITLE);
        renderLog();
        refreshStatus();
    }

    private void finishRun(boolean ok, String blob) {
        busy = false;
        setWinTitle(BENIGN_TITLE);

        Entry e = lastCommandEntry();
        if (e != null) {
            e.pending = false;
            if (ok) {
                e.failed = false;
                splitResult(decodeOutput(blob), e);
            } else {
                e.failed = true;
                e.out = null;
                e.rc = RC_UNKNOWN;
            }
        }
        renderLog();
        if (themeRun) themeResult(ok, e);
        if (iconsRun) iconsResult(ok, e);
        if (!ok) toast("Нет ответа — откройте шторку Game Cube");
        refreshStatus();
    }

    private void setWinTitle(String t) {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.setTitle(t);
            getWindow().setAttributes(lp);
        } catch (Throwable e) {
            Log.e("GCTune", "title fail: " + e);
        }
    }

    // ------------------------------------------------------------- state
    private boolean isGame() {
        try {
            return PKG.equals(Settings.System.getString(cr, "current_game_package"));
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshStatus() {
        boolean game = isGame();
        if (statusDot == null || statusText == null || runBtn == null) return;

        if (busy) {
            statusDot.setImageDrawable(iconDotOn);
            statusText.setText("выполняется…");
            statusText.setTextColor(Ui.ACCENT);
            runBtn.setImageDrawable(iconStop);
            runBtn.setAlpha(1f);
            return;
        }

        runBtn.setImageDrawable(iconPlay);
        boolean req = prefs.getBoolean("require_game", true);
        boolean enabled = !req || game;
        runBtn.setEnabled(enabled);
        runBtn.setAlpha(enabled ? 1f : 0.35f);

        if (game) {
            statusDot.setImageDrawable(iconDotOn);
            statusText.setText("в списке игр");
            statusText.setTextColor(Ui.ACCENT);
        } else {
            statusDot.setImageDrawable(iconDotOff);
            statusText.setText("не в списке игр");
            statusText.setTextColor(Ui.MUTED);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
