package com.downloadanything.pro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Clean two-tone outline glyphs; no filled circles or blob-shaped platform marks. */
final class ToolGlyphView extends View {
    private final String kind;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint contrastPaint=new Paint(Paint.ANTI_ALIAS_FLAG);

    ToolGlyphView(Context context,String kind,int color){
        super(context);this.kind=kind;
        paint.setColor(color);paint.setStyle(Paint.Style.STROKE);
        contrastPaint.setColor(Color.WHITE);contrastPaint.setAlpha(190);contrastPaint.setStyle(Paint.Style.STROKE);
        for(Paint p:new Paint[]{paint,contrastPaint}){p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        // Tiles are much wider than the glyph's height. Using the full tile
        // width makes strokes fill the globe/link interiors on real devices.
        float base=Math.max(2.5f,Math.min(getWidth(),getHeight())*.052f);
        contrastPaint.setStrokeWidth(base+2.2f);paint.setStrokeWidth(base);
        if("browser".equals(kind)){drawBrowser(canvas,contrastPaint);drawBrowser(canvas,paint);}
        else{drawLinks(canvas,contrastPaint);drawLinks(canvas,paint);}
    }

    private void drawBrowser(Canvas canvas,Paint p){
        float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.31f;
        canvas.drawCircle(cx,cy,r,p);
        canvas.drawOval(new RectF(cx-r*.43f,cy-r,cx+r*.43f,cy+r),p);
        canvas.drawLine(cx-r,cy,cx+r,cy,p);
        canvas.drawArc(new RectF(cx-r,cy-r*.48f,cx+r,cy+r*.48f),200,140,false,p);
    }

    private void drawLinks(Canvas canvas,Paint p){
        float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.19f;
        canvas.save();canvas.rotate(-38,cx,cy);
        canvas.drawRoundRect(new RectF(cx-r*1.55f,cy-r,cx-r*.05f,cy+r),r,r,p);
        canvas.drawRoundRect(new RectF(cx+r*.05f,cy-r,cx+r*1.55f,cy+r),r,r,p);
        canvas.drawLine(cx-r*.55f,cy,cx+r*.55f,cy,p);
        canvas.restore();
    }
}
