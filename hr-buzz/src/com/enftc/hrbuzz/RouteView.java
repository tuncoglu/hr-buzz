package com.enftc.hrbuzz;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.List;

/**
 * The GPS track drawn to scale and coloured by heart rate zone. No map tiles (that would need
 * the network and Google Play services); export GPX to see it on a map.
 */
public class RouteView extends View {

    private List<Training.Sample> samples;
    private int hrMax = 190;
    private double minLat, maxLat, minLon, maxLon, lonScale;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RouteView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 3));
        line.setStrokeCap(Paint.Cap.ROUND);
        setMinimumHeight(Ui.dp(c, 260));
    }

    /** @return false when no sample has a fix (nothing to draw) */
    public boolean setData(List<Training.Sample> samples, int hrMax) {
        this.samples = samples;
        this.hrMax = hrMax;
        minLat = minLon = Double.MAX_VALUE;
        maxLat = maxLon = -Double.MAX_VALUE;
        int fixes = 0;
        for (Training.Sample s : samples) {
            if (s == null || !s.hasFix()) {
                continue;
            }
            fixes++;
            minLat = Math.min(minLat, s.lat);
            maxLat = Math.max(maxLat, s.lat);
            minLon = Math.min(minLon, s.lon);
            maxLon = Math.max(maxLon, s.lon);
        }
        lonScale = Math.cos(Math.toRadians((minLat + maxLat) / 2));
        invalidate();
        return fixes >= 2;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), w),
                resolveSize(getSuggestedMinimumHeight(), h));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (samples == null || maxLat < minLat) {
            return;
        }
        float pad = Ui.dp(getContext(), 12);
        double w = Math.max(1e-6, (maxLon - minLon) * lonScale);
        double h = Math.max(1e-6, maxLat - minLat);
        double scale = Math.min((getWidth() - 2 * pad) / w, (getHeight() - 2 * pad) / h);
        float offX = (float) ((getWidth() - w * scale) / 2);
        float offY = (float) ((getHeight() - h * scale) / 2);

        Training.Sample prev = null;
        Training.Sample first = null;
        Training.Sample last = null;
        for (Training.Sample s : samples) {
            if (s == null) {
                prev = null;
                continue;
            }
            if (!s.hasFix()) {
                continue;
            }
            if (first == null) {
                first = s;
            }
            last = s;
            if (prev != null) {
                line.setColor(Ui.ZONE_COLORS[Training.zone(s.bpm, hrMax)]);
                canvas.drawLine(x(prev, scale, offX), y(prev, scale, offY),
                        x(s, scale, offX), y(s, scale, offY), line);
            }
            prev = s;
        }
        float r = Ui.dp(getContext(), 6);
        if (first != null) {
            dot.setColor(0xFF34C759);
            canvas.drawCircle(x(first, scale, offX), y(first, scale, offY), r, dot);
        }
        if (last != null) {
            dot.setColor(0xFFFFFFFF);
            canvas.drawCircle(x(last, scale, offX), y(last, scale, offY), r, dot);
        }
    }

    private float x(Training.Sample s, double scale, float off) {
        return (float) ((s.lon - minLon) * lonScale * scale) + off;
    }

    private float y(Training.Sample s, double scale, float off) {
        return getHeight() - ((float) ((s.lat - minLat) * scale) + off);
    }
}
