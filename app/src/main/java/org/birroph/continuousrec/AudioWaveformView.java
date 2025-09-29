package org.birroph.continuousrec;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class AudioWaveformView extends View {
    private Paint linePaint;
    private Paint thresholdPaint;
    private List<Float> levels = new ArrayList<>();
    private int maxSamples = 200; // numero di punti visibili
    private float thresholdDb = 50;

    public AudioWaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        linePaint = new Paint();
        linePaint.setColor(0xff33aa33); // verde
        linePaint.setStrokeWidth(3f);

        thresholdPaint = new Paint();
        thresholdPaint.setColor(0xffcc0000); // rosso
        thresholdPaint.setStrokeWidth(2f);
    }

    public void setThresholdDb(float db) {
        thresholdDb = db;
        invalidate();
    }

    public void addLevel(float normalizedLevel) {
        // normalizedLevel è già compreso tra 0 e 1
        float norm = Math.min(1f, Math.max(0f, normalizedLevel));
        levels.add(norm);
        if (levels.size() > maxSamples) {
            levels.remove(0); // rimuovi vecchi
        }
        invalidate();
    }

    public void clearLevels() {
        levels.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        if (levels.size() < 2) return;

        float dx = (float) w / (maxSamples - 1);

        // Disegna prima la linea onda
        for (int i = 1; i < levels.size(); i++) {
            float x1 = (i - 1) * dx;
            float y1 = h - (levels.get(i - 1) * h);
            float x2 = i * dx;
            float y2 = h - (levels.get(i) * h);
            canvas.drawLine(x1, y1, x2, y2, linePaint);
        }

        // Calcola posizione verticale soglia (valore normalizzato fra 0 e 1)
        float thresholdNormalized = thresholdDb / 120f;
        float ty = h - (thresholdNormalized * h);

        // Disegna linea rossa della soglia sopra la linea onda, ma nel punto esatto medio
        canvas.drawLine(0, ty, w, ty, thresholdPaint);
    }
}

