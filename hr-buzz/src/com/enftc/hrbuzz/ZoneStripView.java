package com.enftc.hrbuzz;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** The five zones side by side with a pointer at the current heart rate, for glancing. */
public class ZoneStripView extends View {

    private int bpm = -1;
    private int hrMax = 190;

    /** The heart rate range the current workout step asks for; 0 when there is none. */
    private int bandLow;
    private int bandHigh;
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint seg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path tri = new Path();

    public ZoneStripView(Context c) {
        super(c);
        pointer.setColor(Ui.TEXT);
        outline.setColor(Ui.TEXT);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(Ui.dp(c, 3));
        num.setTextSize(Ui.dp(c, 12));
        num.setTextAlign(Paint.Align.CENTER);
        setMinimumHeight(Ui.dp(c, 34));
    }

    public void set(int bpm, int hrMax) {
        if (bpm == this.bpm && hrMax == this.hrMax) {
            return;
        }
        this.bpm = bpm;
        this.hrMax = hrMax;
        invalidate();
    }

    /** Outlines the range a coached step is asking for, so "in target" can be seen at a glance. */
    public void setBand(int low, int high) {
        if (low == bandLow && high == bandHigh) {
            return;
        }
        bandLow = low;
        bandHigh = high;
        invalidate();
    }

    /** Where a heart rate sits along the strip: 50% .. 100% of HRmax maps across the five zones. */
    private float xFor(int bpm, float segW, float gap) {
        float pct = bpm * 100f / hrMax;
        float f = Math.max(0, Math.min(1, (pct - 50) / 50f));
        int z = Math.max(1, Math.min(5, (int) (f * 5) + 1));
        float within = f * 5 - (z - 1);
        return (z - 1) * (segW + gap) + Math.min(1, within) * segW;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), w),
                resolveSize(getSuggestedMinimumHeight(), h));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float gap = Ui.dp(getContext(), 3);
        float segW = (getWidth() - 4 * gap) / 5f;
        float top = Ui.dp(getContext(), 10);
        float bottom = getHeight();
        float r = Ui.dp(getContext(), 5);
        int zone = Training.zone(bpm, hrMax);
        for (int z = 1; z <= 5; z++) {
            float x = (z - 1) * (segW + gap);
            seg.setColor(Ui.ZONE_COLORS[z]);
            seg.setAlpha(z == zone ? 255 : 70);
            rect.set(x, top, x + segW, bottom);
            canvas.drawRoundRect(rect, r, r, seg);
            // Dark on the lit segment; light on the dimmed ones, where dark was unreadable.
            num.setColor(z == zone ? 0xFF0E0E10 : Ui.TEXT2);
            canvas.drawText(String.valueOf(z), x + segW / 2,
                    (top + bottom) / 2 + num.getTextSize() / 3, num);
        }
        if (bandHigh > bandLow && bandLow > 0) {
            float half = outline.getStrokeWidth() / 2;
            rect.set(xFor(bandLow, segW, gap), top + half, xFor(bandHigh, segW, gap),
                    bottom - half);
            canvas.drawRoundRect(rect, r, r, outline);
        }
        if (bpm <= 0) {
            return;
        }
        float x = xFor(bpm, segW, gap);
        float s = Ui.dp(getContext(), 7);
        tri.reset();
        tri.moveTo(x - s, 0);
        tri.lineTo(x + s, 0);
        tri.lineTo(x, top);
        tri.close();
        canvas.drawPath(tri, pointer);
    }
}
