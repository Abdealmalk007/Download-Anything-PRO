package com.downloadanything.pro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Scalable waveform on the converter's blue circular badge. */
final class AudioGlyphView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int fill;

    AudioGlyphView(Context context,int color){
        super(context);
        fill=color;
        paint.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float w=getWidth(),h=getHeight(),cx=w*.5f,cy=h*.5f;
        float radius=Math.min(w,h)*.46f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        canvas.drawCircle(cx,cy,radius,paint);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f,radius*.10f));
        float[] heights={.28f,.50f,.72f,.50f,.28f};
        for(int i=0;i<heights.length;i++){
            float x=cx+(i-2)*radius*.31f;
            float half=radius*heights[i]*.5f;
            canvas.drawLine(x,cy-half,x,cy+half,paint);
        }
    }
}
