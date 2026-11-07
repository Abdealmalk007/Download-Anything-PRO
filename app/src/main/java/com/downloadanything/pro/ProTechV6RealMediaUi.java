package com.downloadanything.pro;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A read-only V6 presentation adapter over existing MediaStore downloads. */
public final class ProTechV6RealMediaUi {
    private static final int INITIAL_CARD_COUNT = 12;
    private static final int APPEND_CARD_COUNT = 8;
    private static final int COMPLETED_ROW_HEIGHT_DP = 60;
    private static final int COMPLETED_THUMB_DP = 48;
    private static final int GRID_VISUAL_HEIGHT_DP = 128;
    private static final int GRID_CARD_MIN_HEIGHT_DP = 200;
    private static final int GRID_TEXT_REGION_HEIGHT_DP = 64;
    private static final int THUMBNAIL_MAX_PX = 384;
    private static final int THUMBNAIL_CACHE_KB = 6 * 1024;
    private static final long SNAPSHOT_TTL_MS = 15000L;
    private static final Object SNAPSHOT_LOCK = new Object();
    private static volatile List<ProTechV6MediaRepository.MediaItem> cachedSnapshot=Collections.emptyList();
    private static volatile long snapshotLoadedAtMs;
    private static volatile String snapshotSource="downloads";
    private static final ProTechV6MediaSnapshotPolicy SNAPSHOT_POLICY=
            new ProTechV6MediaSnapshotPolicy(SNAPSHOT_TTL_MS);
    private static final ThreadPoolExecutor MEDIA_QUERY_EXECUTOR=new ThreadPoolExecutor(1,1,15L,
            TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(1),r->{
                Thread thread=new Thread(r,"DA-MediaSnapshot");thread.setDaemon(true);return thread;
            },new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final LruCache<String,Bitmap> THUMBNAILS=new LruCache<String,Bitmap>(THUMBNAIL_CACHE_KB){
        @Override protected int sizeOf(String key,Bitmap value){return Math.max(1,value.getByteCount()/1024);}
    };
    private ProTechV6RealMediaUi(){}
    private static int dp(Activity a,int v){return ProTechV6Theme.dp(a,v);}
    public static void populate(Activity activity,LinearLayout host,boolean dark,boolean arabic,String type,int limit,boolean grid){
        populate(activity,host,dark,arabic,type,limit,grid,"",0,false,"downloads");
    }
    public static void populate(Activity activity,LinearLayout host,boolean dark,boolean arabic,String type,
            int limit,boolean grid,String search,int sort,boolean favoritesOnly){
        populate(activity,host,dark,arabic,type,limit,grid,search,sort,favoritesOnly,"downloads");
    }
    public static void populate(Activity activity,LinearLayout host,boolean dark,boolean arabic,String type,
            int limit,boolean grid,String search,int sort,boolean favoritesOnly,String source){
        ProTechV6Theme.Tokens colors=ProTechV6Theme.palette(dark);
        // Do not allow an earlier asynchronous query to overwrite newer search results.
        final Object requestToken=new Object();
        host.setTag(requestToken);
        host.removeAllViews();
        TextView loading=ProTechV6Theme.label(activity,arabic?"جاري تحميل الملفات":"Loading files",13,colors.muted,false);
        loading.setGravity(Gravity.CENTER);
        host.addView(loading,new LinearLayout.LayoutParams(-1,dp(activity,60)));
        ProTechV6StartupTrace.mark("media_refresh_scheduled");
        // Let the initial shell draw before MediaStore, sorting and favorite lookups consume resources.
        host.postOnAnimation(()->loadAndFilterAsync(activity,host,dark,arabic,type,limit,grid,
                search,sort,favoritesOnly,source,colors,requestToken));
    }
    private static void loadAndFilterAsync(Activity activity,LinearLayout host,boolean dark,boolean arabic,
            String type,int limit,boolean grid,String search,int sort,boolean favoritesOnly,String source,
            ProTechV6Theme.Tokens colors,Object requestToken){
        if(activity.isFinishing()||activity.isDestroyed()||host.getTag()!=requestToken)return;
        long now=android.os.SystemClock.elapsedRealtime();
        List<ProTechV6MediaRepository.MediaItem> snapshot=cachedSnapshot;
        MEDIA_QUERY_EXECUTOR.execute(()->{
            long workStarted=android.os.SystemClock.elapsedRealtime();
            List<ProTechV6MediaRepository.MediaItem> queried=snapshot;
            if(!source.equals(snapshotSource)||!SNAPSHOT_POLICY.canReuse(snapshotLoadedAtMs,now)){
                long queryStarted=android.os.SystemClock.elapsedRealtime();
                queried=ProTechV6MediaRepository.recent(activity.getApplicationContext(),20000,"all",source);
                synchronized(SNAPSHOT_LOCK){
                    cachedSnapshot=Collections.unmodifiableList(new ArrayList<>(queried));
                    snapshotLoadedAtMs=android.os.SystemClock.elapsedRealtime();
                    snapshotSource=source;
                }
                ProTechV6StartupTrace.mark("media_snapshot_query_done",
                        android.os.SystemClock.elapsedRealtime()-queryStarted,queried.size());
            }
            final boolean recent=activity instanceof ProTechV6PreviewActivity
                    &&((ProTechV6PreviewActivity)activity).isMediaRecentOnly();
            final String collection=activity instanceof ProTechV6PreviewActivity
                    ?((ProTechV6PreviewActivity)activity).mediaCollectionFilter():"";
            final String folder=activity instanceof ProTechV6PreviewActivity
                    ?((ProTechV6PreviewActivity)activity).mediaFolderFilter():"";
            List<ProTechV6MediaRepository.MediaItem> items=ProTechV6MediaRepository.filter(
                    activity.getApplicationContext(),queried,
                    (recent||!collection.isEmpty()||!folder.isEmpty())?20000:limit,
                    type,search,sort,favoritesOnly);
            if(recent||!collection.isEmpty()||!folder.isEmpty()){
                ArrayList<ProTechV6MediaRepository.MediaItem> subset=new ArrayList<>();
                for(ProTechV6MediaRepository.MediaItem item:items){
                    if(recent&&!RecentPlaybackStore.contains(activity,item.uri))continue;
                    if(!collection.isEmpty()&&!collection.equals(LibraryCollections.collection(activity,item.uri)))
                        continue;
                    if(!folder.isEmpty()&&!folder.equalsIgnoreCase(item.folder))continue;
                    subset.add(item);
                    if(subset.size()>=limit)break;
                }
                items=subset;
            }
            ProTechV6StartupTrace.mark("media_filter_done",
                    android.os.SystemClock.elapsedRealtime()-workStarted,items.size());
            final List<ProTechV6MediaRepository.MediaItem> ready=items;
            activity.runOnUiThread(()->renderItems(activity,host,colors,ready,dark,arabic,type,
                    limit,grid,search,sort,favoritesOnly,source,requestToken));
        });
    }
    private static void renderItems(Activity activity,LinearLayout host,ProTechV6Theme.Tokens colors,
            List<ProTechV6MediaRepository.MediaItem> items,boolean dark,boolean arabic,String type,
            int limit,boolean grid,String search,int sort,boolean favoritesOnly,String source,Object requestToken){
        if(activity.isFinishing()||activity.isDestroyed()||!host.isAttachedToWindow()
                ||host.getTag()!=requestToken)return;
        host.removeAllViews();
        if(items.isEmpty()){
            TextView empty=ProTechV6Theme.label(activity,
                    arabic?"لا توجد وسائط في هذا القسم أو تحتاج للسماح بالوصول":"No media here, or permission is required",14,colors.muted,false);
            empty.setGravity(Gravity.CENTER);
            empty.setBackground(ProTechV6Theme.rounded(activity,colors.card,16,colors.stroke));
            host.addView(empty,new LinearLayout.LayoutParams(-1,dp(activity,96)));
            return;
        }
        Runnable refresh=()->populate(activity,host,dark,arabic,type,limit,grid,search,sort,favoritesOnly,source);
        if(grid)appendGridBatch(activity,host,colors,items,arabic,refresh,requestToken,0,
                Math.min(INITIAL_CARD_COUNT,items.size()));
        else appendListBatch(activity,host,colors,items,arabic,refresh,requestToken,
                0,Math.min(INITIAL_CARD_COUNT,items.size()));
    }
    private static void appendListBatch(Activity activity,LinearLayout host,
            ProTechV6Theme.Tokens colors,List<ProTechV6MediaRepository.MediaItem> items,
            boolean arabic,Runnable refresh,Object requestToken,int start,int end){
        if(activity.isFinishing()||activity.isDestroyed()||host.getTag()!=requestToken
                ||!host.isAttachedToWindow())return;
        for(int i=start;i<end;i++){
            LinearLayout card=mediaCard(activity,colors,items.get(i),false,arabic,refresh);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(activity,COMPLETED_ROW_HEIGHT_DP));
            cp.bottomMargin=dp(activity,4);
            host.addView(card,cp);
        }
        if(end<items.size()){
            int next=Math.min(items.size(),end+APPEND_CARD_COUNT);
            host.postOnAnimation(()->appendListBatch(activity,host,colors,items,
                    arabic,refresh,requestToken,end,next));
        }
    }

    private static void appendGridBatch(Activity activity,LinearLayout host,ProTechV6Theme.Tokens colors,
            List<ProTechV6MediaRepository.MediaItem> items,boolean arabic,Runnable refresh,
            Object requestToken,int start,int end){
        if(host.getTag()!=requestToken||!host.isAttachedToWindow())return;
        for(int i=start;i<end;i+=2){
            LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            for(int j=i;j<Math.min(i+2,items.size());j++){
                LinearLayout card=mediaCard(activity,colors,items.get(j),true,arabic,refresh);
                card.setMinimumHeight(dp(activity,GRID_CARD_MIN_HEIGHT_DP));
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);
                cp.bottomMargin=dp(activity,6);if(j>i)cp.setMarginStart(dp(activity,7));row.addView(card,cp);
            }
            if(i+1>=items.size()){
                LinearLayout.LayoutParams empty=new LinearLayout.LayoutParams(0,1,1);
                empty.setMarginStart(dp(activity,7));
                row.addView(new View(activity),empty);
            }
            host.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        if(end<items.size()){
            int next=Math.min(items.size(),end+APPEND_CARD_COUNT);
            host.postOnAnimation(()->appendGridBatch(activity,host,colors,items,arabic,refresh,requestToken,end,next));
        }else if(items.size()>0 && activity instanceof ProTechV6PreviewActivity){
            TextView more=ProTechV6Theme.label(activity,
                    arabic?"عرض المزيد من ملفات الوسائط":"Load more media",13,colors.primary,true);
            more.setGravity(Gravity.CENTER);
            ProTechV6Theme.minimumTap(more,activity);
            more.setOnClickListener(v->((ProTechV6PreviewActivity)activity).loadMoreMedia());
            host.addView(more,new LinearLayout.LayoutParams(-1,dp(activity,52)));
        }
    }
    private static LinearLayout mediaCard(Activity a,ProTechV6Theme.Tokens p,
            ProTechV6MediaRepository.MediaItem item,boolean grid,boolean ar,Runnable refresh){
        LinearLayout box=new LinearLayout(a);
        box.setOrientation(grid?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        if(!grid)box.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int padding=dp(a,grid?4:6);
        box.setPadding(padding,padding,padding,padding);
        box.setBackground(ProTechV6Theme.rounded(a,p.card,12,p.stroke));
        box.setContentDescription(item.filename);
        box.setOnClickListener(v->open(a,item));
        box.setOnLongClickListener(v->{
            ProTechV6MediaActions.show(a,item,()->{
                invalidateSnapshot();
                refresh.run();
            });
            return true;
        });
        ImageView thumbnail=new ImageView(a);
        final Object thumbnailToken=new Object();
        thumbnail.setTag(thumbnailToken);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackground(ProTechV6Theme.rounded(a,p.input,10,p.stroke));
        thumbnail.setClipToOutline(true);
        FrameLayout mediaVisual=new FrameLayout(a);
        mediaVisual.addView(thumbnail,new FrameLayout.LayoutParams(-1,-1));
        String thumbnailKey=item.uri.toString()+"@"+THUMBNAIL_MAX_PX;
        Bitmap cached=THUMBNAILS.get(thumbnailKey);
        if(cached!=null&&!cached.isRecycled())setThumbnailIfChanged(thumbnail,cached);
        if(item.audio()){
            GradientDrawable audioBackground=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xff7654f7,0xff1677ed});
            audioBackground.setCornerRadius(dp(a,10));mediaVisual.setBackground(audioBackground);
            int glyphSize=dp(a,grid?40:30);
            mediaVisual.addView(new MusicArtworkView(a,Color.WHITE),
                    new FrameLayout.LayoutParams(glyphSize,glyphSize,Gravity.CENTER));
        }
        if(item.duration>0){
            TextView duration=ProTechV6Theme.label(a,formatDuration(item.duration),10,Color.WHITE,true);
            duration.setGravity(Gravity.CENTER);
            duration.setBackground(ProTechV6Theme.rounded(a,0xbb000000,6,Color.TRANSPARENT));
            FrameLayout.LayoutParams durationParams=new FrameLayout.LayoutParams(-2,dp(a,20),Gravity.BOTTOM|Gravity.END);
            durationParams.setMargins(0,0,dp(a,4),dp(a,4));
            mediaVisual.addView(duration,durationParams);
        }
        TextView more=ProTechV6Theme.label(a,"⋮",22,Color.WHITE,true);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription((ar?"إجراءات الملف: ":"File actions: ")+item.filename);
        more.setBackground(ProTechV6Theme.rounded(a,0x88000000,10,Color.TRANSPARENT));
        FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(dp(a,48),dp(a,48),
                Gravity.TOP|Gravity.END);
        mp.setMargins(0,dp(a,3),dp(a,3),0);
        mediaVisual.addView(more,mp);
        more.setOnClickListener(v->ProTechV6MediaActions.show(a,item,()->{
            invalidateSnapshot();refresh.run();
        }));
        box.addView(mediaVisual,new LinearLayout.LayoutParams(grid?-1:dp(a,COMPLETED_THUMB_DP),dp(a,grid?GRID_VISUAL_HEIGHT_DP:COMPLETED_THUMB_DP)));
        Future<?> thumbnailFuture=null;
        if(cached==null&&item.video()&&Build.VERSION.SDK_INT>=29){
            ProTechV6StartupTrace.mark("thumbnail_decode_scheduled");
            thumbnailFuture=RuntimeExecutors.io().submit(()->{
                long decodeStarted=android.os.SystemClock.elapsedRealtime();
                try{
                    if(Thread.currentThread().isInterrupted())return;
                    Bitmap image=a.getContentResolver().loadThumbnail(item.uri,
                            new Size(THUMBNAIL_MAX_PX,THUMBNAIL_MAX_PX),null);
                    if(image.getWidth()>THUMBNAIL_MAX_PX||image.getHeight()>THUMBNAIL_MAX_PX){
                        Bitmap scaled=scaleToBounds(image,THUMBNAIL_MAX_PX);
                        if(scaled!=image)image.recycle();
                        image=scaled;
                    }
                    image=convertOpaqueToRgb565(image);
                    if(Thread.currentThread().isInterrupted()){image.recycle();return;}
                    THUMBNAILS.put(thumbnailKey,image);
                    ProTechV6StartupTrace.mark("thumbnail_decode_done",
                            android.os.SystemClock.elapsedRealtime()-decodeStarted,1);
                    Bitmap ready=image;
                    a.runOnUiThread(()->{
                        if(!a.isFinishing()&&!a.isDestroyed()&&thumbnail.isAttachedToWindow()
                                &&thumbnail.getTag()==thumbnailToken)setThumbnailIfChanged(thumbnail,ready);
                    });
                }catch(Exception ignored){}
            });
        }else if(cached==null&&item.audio()){
            ProTechV6StartupTrace.mark("thumbnail_decode_scheduled");
            thumbnailFuture=loadEmbeddedAudioArtwork(a,item,thumbnail,thumbnailToken,thumbnailKey);
        }
        if(thumbnailFuture!=null){
            Future<?> finalThumbnailFuture=thumbnailFuture;
            thumbnail.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                public void onViewAttachedToWindow(View view){}
                public void onViewDetachedFromWindow(View view){
                    thumbnail.setTag(new Object());
                    finalThumbnailFuture.cancel(true);
                    thumbnail.removeOnAttachStateChangeListener(this);
                }
            });
        }
        LinearLayout labels=new LinearLayout(a);labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(a,grid?0:7),dp(a,grid?3:0),0,0);
        labels.setLayoutDirection(ar?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
        String display=(LibraryCollections.isFavorite(a,item.uri)?"★  ":"")+item.filename;
        TextView title=ProTechV6Theme.label(a,display,grid?12:13,p.text,true);
        if(grid)title.setTextSize(12);
        title.setTextDirection(ar?View.TEXT_DIRECTION_RTL:View.TEXT_DIRECTION_LTR);
        title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if(!grid)title.setMaxLines(1);
        if(Build.VERSION.SDK_INT>=26)title.setTooltipText(item.filename);
        labels.addView(title,new LinearLayout.LayoutParams(-1,-2));
        TextView details=ProTechV6Theme.label(a,
            (ar?(item.audio()?"صوت":"فيديو"):(item.audio()?"Audio":"Video"))+" · "+pretty(item.bytes),grid?10:10,p.muted,false);
        details.setTextDirection(ar?View.TEXT_DIRECTION_RTL:View.TEXT_DIRECTION_LTR);
        if(grid)details.setTextSize(10);
        details.setSingleLine(true);details.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(-1,-2);dl.topMargin=dp(a,2);
        labels.addView(details,dl);
        // Allow two-line names and accessibility font scaling to grow without clipping.
        if(grid)labels.setMinimumHeight(dp(a,GRID_TEXT_REGION_HEIGHT_DP));
        box.addView(labels,grid?new LinearLayout.LayoutParams(-1,-2):
                new LinearLayout.LayoutParams(0,-2,1));
        return box;
    }
    private static Future<?> loadEmbeddedAudioArtwork(Activity a,ProTechV6MediaRepository.MediaItem item,
            ImageView thumbnail,Object thumbnailToken,String thumbnailKey){
        return RuntimeExecutors.io().submit(()->{
            long decodeStarted=android.os.SystemClock.elapsedRealtime();
            MediaMetadataRetriever retriever=new MediaMetadataRetriever();
            try{
                if(Thread.currentThread().isInterrupted())return;
                retriever.setDataSource(a,item.uri);byte[] bytes=retriever.getEmbeddedPicture();
                if(bytes==null||bytes.length==0||bytes.length>8*1024*1024)return;
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                BitmapFactory.Options options=new BitmapFactory.Options();
                options.inSampleSize=calculateInSampleSize(bounds,THUMBNAIL_MAX_PX,THUMBNAIL_MAX_PX);
                options.inPreferredConfig=Bitmap.Config.RGB_565;
                Bitmap art=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(art==null)return;
                if(art.getWidth()>THUMBNAIL_MAX_PX||art.getHeight()>THUMBNAIL_MAX_PX){
                    Bitmap scaled=scaleToBounds(art,THUMBNAIL_MAX_PX);
                    if(scaled!=art)art.recycle();
                    art=scaled;
                }
                if(Thread.currentThread().isInterrupted()){art.recycle();return;}
                THUMBNAILS.put(thumbnailKey,art);
                ProTechV6StartupTrace.mark("thumbnail_decode_done",
                        android.os.SystemClock.elapsedRealtime()-decodeStarted,1);
                Bitmap ready=art;
                a.runOnUiThread(()->{if(!a.isFinishing()&&!a.isDestroyed()&&thumbnail.isAttachedToWindow()
                        &&thumbnail.getTag()==thumbnailToken)setThumbnailIfChanged(thumbnail,ready);});
            }catch(Exception ignored){}finally{try{retriever.release();}catch(Exception ignored){}}
        });
    }
    private static int calculateInSampleSize(BitmapFactory.Options bounds,int maxWidth,int maxHeight){
        int sample=1;
        while(bounds.outWidth/sample>maxWidth*2||bounds.outHeight/sample>maxHeight*2)sample*=2;
        return Math.max(1,sample);
    }
    private static Bitmap scaleToBounds(Bitmap image,int maxPx){
        int width=image.getWidth(),height=image.getHeight();
        if(width<=maxPx&&height<=maxPx)return image;
        float scale=Math.min(maxPx/(float)width,maxPx/(float)height);
        return Bitmap.createScaledBitmap(image,Math.max(1,Math.round(width*scale)),
                Math.max(1,Math.round(height*scale)),true);
    }
    private static Bitmap convertOpaqueToRgb565(Bitmap image){
        if(image.hasAlpha()||image.getConfig()==Bitmap.Config.RGB_565)return image;
        Bitmap compact=image.copy(Bitmap.Config.RGB_565,false);
        if(compact!=null&&compact!=image){image.recycle();return compact;}
        return image;
    }
    private static void setThumbnailIfChanged(ImageView view,Bitmap image){
        if(view.getDrawable() instanceof BitmapDrawable
                &&((BitmapDrawable)view.getDrawable()).getBitmap()==image)return;
        view.setImageBitmap(image);
    }
    public static void invalidateSnapshot(){
        synchronized(SNAPSHOT_LOCK){cachedSnapshot=Collections.emptyList();snapshotLoadedAtMs=0L;}
        SNAPSHOT_POLICY.invalidate();
    }
    private static String pretty(long size){
        return size<1048576?String.format(Locale.US,"%.1f KB",size/1024d):
                String.format(Locale.US,"%.1f MB",size/1048576d);
    }
    private static String formatDuration(long milliseconds){
        long total=Math.max(0L,milliseconds/1000L);
        return String.format(Locale.US,"%d:%02d",total/60L,total%60L);
    }
    private static void open(Activity a,ProTechV6MediaRepository.MediaItem item){
        try{
            Intent player=new Intent(a,PlayerActivity.class);
            player.setDataAndType(item.uri,item.mime);
            player.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(player);
        }catch(Exception e){
            android.widget.Toast.makeText(a,
                    "Unable to open this media file",android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
