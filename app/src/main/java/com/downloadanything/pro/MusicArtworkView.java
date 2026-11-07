package com.downloadanything.pro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Centered vector-like music note used when an audio file has no embedded artwork. */
final class MusicArtworkView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path note=new Path();
    MusicArtworkView(Context context,int color){super(context);paint.setColor(color);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float w=getWidth(),h=getHeight();
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.argb(32,255,255,255));
        canvas.drawRoundRect(new RectF(w*.27f,h*.20f,w*.73f,h*.80f),w*.09f,w*.09f,paint);
        paint.setColor(Color.WHITE);note.reset();
        note.moveTo(w*.45f,h*.32f);note.lineTo(w*.67f,h*.27f);note.lineTo(w*.67f,h*.59f);
        note.lineTo(w*.57f,h*.61f);note.lineTo(w*.57f,h*.39f);note.lineTo(w*.45f,h*.42f);note.close();
        canvas.drawPath(note,paint);canvas.drawCircle(w*.39f,h*.65f,w*.10f,paint);canvas.drawCircle(w*.61f,h*.59f,w*.10f,paint);
    }
}
