package com.aliothmoon.maameow.debug;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

/** Debug-only animated content used to verify capture while display 0 is asleep. */
public final class FrameProbeActivity extends Activity {
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        Log.i("VD_FRAME_PROBE", "touch action=" + event.getActionMasked()
                + " x=" + event.getX() + " y=" + event.getY());
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new View(this) {
            private final Paint paint = new Paint();
            private int frame;

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                canvas.drawColor((frame++ & 1) == 0 ? Color.rgb(24, 48, 96) : Color.rgb(96, 48, 24));
                paint.setColor(Color.WHITE);
                paint.setTextSize(48);
                canvas.drawText("VD frame " + frame, 32, 80, paint);
                postInvalidateDelayed(100);
            }
        });
    }
}
