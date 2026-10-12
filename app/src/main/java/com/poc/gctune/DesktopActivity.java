package com.poc.gctune;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Interactive Desktop Layout & Tile Sizing (1x1, 2x1, 1x2, 2x2) Editor for OriginOS Launcher.
 * Allows arranging desktop items, stretching icons to atomic 2x1, 1x2, 2x2 cards, and
 * directly pushing changes to /data/user/0/com.bbk.launcher2/databases/launcher.db.
 */
public class DesktopActivity extends Main {

    @Override
    protected int sectionPage() {
        return PAGE_DESKTOP;
    }

    public static final class DesktopItem {
        public String label;
        public String packageName;
        public Drawable icon;
        public int spanX = 1;
        public int spanY = 1;
        public int cellX;
        public int cellY;

        public DesktopItem(String label, String packageName, Drawable icon, int spanX, int spanY, int cellX, int cellY) {
            this.label = label;
            this.packageName = packageName;
            this.icon = icon;
            this.spanX = spanX;
            this.spanY = spanY;
            this.cellX = cellX;
            this.cellY = cellY;
        }
    }

    private final List<DesktopItem> desktopApps = new ArrayList<>();
    private final List<ResolveInfo> installedApps = new ArrayList<>();
    private GridLayout gridLayout;
    private TextView statusTextView;
    private View hintCardView;
    private DesktopItem selectedItem = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public View buildDesktopPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = Ui.col(this);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 30));
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -2));

        TextView title = Ui.label(this, "Рабочий стол", 26f, Ui.TEXT);
        root.addView(pageTitle(title, "Растягивание иконок (1×1, 2×1, 1×2, 2×2)"));

        hintCardView = hintCard();
        hintCardView.setVisibility(View.GONE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.bottomMargin = Ui.dp(this, 14);
        root.addView(hintCardView, hintLp);

        // ---- Desktop Grid Container Card (Mockup styled) ----
        LinearLayout mockCard = Ui.col(this);
        mockCard.setBackground(Ui.shape(this, Ui.SURFACE, 20f, Ui.BORDER_SOFT, 1.2f));
        mockCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));

        LinearLayout mockHead = Ui.row(this);
        mockHead.addView(Ui.section(this, "Сетка рабочего стола (4 колонки)"), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView addBtn = Ui.label(this, "+ Добавить иконку", 12f, Ui.ACCENT);
        addBtn.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        addBtn.setBackground(Ui.pillShape(this, Ui.PRIMARY, Ui.BORDER, 1f));
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.tap(v);
                showAddAppDialog();
            }
        });
        mockHead.addView(addBtn);
        mockCard.addView(mockHead);

        TextView subHint = Ui.text(this, "Нажмите на иконку для изменения размера или выбора действия", 11.5f, Ui.MUTED);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = Ui.dp(this, 4);
        subLp.bottomMargin = Ui.dp(this, 12);
        mockCard.addView(subHint, subLp);

        // The actual 4-column GridLayout for Desktop Items
        gridLayout = new GridLayout(this);
        gridLayout.setColumnCount(4);
        gridLayout.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        mockCard.addView(gridLayout, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams mockLp = new LinearLayout.LayoutParams(-1, -2);
        mockLp.bottomMargin = Ui.dp(this, 14);
        root.addView(mockCard, mockLp);

        // ---- Apply Button ----
        View applyBtn = pillButton(M3Icons.play(Ui.ACCENT, Ui.dp(this, 18)),
                "Применить на рабочий стол",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        applyDesktopLayout();
                    }
                });
        LinearLayout.LayoutParams appLp = new LinearLayout.LayoutParams(-1, -2);
        appLp.bottomMargin = Ui.dp(this, 14);
        root.addView(applyBtn, appLp);

        // ---- Status Card ----
        LinearLayout statusCard = Ui.col(this);
        statusCard.setBackground(Ui.shape(this, Ui.INPUT_BG, 12f, Ui.BORDER, 1.2f));
        statusCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        statusTextView = Ui.text(this, "Настройте плитки и нажмите «Применить»", 13f, Ui.MUTED);
        statusCard.addView(statusTextView);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, -2);
        stLp.bottomMargin = Ui.dp(this, 14);
        root.addView(statusCard, stLp);

        TextView note = Ui.text(this,
                "Обновляет базу launcher.db (spanX/spanY) и перезапускает лончер OriginOS.",
                11.5f, Ui.MUTED);
        root.addView(note);

        // Load installed apps & populate default desktop items
        loadInitialApps();

        return scroll;
    }

    private void loadInitialApps() {
        desktopApps.clear();
        new Thread(new Runnable() {
            @Override public void run() {
                PackageManager pm = getPackageManager();
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);

                Collections.sort(list, new Comparator<ResolveInfo>() {
                    @Override public int compare(ResolveInfo a, ResolveInfo b) {
                        return String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm)));
                    }
                });

                HashSet<String> seen = new HashSet<>();
                final List<DesktopItem> defaults = new ArrayList<>();
                for (ResolveInfo ri : list) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(PKG)) continue;
                    if (!seen.add(pkg)) continue;
                    installedApps.add(ri);

                    if (defaults.size() < 8) {
                        Drawable d = null;
                        try { d = ri.activityInfo.loadIcon(pm); } catch (Throwable ignored) {}
                        int spanX = (defaults.size() == 0 || defaults.size() == 4) ? 2 : 1;
                        int spanY = (defaults.size() == 0) ? 2 : 1;
                        defaults.add(new DesktopItem(ri.loadLabel(pm).toString(), pkg, d, spanX, spanY, 0, 0));
                    }
                }

                h.post(new Runnable() {
                    @Override public void run() {
                        desktopApps.addAll(defaults);
                        renderDesktopGrid();
                    }
                });
            }
        }).start();
    }

    private void renderDesktopGrid() {
        if (gridLayout == null) return;
        gridLayout.removeAllViews();

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int totalPad = Ui.dp(this, 64);
        int cellWidth = Math.max(Ui.dp(this, 62), (screenWidth - totalPad) / 4);
        int cellHeight = Ui.dp(this, 72);

        for (int i = 0; i < desktopApps.size(); i++) {
            final DesktopItem item = desktopApps.get(i);
            final int index = i;

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, item.spanX, 1f);
            lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, item.spanY);
            lp.width = (item.spanX == 2) ? cellWidth * 2 : cellWidth;
            lp.height = (item.spanY == 2) ? cellHeight * 2 : cellHeight;
            lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));

            FrameLayout itemCard = new FrameLayout(this);
            itemCard.setBackground(Ui.shape(this, Ui.SURFACE_HIGH, 16f,
                    (selectedItem == item) ? Ui.ACCENT : Ui.BORDER_SOFT,
                    (selectedItem == item) ? 2f : 1f));

            if (item.spanX == 1 && item.spanY == 1) {
                // 1x1 Classic
                LinearLayout col = Ui.col(this);
                col.setGravity(Gravity.CENTER);
                col.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));

                ImageView iv = new ImageView(this);
                if (item.icon != null) iv.setImageDrawable(item.icon);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                col.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));

                TextView tv = Ui.text(this, item.label, 10.5f, Ui.TEXT);
                tv.setSingleLine(true);
                tv.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
                tLp.topMargin = Ui.dp(this, 3);
                col.addView(tv, tLp);

                itemCard.addView(col, new FrameLayout.LayoutParams(-1, -1));
            } else if (item.spanX == 2 && item.spanY == 1) {
                // 2x1 Wide Banner
                LinearLayout row = Ui.row(this);
                row.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));

                ImageView iv = new ImageView(this);
                if (item.icon != null) iv.setImageDrawable(item.icon);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));

                LinearLayout info = Ui.col(this);
                info.setPadding(Ui.dp(this, 8), 0, 0, 0);
                TextView title = Ui.label(this, item.label, 13f, Ui.TEXT);
                title.setSingleLine(true);
                info.addView(title);
                TextView sub = Ui.text(this, "2×1 Плитка", 10.5f, Ui.MUTED);
                info.addView(sub);
                row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

                TextView badge = Ui.badge(this, "2×1", Ui.PRIMARY, Ui.ACCENT);
                row.addView(badge);

                itemCard.addView(row, new FrameLayout.LayoutParams(-1, -1));
            } else if (item.spanX == 1 && item.spanY == 2) {
                // 1x2 Tall Tile
                LinearLayout col = Ui.col(this);
                col.setGravity(Gravity.CENTER);
                col.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));

                ImageView iv = new ImageView(this);
                if (item.icon != null) iv.setImageDrawable(item.icon);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                col.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));

                TextView title = Ui.label(this, item.label, 11f, Ui.TEXT);
                title.setSingleLine(true);
                title.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
                tLp.topMargin = Ui.dp(this, 6);
                col.addView(title, tLp);

                TextView badge = Ui.badge(this, "1×2", Ui.PRIMARY, Ui.ACCENT);
                LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
                bLp.topMargin = Ui.dp(this, 4);
                col.addView(badge, bLp);

                itemCard.addView(col, new FrameLayout.LayoutParams(-1, -1));
            } else {
                // 2x2 Super Card
                LinearLayout col = Ui.col(this);
                col.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));

                LinearLayout topRow = Ui.row(this);
                ImageView iv = new ImageView(this);
                if (item.icon != null) iv.setImageDrawable(item.icon);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                topRow.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

                TextView badge = Ui.badge(this, "SUPER 2×2", Ui.PRIMARY, Ui.ACCENT);
                topRow.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));
                topRow.addView(badge);
                col.addView(topRow);

                col.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));

                TextView title = Ui.label(this, item.label, 15f, Ui.TEXT);
                title.setSingleLine(true);
                col.addView(title);
                TextView sub = Ui.text(this, item.packageName, 10.5f, Ui.MUTED);
                sub.setSingleLine(true);
                col.addView(sub);

                itemCard.addView(col, new FrameLayout.LayoutParams(-1, -1));
            }

            itemCard.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Ui.tap(v);
                    selectedItem = item;
                    showItemOptionsDialog(item, index);
                }
            });

            gridLayout.addView(itemCard, lp);
        }
    }

    private void showItemOptionsDialog(final DesktopItem item, final int index) {
        CharSequence[] options = {
                "Размер 1 × 1 (Стандарт)",
                "Размер 2 × 1 (Широкая плитка)",
                "Размер 1 × 2 (Высокая плитка)",
                "Размер 2 × 2 (Супер-карточка)",
                "Удалить из сетки"
        };

        new AlertDialog.Builder(this)
                .setTitle(item.label)
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            item.spanX = 1; item.spanY = 1;
                        } else if (which == 1) {
                            item.spanX = 2; item.spanY = 1;
                        } else if (which == 2) {
                            item.spanX = 1; item.spanY = 2;
                        } else if (which == 3) {
                            item.spanX = 2; item.spanY = 2;
                        } else if (which == 4) {
                            desktopApps.remove(index);
                        }
                        renderDesktopGrid();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showAddAppDialog() {
        if (installedApps.isEmpty()) {
            toast("Список приложений загружается…");
            return;
        }

        final PackageManager pm = getPackageManager();
        CharSequence[] names = new CharSequence[installedApps.size()];
        for (int i = 0; i < installedApps.size(); i++) {
            names[i] = installedApps.get(i).loadLabel(pm);
        }

        new AlertDialog.Builder(this)
                .setTitle("Выберите приложение")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        ResolveInfo ri = installedApps.get(which);
                        Drawable d = null;
                        try { d = ri.activityInfo.loadIcon(pm); } catch (Throwable ignored) {}
                        desktopApps.add(new DesktopItem(ri.loadLabel(pm).toString(),
                                ri.activityInfo.packageName, d, 2, 1, 0, 0));
                        renderDesktopGrid();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void applyDesktopLayout() {
        if (busy) {
            toast("Дождитесь текущей команды");
            return;
        }
        if (desktopApps.isEmpty()) {
            toast("Сетка пуста");
            return;
        }

        // Build atomic SQL query for OriginOS launcher.db
        StringBuilder sb = new StringBuilder();
        sb.append("DB=/data/user/0/com.bbk.launcher2/databases/launcher.db; ");
        sb.append("if [ ! -f $DB ]; then echo NO_DB; exit 0; fi; ");

        for (DesktopItem item : desktopApps) {
            sb.append("sqlite3 $DB \"UPDATE favorites SET spanX=")
                    .append(item.spanX)
                    .append(", spanY=")
                    .append(item.spanY)
                    .append(" WHERE intent LIKE '%")
                    .append(item.packageName)
                    .append("%';\"; ");
        }

        sb.append("am force-stop com.bbk.launcher2; ");
        sb.append("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; ");
        sb.append("echo APPLIED_DESKTOP;");

        desktopRun = true;
        if (statusTextView != null) {
            statusTextView.setText("Отправка в launcher.db… Откройте режим игры");
            statusTextView.setTextColor(Ui.ACCENT);
        }
        if (hintCardView != null) hintCardView.setVisibility(View.VISIBLE);

        if (!apply(sb.toString())) {
            desktopRun = false;
            if (hintCardView != null) hintCardView.setVisibility(View.GONE);
            if (statusTextView != null) {
                statusTextView.setText("Не отправлено — откройте режим игры");
                statusTextView.setTextColor(Ui.ERROR);
            }
        }
    }

    private boolean desktopRun = false;

    public void onDesktopResult(boolean ok, String out) {
        desktopRun = false;
        if (hintCardView != null) hintCardView.setVisibility(View.GONE);
        if (!ok) {
            if (statusTextView != null) {
                statusTextView.setText("Нет ответа от uid 1000 — откройте режим игры");
                statusTextView.setTextColor(Ui.ERROR);
            }
        } else if (out != null && out.contains("NO_DB")) {
            if (statusTextView != null) {
                statusTextView.setText("launcher.db не найден (проверьте лончер)");
                statusTextView.setTextColor(Ui.ERROR);
            }
        } else if (out != null && out.contains("APPLIED_DESKTOP")) {
            if (statusTextView != null) {
                statusTextView.setText("Размеры иконок применены! Проверьте рабочий стол");
                statusTextView.setTextColor(Ui.ACCENT);
            }
            toast("Размеры рабочего стола обновлены!");
        } else {
            if (statusTextView != null) {
                statusTextView.setText("Команда выполнена — проверьте рабочий стол");
                statusTextView.setTextColor(Ui.TEXT);
            }
        }
    }
}
