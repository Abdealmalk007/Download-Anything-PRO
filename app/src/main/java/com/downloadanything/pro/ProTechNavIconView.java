package com.downloadanything.pro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Three scalable bottom-navigation icons drawn consistently on every Android font. */
final class ProTechNavIconView extends View {
    private final String kind;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    ProTechNavIconView(Context context,String kind,int color){super(context);this.kind=kind;paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float w=getWidth(),h=getHeight();paint.setStrokeWidth(Math.max(2f,w*.085f));
        if("download".equals(kind)){canvas.drawLine(w*.5f,h*.13f,w*.5f,h*.62f,paint);Path p=new Path();p.moveTo(w*.27f,h*.45f);p.lineTo(w*.5f,h*.68f);p.lineTo(w*.73f,h*.45f);canvas.drawPath(p,paint);canvas.drawLine(w*.22f,h*.82f,w*.78f,h*.82f,paint);}
        else if("media".equals(kind)){canvas.drawRoundRect(new RectF(w*.14f,h*.22f,w*.86f,h*.78f),w*.08f,w*.08f,paint);Path p=new Path();p.moveTo(w*.43f,h*.36f);p.lineTo(w*.68f,h*.5f);p.lineTo(w*.43f,h*.64f);p.close();canvas.drawPath(p,paint);}
        else{float r=w*.08f;float[] x={.28f,.5f,.72f},y={.31f,.54f};for(float yy:y)for(float xx:x)canvas.drawCircle(w*xx,h*yy,r,paint);canvas.drawRoundRect(new RectF(w*.18f,h*.18f,w*.82f,h*.72f),w*.09f,w*.09f,paint);}
    }
}
