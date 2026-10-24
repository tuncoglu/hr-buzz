package com.emre.hrbuzz;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/** Small view helpers shared by the screens, so layouts stay short. */
final class Ui {

    /** Zone 0 (below zone 1) through zone 5, Polar's grey / blue / green / yellow / red. */
    static final int[] ZONE_COLORS = {
            0xFF5F6368, 0xFF9AA0A6, 0xFF4F8EF7, 0xFF34C759, 0xFFFFC400, 0xFFFF3B30};

    static final int TEXT = 0xFFF2F2F2;
    static final int TEXT2 = 0xFFA0A0A8;
    /** Hints: 5.2:1 on a card, over the 4.5:1 that small text needs. */
    static final int TEXT3 = 0xFF8E8E93;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static TextView bold(TextView t) {
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** Selectable pill used for sport, sex, voice and feeling choices. */
    static TextView chip(Context c, String label) {
        TextView t = text(c, label, 15, TEXT);
        t.setBackgroundResource(R.drawable.chip);
        t.setGravity(Gravity.CENTER);
        int h = dp(c, 16);
        t.setPadding(h, dp(c, 9), h, dp(c, 9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(c, 8);
        t.setLayoutParams(lp);
        return t;
    }

    /** Marks exactly one child of {@code group} selected. */
    static void select(ViewGroup group, int index) {
        for (int i = 0; i < group.getChildCount(); i++) {
            group.getChildAt(i).setSelected(i == index);
        }
    }

    /** Big number with a caption under it, for the training and summary grids. */
    static final class Tile {
        final LinearLayout root;
        final TextView value;
        final TextView label;

        Tile(Context c, String caption, float valueSp) {
            root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setPadding(0, dp(c, 10), 0, dp(c, 10));
            value = bold(text(c, "--", valueSp, TEXT));
            value.setIncludeFontPadding(false);
            value.setSingleLine();
            value.setGravity(Gravity.CENTER);
            label = text(c, caption, 13, TEXT2);
            label.setGravity(Gravity.CENTER);
            root.addView(value);
            root.addView(label);
        }

        void set(String v) {
            value.setText(v);
        }
    }

    /** Lays tiles out {@code columns} to a row inside {@code parent}. */
    static Tile[] grid(Context c, LinearLayout parent, String[] captions, int columns,
                       float valueSp) {
        Tile[] tiles = new Tile[captions.length];
        LinearLayout row = null;
        for (int i = 0; i < captions.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                parent.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            tiles[i] = new Tile(c, captions[i], valueSp);
            row.addView(tiles[i].root, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        return tiles;
    }

    /** Calls {@code onValue} with every complete number typed; ignores half-typed input. */
    static void onNumber(EditText input, IntConsumer onValue) {
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String v = s.toString().trim();
                if (v.isEmpty()) {
                    onValue.accept(0);
                    return;
                }
                try {
                    onValue.accept(Integer.parseInt(v));
                } catch (NumberFormatException ignored) {
                    // half-typed input; keep the old value
                }
            }
        });
    }

    static void show(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Dark system bars to match the theme on every screen. */
    static void darkBars(Activity a) {
        a.getWindow().setStatusBarColor(0xFF0E0E10);
        a.getWindow().setNavigationBarColor(0xFF0E0E10);
    }

    /** The plan's steps as coloured bars sized by duration, with a caption under each. */
    static View stepsBar(Context c, CoachLogic.Plan plan) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(c);
        LinearLayout labels = new LinearLayout(c);
        for (CoachLogic.Step st : plan.steps) {
            View seg = new View(c);
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setCornerRadius(dp(c, 5));
            d.setColor(ZONE_COLORS[st.zoneHigh]);
            seg.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, 10),
                    Math.max(1, st.minutes));
            lp.rightMargin = dp(c, 3);
            bar.addView(seg, lp);
            String zones = st.zoneLow == st.zoneHigh ? "Z" + st.zoneLow
                    : "Z" + st.zoneLow + "\u2013" + st.zoneHigh;
            TextView t = text(c, st.minutes + "\u2032 " + zones, 12, TEXT2);
            t.setSingleLine();
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Math.max(1, st.minutes)));
        }
        col.addView(bar);
        col.addView(labels);
        return col;
    }
}
