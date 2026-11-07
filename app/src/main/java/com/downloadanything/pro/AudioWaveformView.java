package com.downloadanything.pro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.View;
import java.nio.ByteBuffer;

/** Lightweight, decorative waveform sampled from the selected audio file. */
public final class AudioWaveformView extends View {
    private static final int BAR_COUNT=64;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile int generation;
    private float[] bars=fallbackBars();

    public AudioWaveformView(Context context){super(context);init();}
    public AudioWaveformView(Context context, AttributeSet attrs){super(context,attrs);init();}

    private void init(){
        paint.setColor(0xff7151f7); // Color is interpolated per bar while drawing
        paint.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setSource(Uri source){
        final int request=++generation;
        if(source==null)return;
        RuntimeExecutors.io().execute(()->{
            float[] sampled=sample(source);
            post(()->{
                if(request!=generation||!isAttachedToWindow())return;
                bars=sampled;
                invalidate();
            });
        });
    }

    private float[] sample(Uri source){
        MediaExtractor extractor=new MediaExtractor();
        try{
            extractor.setDataSource(getContext(),source,null);
            int track=-1;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat format=extractor.getTrackFormat(i);
                String mime=format.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/")){track=i;break;}
            }
            if(track<0)return fallbackBars();
            extractor.selectTrack(track);
            ByteBuffer buffer=ByteBuffer.allocate(32*1024);
            float[] result=new float[BAR_COUNT];
            int count=0;
            while(count<BAR_COUNT){
                buffer.clear();
                int size=extractor.readSampleData(buffer,0);
                if(size<=0)break;
                long energy=0;
                int stride=Math.max(1,size/128);
                for(int i=0;i<size;i+=stride)energy+=Math.abs(buffer.get(i));
                result[count++]=Math.min(1f,0.12f+(energy/(float)Math.max(1,(size/stride)*128)));
                if(!extractor.advance())break;
            }
            if(count==0)return fallbackBars();
            for(int i=count;i<BAR_COUNT;i++)result[i]=result[i%count];
            return result;
        }catch(Exception ignored){return fallbackBars();}
        finally{try{extractor.release();}catch(Exception ignored){}}
    }

    private static float[] fallbackBars(){
        float[] result=new float[BAR_COUNT];
        for(int i=0;i<BAR_COUNT;i++)result[i]=0.18f+(float)Math.abs(Math.sin(i*0.47))*0.48f;
        return result;
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float center=getHeight()/2f;
        float step=getWidth()/(float)BAR_COUNT;
        paint.setStrokeWidth(Math.max(2f,step*0.48f));
        for(int i=0;i<BAR_COUNT;i++){
            float half=Math.max(3f,bars[i]*getHeight()*0.36f);
            float t=i/(float)Math.max(1,BAR_COUNT-1);
            int red=(int)(111+(25-111)*t),green=(int)(79+(137-79)*t),blue=(int)(246+(238-246)*t);
            paint.setColor(Color.rgb(red,green,blue));
            float x=(i+0.5f)*step;
            canvas.drawLine(x,center-half,x,center+half,paint);
        }
    }

    @Override protected void onDetachedFromWindow(){generation++;super.onDetachedFromWindow();}
}
