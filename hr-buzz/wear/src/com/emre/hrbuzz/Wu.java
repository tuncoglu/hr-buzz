package com.emre.hrbuzz;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small helpers for building the watch screens in code. Sizes are written against the
 * 360 px design frame and scaled to whatever round screen the watch has, so one number
 * means the same share of the screen on a 41 mm and a 45 mm watch.
 */
final class Wu {

    static final int BLACK = Color.BLACK;
    static final int TEXT = Color.parseColor("#F2F2F2");
    static final int MUTED = Color.parseColor("#B8BEC6");
    static final int CARD = Color.parseColor("#161A1E");
    static final int BUTTON = Color.parseColor("#1E2328");
    /** Zone 1..5, then index 0 for below zone 1. Different in lightness as well as hue. */
    static final int[] ZONE = {
            Color.parseColor("#6B737C"), Color.parseColor("#7DB8FF"), Color.parseColor("#5BE0C3"),
            Color.parseColor("#F2E05B"), Color.parseColor("#FFB454"), Color.parseColor("#FF8A75")};
    static final int ALERT = ZONE[5];
    static final int ON_ACCENT = Color.parseColor("#00201A");
    static final int TARGET_BG = Color.parseColor("#0F3A31");
    static final int TARGET_TEXT = Color.parseColor("#7FF0D6");

    private Wu() {
    }

    /** One design pixel (of a 360 px frame) in real pixels. */
    static float unit(Context c) {
        return c.getResources().getDisplayMetrics().widthPixels / 360f;
    }

    static int px(Context c, float designPx) {
        return Math.round(designPx * unit(c));
    }

    static GradientDrawable pill(int color, Context c, float radiusDesignPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(c, radiusDesignPx));
        return d;
    }

    static TextView text(Context c, String s, float sizeDesignPx, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeDesignPx * unit(c));
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** A filled, full-height rounded button; at least 44 design px tall for touch. */
    static Button button(Context c, String s, int bg, int fg, float heightDesignPx) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22 * unit(c));
        b.setTextColor(fg);
        b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        b.setBackground(pill(bg, c, heightDesignPx / 2));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(px(c, 12), 0, px(c, 12), 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(c, heightDesignPx)));
        return b;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static View space(Context c, float designPx) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px(c, designPx)));
        return v;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    /** Colour for a heart rate: its zone's, or grey below zone 1 or with no reading. */
    static int zoneColor(int bpm, int hrMax) {
        return ZONE[Training.zone(bpm, hrMax)];
    }
}
