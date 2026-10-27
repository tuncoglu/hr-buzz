package com.enftc.hrbuzz;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * A time series line (heart rate or pace), optionally coloured point by point and laid over
 * horizontal bands (the heart rate zones). NaN values leave gaps.
 */
public class LineChartView extends View {

    public interface Labeler {
        String label(float value);
    }

    private float[] values = new float[0];
    private int[] colors;
    private float min;
    private float max = 1;
    private boolean invert;
    private long durationMs;
    private float[] bandFrom = new float[0];
    private int[] bandColors = new int[0];
    private Labeler labeler = v -> String.valueOf(Math.round(v));

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint band = new Paint();
    private final Paint grid = new Paint();
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LineChartView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 2));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(Ui.TEXT);
        grid.setColor(0x22FFFFFF);
        grid.setStrokeWidth(1);
        label.setColor(Ui.TEXT3);
        label.setTextSize(Ui.dp(c, 11));
        setMinimumHeight(Ui.dp(c, 170));
    }

    /**
     * @param invert draw small values at the top (pace: faster is up)
     */
    public void setData(float[] values, int[] colors, float min, float max, boolean invert,
                        long durationMs, Labeler labeler) {
        this.values = values;
        this.colors = colors;
        this.min = min;
        this.max = Math.max(max, min + 1);
        this.invert = invert;
        this.durationMs = durationMs;
        this.labeler = labeler;
        invalidate();
    }

    /** Coloured background from each {@code from[i]} up to the next one (or the top). */
    public void setBands(float[] from, int[] colors) {
        bandFrom = from;
        bandColors = colors;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), w),
                resolveSize(getSuggestedMinimumHeight(), h));
    }

    private float y(float v, float top, float bottom) {
        float f = (v - min) / (max - min);
        if (invert) {
            f = 1 - f;
        }
        return bottom - f * (bottom - top);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float left = Ui.dp(getContext(), 34);
        float top = Ui.dp(getContext(), 6);
        float right = getWidth() - Ui.dp(getContext(), 4);
        float bottom = getHeight() - Ui.dp(getContext(), 18);

        for (int i = 0; i < bandFrom.length; i++) {
            float lo = Math.max(min, bandFrom[i]);
            float hi = i + 1 < bandFrom.length ? Math.min(max, bandFrom[i + 1]) : max;
            if (hi <= lo) {
                continue;
            }
            band.setColor((bandColors[i] & 0x00FFFFFF) | 0x1E000000);
            float y1 = y(lo, top, bottom);
            float y2 = y(hi, top, bottom);
            canvas.drawRect(left, Math.min(y1, y2), right, Math.max(y1, y2), band);
        }

        for (int i = 0; i <= 2; i++) {
            float v = min + (max - min) * i / 2f;
            float yy = y(v, top, bottom);
            canvas.drawLine(left, yy, right, yy, grid);
            canvas.drawText(labeler.label(v), 0, yy + label.getTextSize() / 3, label);
        }
        canvas.drawText("0:00", left, getHeight() - Ui.dp(getContext(), 3), label);
        String end = Training.duration(durationMs);
        canvas.drawText(end, right - label.measureText(end),
                getHeight() - Ui.dp(getContext(), 3), label);

        int n = values.length;
        if (n < 2) {
            return;
        }
        float step = (right - left) / (n - 1);
        for (int i = 1; i < n; i++) {
            float a = values[i - 1];
            float b = values[i];
            if (Float.isNaN(a) || Float.isNaN(b)) {
                continue;
            }
            if (colors != null) {
                line.setColor(colors[i]);
            }
            canvas.drawLine(left + (i - 1) * step, y(a, top, bottom),
                    left + i * step, y(b, top, bottom), line);
        }
    }
}
