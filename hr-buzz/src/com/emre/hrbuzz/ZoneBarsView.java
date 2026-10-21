package com.emre.hrbuzz;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Time in each heart rate zone, zone 5 at the top like Polar Flow, with the time below zone 1
 * last so the rows add up to the session's heart rate time.
 */
public class ZoneBarsView extends View {

    private long[] zoneMs = new long[6];
    private int hrMax = 190;

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public ZoneBarsView(Context c) {
        super(c);
        track.setColor(0xFF2A2A2E);
        label.setColor(Ui.TEXT2);
        label.setTextSize(Ui.dp(c, 13));
        value.setColor(Ui.TEXT);
        value.setTextSize(Ui.dp(c, 14));
        setMinimumHeight(Ui.dp(c, 6 * 34));
    }

    public void setData(long[] zoneMs, int hrMax) {
        this.zoneMs = zoneMs;
        this.hrMax = hrMax;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), w),
                resolveSize(getSuggestedMinimumHeight(), h));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long most = 1;
        for (int z = 0; z <= 5; z++) {
            most = Math.max(most, zoneMs[z]);
        }
        float rowH = getHeight() / 6f;
        float labelW = Ui.dp(getContext(), 96);
        float valueW = Ui.dp(getContext(), 64);
        float barH = Ui.dp(getContext(), 12);
        float radius = barH / 2;
        for (int z = 5; z >= 0; z--) {
            float cy = (5 - z) * rowH + rowH / 2;
            String text;
            if (z == 0) {
                text = "Below  <" + Training.zoneFloor(1, hrMax);
            } else {
                int lo = Training.zoneFloor(z, hrMax);
                text = "Z" + z + "  " + (z == 5 ? lo + "+"
                        : lo + "\u2013" + (Training.zoneFloor(z + 1, hrMax) - 1));
            }
            canvas.drawText(text, 0, cy + label.getTextSize() / 3, label);
            float x0 = labelW;
            float x1 = getWidth() - valueW;
            rect.set(x0, cy - barH / 2, x1, cy + barH / 2);
            canvas.drawRoundRect(rect, radius, radius, track);
            if (zoneMs[z] > 0) {
                bar.setColor(Ui.ZONE_COLORS[z]);
                rect.right = x0 + Math.max(barH, (x1 - x0) * zoneMs[z] / (float) most);
                canvas.drawRoundRect(rect, radius, radius, bar);
            }
            String t = Training.duration(zoneMs[z]);
            canvas.drawText(t, getWidth() - value.measureText(t),
                    cy + value.getTextSize() / 3, value);
        }
    }
}
