package com.emre.hrbuzz;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Every sport in one list; the current one is highlighted. GPS sports say so. */
public class SportActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int side = Wu.px(this, 62);
        LinearLayout page = Wu.column(this);
        page.setPadding(side, Wu.px(this, 46), side, Wu.px(this, 70));
        page.addView(Wu.text(this, "Sport", 18, Wu.MUTED, true));
        page.addView(Wu.space(this, 10));

        String current = Prefs.sport(this);
        for (String sport : Prefs.SPORTS) {
            boolean selected = sport.equals(current);
            String label = sport + (Prefs.usesGps(sport) ? "" : "  ·  no GPS");
            Button b = Wu.button(this, label, selected ? Wu.TARGET_BG : Wu.CARD,
                    selected ? Wu.TARGET_TEXT : Wu.TEXT, 56);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Wu.textPx(this, 19));
            b.setContentDescription(sport + (selected ? ", selected" : ""));
            b.setOnClickListener(v -> {
                Prefs.of(this).edit().putString("sport", sport).apply();
                finish();
            });
            page.addView(b);
            page.addView(Wu.space(this, 6));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Wu.BLACK);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        scroll.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        scroll.requestFocus();      // so the crown scrolls
    }
}
