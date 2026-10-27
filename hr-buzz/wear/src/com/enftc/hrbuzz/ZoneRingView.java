package com.enftc.hrbuzz;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The 270 degree gauge around the edge of the screen: five dimmed zone segments and a dot at
 * the current heart rate in that zone's colour. The geometry is in {@link WearLogic}.
 */
public class ZoneRingView extends View {

    private final Paint segment = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private int bpm = -1;
    private int hrMax;

    public ZoneRingView(Context context) {
        super(context);
        segment.setStyle(Paint.Style.STROKE);
        segment.setStrokeCap(Paint.Cap.ROUND);
        progress.setStyle(Paint.Style.STROKE);
        progress.setStrokeCap(Paint.Cap.ROUND);
        thumb.setStyle(Paint.Style.FILL);
        thumb.setColor(Wu.TEXT);
    }

    /** @param bpm current heart rate, or -1 for none (the dot is hidden) */
    public void set(int bpm, int hrMax) {
        if (bpm == this.bpm && hrMax == this.hrMax) {
            return;
        }
        this.bpm = bpm;
        this.hrMax = hrMax;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float u = Wu.unit(getContext());
        float stroke = 14 * u;
        float inset = stroke / 2 + 4 * u;
        box.set(inset, inset, getWidth() - inset, getHeight() - inset);
        segment.setStrokeWidth(stroke);
        progress.setStrokeWidth(stroke);

        for (int z = 1; z <= WearLogic.ZONES; z++) {
            segment.setColor(Wu.ZONE[z]);
            segment.setAlpha(77);
            float start = WearLogic.segmentStart(z);
            canvas.drawArc(box, start, WearLogic.segmentSweep(z), false, segment);
        }
        if (bpm > 0 && hrMax > 0) {
            // Bright part of each zone segment, up to the current heart rate.
            float now = WearLogic.angleFor(bpm, hrMax);
            for (int z = 1; z <= WearLogic.ZONES; z++) {
                float start = WearLogic.segmentStart(z);
                float lit = Math.min(WearLogic.segmentSweep(z), now - start);
                if (lit > 0) {
                    progress.setColor(Wu.ZONE[z]);
                    canvas.drawArc(box, start, lit, false, progress);
                }
            }
            double angle = Math.toRadians(WearLogic.angleFor(bpm, hrMax));
            float r = (box.width()) / 2f;
            float cx = box.centerX() + (float) (r * Math.cos(angle));
            float cy = box.centerY() + (float) (r * Math.sin(angle));
            canvas.drawCircle(cx, cy, 8 * u, thumb);
        }
    }
}
