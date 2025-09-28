package org.birroph.continuousrec;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class AudioLevelMeter extends View {
    private Paint paint = new Paint();
    private int thresholdDb = 50;
    private float currentLevel = 0f; // 0..1

    public AudioLevelMeter(Context context) { super(context); }
    public AudioLevelMeter(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }

    public void setThresholdDb(int db) { this.thresholdDb = db; invalidate(); }
    public void setLevel(float level) { this.currentLevel = level; invalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(200);
        // draw background bar
        paint.setColor(0xffdddddd);
        canvas.drawRect(0, h/4f, w, h*3/4f, paint);
        // draw level
        paint.setColor(0xff33aa33);
        canvas.drawRect(0, h/4f, w * currentLevel, h*3/4f, paint);
        // draw threshold line (red)
        float t = (thresholdDb / 120f); // naive mapping
        paint.setColor(0xffcc0000);
        canvas.drawRect(w * t - 2, h/8f, w * t + 2, h*7/8f, paint);
    }
}