package com.poc.gctune;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared Material You (dark green) palette and small view helpers. */
final class Ui {

    private Ui() {}

    // Material You dark green, aShell You style
    static final int BG = 0xFF0A120D;              // deep background
    static final int SURFACE = 0xFF111C14;         // cards
    static final int PRIMARY = 0xFF14462D;         // button fill
    static final int PRIMARY_PRESSED = 0xFF1C5A3B;
    static final int ACCENT = 0xFF82E5A6;          // mint text / icons
    static final int BORDER = 0xFF3D7D56;          // input outline
    static final int BORDER_SOFT = 0x553D7D56;     // card outline
    static final int INPUT_BG = 0xFF0C1610;
    static final int TEXT = 0xFFE6E1E5;
    static final int MUTED = 0xFF8A9A8F;
    static final int ERROR = 0xFFE3A7A7;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static int sp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable shape(Context c, int bg, float radius, int stroke, float strokeW) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(dp(c, radius));
        if (strokeW > 0) gd.setStroke(dp(c, strokeW), stroke);
        return gd;
    }

    static StateListDrawable pressable(Context c, int normal, int pressed, float radius) {
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed},
                shape(c, pressed, radius, 0, 0));
        sld.addState(new int[]{}, shape(c, normal, radius, 0, 0));
        return sld;
    }

    static TextView text(Context c, String s, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    static TextView label(Context c, String s, float sizeSp, int color) {
        TextView t = text(c, s, sizeSp, color);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static LinearLayout row(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        return ll;
    }

    static LinearLayout col(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.VERTICAL);
        return ll;
    }

    static LinearLayout.LayoutParams grow() {
        return new LinearLayout.LayoutParams(0, 1, 1f);
    }
}
