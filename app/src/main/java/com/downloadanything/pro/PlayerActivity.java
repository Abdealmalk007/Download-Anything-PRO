package com.downloadanything.pro;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.util.Rational;
import android.view.*;
import android.widget.*;

/**
 * 4.7.0 Fullscreen Player 2.0.
 * Edge-to-edge video, touch-first controls, resume, gestures, speed, rotation,
 * fit/fill, and optional PiP mini-player. Audio behavior is retained.
 */
public final class PlayerActivity extends Activity {
    private static final int VIDEO_TOP_HEIGHT_DP = 56;
    private static final int VIDEO_BOTTOM_HEIGHT_DP = 132;
    private static final int AUDIO_ARTWORK_DP = 138;
    private static final int AUDIO_ARTWORK_MAX_DP = 148;
    private static final int AUDIO_WAVEFORM_DP = 46;
    private static final int AUDIO_PRIMARY_DP = 68;
    private static final int AUDIO_SECONDARY_DP = 52;
    boolean choosing;
    private Uri uri;
    private String mime="application/octet-stream",name="";
    private boolean audio,launchingExternal,backgroundHandoff,closeRequested;

    private final Handler handler=new Handler(Looper.getMainLooper());
    private VideoView video;
    private MediaPlayer videoPlayer;
    private FrameLayout videoRoot,videoHolder;
    private LinearLayout topBar,bottomBar;
    private SeekBar videoSeek,audioSeek;
    private Button audioFavoriteButton;
    private TextView videoTime,audioTime,gestureHud,metaText,audioArtist;
    private ImageView audioArtwork;
    private Button playPause,toggle,speedButton,scaleButton,orientationButton,repeatButton,shuffleButton;
    private boolean controlsVisible=true,videoPrepared=false,seekingByUser=false,fillMode=false;
    private float playbackSpeed=1.0f;
    private int videoW=0,videoH=0;
    private GestureDetector gestures;
    private AudioManager audioManager;
    private int gestureStartVolume=0,gestureStartPosition=0;
    private float gestureStartBrightness=0.5f;
    private int gestureMode=0; // 0 unknown, 1 seek, 2 brightness, 3 volume

    private final Runnable hideControls=()->setControlsVisible(false);
    private final Runnable ticker=new Runnable(){
        @Override public void run(){
            if(audio) updateAudioUi(); else updateVideoUi();
            handler.postDelayed(this,500L);
        }
    };

    public static boolean open(Context c,Uri uri,String mime,String displayName){
        if(c==null||uri==null)return false;
        try{
            Intent i=new Intent(c,PlayerActivity.class)
                    .setDataAndType(uri,mime==null?"application/octet-stream":mime)
                    .putExtra("displayName",displayName)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try{i.setClipData(ClipData.newRawUri("media",uri));}catch(Exception ignored){}
            if(!(c instanceof Activity))i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        }catch(Exception e){return false;}
    }

    private boolean restoringPreviousActivity;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        restoringPreviousActivity=state!=null;
        if(!bindIncomingMedia(getIntent())){
            Toast.makeText(this,UiPrefs.t(this,"تعذر فتح ملف الوسائط","Unable to open media file"),Toast.LENGTH_SHORT).show();
            finish();return;
        }
        PlaybackQueueStore.select(this,uri);
        RecentPlaybackStore.touch(this,uri);
        if(audio) buildAudio(); else {
            applyPreferredOrientation();
            buildVideo();
        }
    }

    /** 4.8.3: accept Android Open-with (ACTION_VIEW) and media share (ACTION_SEND). */
    private boolean bindIncomingMedia(Intent incoming){
        if(incoming==null)return false;
        Uri value=incoming.getData();
        if(value==null&&Intent.ACTION_SEND.equals(incoming.getAction())){
            try{
                if(Build.VERSION.SDK_INT>=33)value=incoming.getParcelableExtra(Intent.EXTRA_STREAM,Uri.class);
                else value=(Uri)incoming.getParcelableExtra(Intent.EXTRA_STREAM);
            }catch(Exception ignored){}
        }
        if(value==null)return false;
        uri=value;
        try{
            int flags=incoming.getFlags();
            if("content".equalsIgnoreCase(uri.getScheme()) && (flags&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0){
                int take=flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if((take&Intent.FLAG_GRANT_READ_URI_PERMISSION)==0)take|=Intent.FLAG_GRANT_READ_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(uri,take);
            }
        }catch(Exception ignored){}

        name=incoming.getStringExtra("displayName");
        if(name==null||name.trim().isEmpty())name=resolveDisplayName(uri);
        if(name==null)name="";
        mime=ExternalMediaLauncher.resolveMime(this,uri,incoming.getType(),name);
        audio=isAudio(mime,name);
        return true;
    }

    private String resolveDisplayName(Uri value){
        if(value==null)return "";
        if("content".equalsIgnoreCase(value.getScheme())){
            try(Cursor cursor=getContentResolver().query(value,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
                if(cursor!=null&&cursor.moveToFirst()){
                    int i=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if(i>=0){String n=cursor.getString(i);if(n!=null&&!n.trim().isEmpty())return n;}
                }
            }catch(Exception ignored){}
        }
        String last=value.getLastPathSegment();
        return last==null?"":last;
    }

    private void applyPreferredOrientation(){
        String pref=UiPrefs.playerOrientation(this);
        if("landscape".equals(pref)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        else if("portrait".equals(pref)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        else setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void buildVideo(){
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);

        audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);

        videoRoot=new FrameLayout(this);
        videoRoot.setBackgroundColor(Color.BLACK);

        videoHolder=new FrameLayout(this);
        videoHolder.setBackgroundColor(Color.BLACK);
        video=new VideoView(this);
        video.setVideoURI(uri);
        videoHolder.addView(video,new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT,Gravity.CENTER));
        videoRoot.addView(videoHolder,new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));

        buildVideoTopControls();
        buildVideoBottomControls();

        gestureHud=text("");
        gestureHud.setTextSize(17);
        gestureHud.setGravity(Gravity.CENTER);
        gestureHud.setBackgroundColor(0xaa000000);
        gestureHud.setVisibility(View.GONE);
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(dp(190),dp(56),Gravity.CENTER);
        videoRoot.addView(gestureHud,hp);

        UiSafeArea.apply(this,videoRoot);
        setContentView(videoRoot);
        installVideoGestures();
        applyImmersive();

        video.setOnPreparedListener(mp->{
            videoPlayer=mp;
            videoPrepared=true;
            videoW=mp.getVideoWidth();
            videoH=mp.getVideoHeight();
            metaText.setText(videoW>0&&videoH>0 ? videoW+"×"+videoH : "");
            int pos=UiPrefs.playerResume(this)?PlaybackStateStore.get(this,uri):0;
            if(pos>5000&&pos<video.getDuration()-2000){
                video.seekTo(pos);
                Toast.makeText(this,UiPrefs.t(this,"استكمال من "+time(pos),"Resume from "+time(pos)),Toast.LENGTH_SHORT).show();
            }
            applyPlaybackSpeed();
            video.start();
            updatePlayPause();
            videoHolder.post(()->{applyScaleMode();updatePipParams();});
            showControlsTemporarily();
        });
        video.setOnCompletionListener(mp->{
            PlaybackStateStore.clear(this,uri);
            if(PlaybackQueueStore.repeatOne(this)||PlaybackQueueStore.size(this)>1){advanceQueue(1);return;}
            updatePlayPause();
            setControlsVisible(true);
        });
        video.setOnErrorListener((mp,what,extra)->{
            Toast.makeText(this,UiPrefs.t(this,"تعذر تشغيل الملف","Could not play this file"),Toast.LENGTH_SHORT).show();
            setControlsVisible(true);
            return false;
        });
        handler.post(ticker);
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void buildVideoTopControls(){
        topBar=new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8),dp(7),dp(8),dp(7));
        topBar.setBackgroundColor(0x88000000);

        Button close=videoIconButton("\u2039",26);
        close.setContentDescription(UiPrefs.t(this,"إغلاق المشغل","Close player"));

        LinearLayout titleBox=new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title=text(name.isEmpty()?UiPrefs.t(this,"فيديو","Video"):name);
        title.setTextSize(15);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        metaText=text("");
        metaText.setTextSize(11);
        metaText.setTextColor(0xffc3c7ce);
        titleBox.addView(title);
        titleBox.addView(metaText);

        Button more=videoIconButton("\u22ee",24);
        more.setContentDescription(UiPrefs.t(this,"المزيد","More"));

        topBar.addView(close,new LinearLayout.LayoutParams(dp(50),dp(48)));
        topBar.addView(titleBox,new LinearLayout.LayoutParams(0,dp(52),1));
        topBar.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));

        FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,dp(VIDEO_TOP_HEIGHT_DP),Gravity.TOP);
        videoRoot.addView(topBar,tp);

        close.setOnClickListener(v->closePlayer());
        more.setOnClickListener(v->showVideoOverflow());
    }

    private void buildVideoBottomControls(){
        bottomBar=new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(12),dp(4),dp(12),dp(8));
        bottomBar.setBackgroundColor(0x99000000);

        LinearLayout seekRow=new LinearLayout(this);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        videoTime=text("00:00 / 00:00");
        videoTime.setTextSize(11);
        videoSeek=new SeekBar(this);
        videoSeek.setMax(1000);
        if(Build.VERSION.SDK_INT>=21){videoSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff1689ee));videoSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));}
        seekRow.addView(videoSeek,new LinearLayout.LayoutParams(0,dp(34),1));
        seekRow.addView(videoTime,new LinearLayout.LayoutParams(dp(96),dp(34)));
        bottomBar.addView(seekRow,new LinearLayout.LayoutParams(-1,dp(34)));

        LinearLayout controls=new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        Button back=videoIconButton("\u21b6 10",16);
        playPause=videoIconButton("\u25b6",24);
        playPause.setBackground(ProTechV6Theme.rounded(this,0x553b82f6,26,0x99ffffff));
        Button forward=videoIconButton("10 \u21b7",16);

        controls.addView(back,new LinearLayout.LayoutParams(0,dp(52),1));
        controls.addView(playPause,new LinearLayout.LayoutParams(dp(56),dp(52)));
        controls.addView(forward,new LinearLayout.LayoutParams(0,dp(52),1));
        bottomBar.addView(controls,new LinearLayout.LayoutParams(-1,dp(54)));

        LinearLayout queueRow=new LinearLayout(this);queueRow.setGravity(Gravity.CENTER);
        Button previous=videoIconButton("⏮",18);speedButton=videoIconButton("1×",14);orientationButton=videoIconButton("⛶",20);Button next=videoIconButton("⏭",18);
        previous.setContentDescription(UiPrefs.t(this,"السابق","Previous"));next.setContentDescription(UiPrefs.t(this,"التالي","Next"));orientationButton.setContentDescription(UiPrefs.t(this,"ملء الشاشة","Fullscreen"));
        queueRow.addView(previous,new LinearLayout.LayoutParams(0,dp(38),1));queueRow.addView(speedButton,new LinearLayout.LayoutParams(0,dp(38),1));queueRow.addView(orientationButton,new LinearLayout.LayoutParams(0,dp(38),1));queueRow.addView(next,new LinearLayout.LayoutParams(0,dp(38),1));
        bottomBar.addView(queueRow,new LinearLayout.LayoutParams(-1,dp(38)));

        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,dp(VIDEO_BOTTOM_HEIGHT_DP),Gravity.BOTTOM);
        videoRoot.addView(bottomBar,bp);

        back.setOnClickListener(v->seekBy(-10000));
        forward.setOnClickListener(v->seekBy(10000));
        playPause.setOnClickListener(v->toggleVideo());
        speedButton.setOnClickListener(v->cycleSpeed());
        orientationButton.setOnClickListener(v->toggleOrientation());
        previous.setOnClickListener(v->advanceQueue(-1));next.setOnClickListener(v->advanceQueue(1));

        videoSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar s){seekingByUser=true;handler.removeCallbacks(hideControls);}
            @Override public void onStopTrackingTouch(SeekBar s){
                if(video!=null&&videoPrepared){
                    int d=video.getDuration();
                    int target=d<=0?0:(int)((long)d*s.getProgress()/1000L);
                    video.seekTo(target);
                    PlaybackStateStore.put(PlayerActivity.this,uri,target);
                }
                seekingByUser=false;
                showControlsTemporarily();
            }
            @Override public void onProgressChanged(SeekBar s,int p,boolean fromUser){
                if(fromUser&&videoPrepared){
                    int d=video.getDuration();
                    int target=d<=0?0:(int)((long)d*p/1000L);
                    videoTime.setText(time(target)+" / "+time(d));
                }
            }
        });
    }

    private void showVideoOverflow(){
        String[] items={UiPrefs.t(this,"ملاءمة / ملء الفيديو","Fit / fill video"),UiPrefs.t(this,"المشغل المتقدم","Advanced player"),UiPrefs.t(this,"فتح بمشغل خارجي","Open external player")};
        new AlertDialog.Builder(this).setItems(items,(d,which)->{
            if(which==0){fillMode=!fillMode;applyScaleMode();showControlsTemporarily();return;}
            if(which==1&&uri!=null){
                try{Intent i=new Intent(this,Media3PlayerActivity.class).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);i.setClipData(ClipData.newRawUri("media",uri));video.pause();startActivity(i);}catch(Exception ignored){}
                return;
            }
            launchingExternal=true;ExternalMediaLauncher.open(this,uri,mime,name,UiPrefs.isArabic(this));
        }).show();
    }

    private void installVideoGestures(){
        gestures=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){
                gestureMode=0;
                if(videoRoot!=null)videoRoot.setTag(null);
                gestureStartPosition=(video!=null&&videoPrepared)?video.getCurrentPosition():0;
                gestureStartVolume=audioManager==null?0:audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                float b=getWindow().getAttributes().screenBrightness;
                gestureStartBrightness=b<0?0.5f:b;
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e){
                toggleControls();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e){
                if(!UiPrefs.playerDoubleTap(PlayerActivity.this))return true;
                float w=Math.max(1,videoRoot.getWidth());
                if(e.getX()<w/3f)seekBy(-10000);
                else if(e.getX()>w*2f/3f)seekBy(10000);
                else toggleVideo();
                showControlsTemporarily();
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1,MotionEvent e2,float distanceX,float distanceY){
                if(e1==null||e2==null)return false;
                float dx=e2.getX()-e1.getX(),dy=e2.getY()-e1.getY();
                if(gestureMode==0){
                    if(Math.abs(dx)<dp(22)&&Math.abs(dy)<dp(22))return true;
                    if(Math.abs(dx)>Math.abs(dy))gestureMode=1;
                    else gestureMode=e1.getX()<videoRoot.getWidth()/2f?2:3;
                }
                if(gestureMode==1){
                    updateSeekTarget(e1,e2);
                }else if(gestureMode==2){
                    float value=Math.max(0.05f,Math.min(1f,gestureStartBrightness-dy/Math.max(1f,videoRoot.getHeight())));
                    WindowManager.LayoutParams lp=getWindow().getAttributes();
                    lp.screenBrightness=value;
                    getWindow().setAttributes(lp);
                    showGesture(UiPrefs.t(PlayerActivity.this,"السطوع ","Brightness ")+(int)(value*100)+"%");
                }else if(gestureMode==3&&audioManager!=null){
                    int max=audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int v=Math.max(0,Math.min(max,gestureStartVolume+(int)(-dy/Math.max(1f,videoRoot.getHeight())*max)));
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,v,0);
                    showGesture(UiPrefs.t(PlayerActivity.this,"الصوت ","Volume ")+(max==0?0:(v*100/max))+"%");
                }
                return true;
            }
        });

        videoRoot.setOnTouchListener((v,e)->{
            boolean handled=gestures.onTouchEvent(e);
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                if(e.getActionMasked()==MotionEvent.ACTION_UP)v.performClick();
                if(gestureMode==1&&videoPrepared){
                    MotionEvent down=null; // target is recomputed from current event relative to initial gesture through last HUD text below.
                    // Seek by the final horizontal distance using GestureDetector state is intentionally avoided;
                    // onScroll already displayed the preview, so derive from velocity-independent raw distance kept in tag.
                    Object tag=videoRoot.getTag();
                    if(tag instanceof Integer)video.seekTo((Integer)tag);
                }
                gestureMode=0;
                handler.postDelayed(()->gestureHud.setVisibility(View.GONE),350L);
            }
            return handled;
        });

        // A second lightweight listener tracks the final horizontal seek target without
        // interfering with GestureDetector's tap/double-tap recognition.
        videoRoot.setOnGenericMotionListener((v,e)->false);
    }

    private void showGesture(String s){
        if(gestureHud==null)return;
        gestureHud.setText(s);
        gestureHud.setVisibility(View.VISIBLE);
        if(gestureMode==1&&videoPrepared){
            // Parse-free target caching: derive again from the current touch data is not
            // available here, so horizontal seeking is applied continuously by updateSeekTarget().
        }
    }

    private void updateSeekTarget(MotionEvent e1,MotionEvent e2){
        if(!videoPrepared||e1==null||e2==null)return;
        int d=video.getDuration();
        if(d<=0)return;
        float dx=e2.getX()-e1.getX();
        int target=Math.max(0,Math.min(d,gestureStartPosition+(int)(dx/Math.max(1f,videoRoot.getWidth())*d)));
        videoRoot.setTag(Integer.valueOf(target));
        showGesture(UiPrefs.t(this,"تقديم/ترجيع ","Seek ")+time(target));
    }

    private void toggleVideo(){
        if(video==null||!videoPrepared)return;
        try{if(video.isPlaying())video.pause();else video.start();}catch(Exception ignored){}
        updatePlayPause();
        showControlsTemporarily();
    }

    private void seekBy(int delta){
        if(video==null||!videoPrepared)return;
        try{
            int d=video.getDuration();
            int p=Math.max(0,Math.min(d,video.getCurrentPosition()+delta));
            video.seekTo(p);
            PlaybackStateStore.put(this,uri,p);
            showGesture((delta>=0?"+":"")+delta/1000+"s");
            handler.postDelayed(()->gestureHud.setVisibility(View.GONE),450L);
        }catch(Exception ignored){}
        showControlsTemporarily();
    }

    private void cycleSpeed(){
        float[] speeds={1.0f,1.25f,1.5f,1.75f,2.0f,0.5f,0.75f};
        int next=0;
        for(int i=0;i<speeds.length;i++)if(Math.abs(speeds[i]-playbackSpeed)<0.01f){next=(i+1)%speeds.length;break;}
        playbackSpeed=speeds[next];
        applyPlaybackSpeed();
        speedButton.setText((playbackSpeed==(int)playbackSpeed?Integer.toString((int)playbackSpeed):Float.toString(playbackSpeed))+"x");
        showControlsTemporarily();
    }

    private void applyPlaybackSpeed(){
        if(videoPlayer==null)return;
        try{
            PlaybackParams p=videoPlayer.getPlaybackParams();
            p.setSpeed(playbackSpeed);
            videoPlayer.setPlaybackParams(p);
        }catch(Exception ignored){}
    }

    private void toggleOrientation(){
        int o=getResources().getConfiguration().orientation;
        if(o==Configuration.ORIENTATION_LANDSCAPE)setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        else setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        showControlsTemporarily();
    }

    private void applyScaleMode(){
        if(video==null||videoHolder==null)return;
        video.setScaleX(1f); video.setScaleY(1f);
        if(!fillMode)return;
        video.post(()->{
            int vw=video.getWidth(),vh=video.getHeight(),hw=videoHolder.getWidth(),hh=videoHolder.getHeight();
            if(vw<=0||vh<=0||hw<=0||hh<=0)return;
            float scale=Math.max((float)hw/vw,(float)hh/vh);
            if(scale<1f)scale=1f;
            video.setScaleX(scale);video.setScaleY(scale);
        });
    }

    private void updateVideoUi(){
        if(audio||video==null||!videoPrepared)return;
        try{
            int d=video.getDuration(),p=video.getCurrentPosition();
            if(!seekingByUser&&videoSeek!=null&&d>0)videoSeek.setProgress((int)Math.min(1000L,(long)p*1000L/d));
            if(videoTime!=null)videoTime.setText(time(p)+" / "+time(d));
            updatePlayPause();
            if(UiPrefs.playerResume(this)&&uri!=null&&p>1000)PlaybackStateStore.put(this,uri,p);
        }catch(Exception ignored){}
    }

    private void updatePlayPause(){
        if(playPause==null||video==null)return;
        boolean playing=false;
        try{playing=video.isPlaying();}catch(Exception ignored){}
        playPause.setText(playing?"||":"\u25B6");
    }

    private void toggleControls(){
        setControlsVisible(!controlsVisible);
        if(controlsVisible)showControlsTemporarily();
    }

    private void showControlsTemporarily(){
        setControlsVisible(true);
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls,3000L);
    }

    private void setControlsVisible(boolean show){
        controlsVisible=show;
        if(topBar!=null)topBar.setVisibility(show?View.VISIBLE:View.GONE);
        if(bottomBar!=null)bottomBar.setVisibility(show?View.VISIBLE:View.GONE);
        applyImmersive();
    }

    private void applyImmersive(){
        View decor=getWindow().getDecorView();
        int flags=View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                |View.SYSTEM_UI_FLAG_FULLSCREEN
                |View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                |View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                |View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                |View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        decor.setSystemUiVisibility(flags);
        if(Build.VERSION.SDK_INT>=30){
            WindowInsetsController c=decor.getWindowInsetsController();
            if(c!=null){
                c.hide(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    private void buildAudio(){
        final int navy=0xff060b16, panel=0xff111827, muted=0xffa7b0c0, purple=0xff7251f7;
        getWindow().setStatusBarColor(navy);
        getWindow().setNavigationBarColor(navy);
        ScrollView audioScroll=new ScrollView(this);
        audioScroll.setFillViewport(true);
        audioScroll.setClipToPadding(false);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(18),dp(8),dp(18),dp(16));
        root.setBackgroundColor(navy);

        LinearLayout audioTop=new LinearLayout(this);audioTop.setGravity(Gravity.CENTER_VERTICAL);
        Button closeAudio=button("‹");closeAudio.setTextSize(30);
        closeAudio.setContentDescription(UiPrefs.t(this,"إغلاق المشغل","Close player"));
        audioTop.addView(closeAudio,new LinearLayout.LayoutParams(dp(48),dp(44)));
        TextView audioHeader=text(UiPrefs.t(this,"مشغل الصوت","Audio player"));audioHeader.setGravity(Gravity.CENTER);audioHeader.setTextSize(15);
        audioTop.addView(audioHeader,new LinearLayout.LayoutParams(0,dp(44),1));
        Button moreAudio=button("⋮");
        audioTop.addView(moreAudio,new LinearLayout.LayoutParams(dp(48),dp(44)));
        root.addView(audioTop,new LinearLayout.LayoutParams(-1,dp(44)));
        closeAudio.setOnClickListener(v->closePlayer());

        addFlexibleAudioSpace(root,.20f);

        FrameLayout artworkFrame=new FrameLayout(this);
        android.graphics.drawable.GradientDrawable artBg=new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xff6f4df6,0xff1689ee});
        artBg.setCornerRadius(dp(18));artworkFrame.setBackground(artBg);artworkFrame.setClipToOutline(true);
        artworkFrame.addView(new MusicArtworkView(this,Color.WHITE),new FrameLayout.LayoutParams(-1,-1));
        audioArtwork=new ImageView(this);audioArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        audioArtwork.setImageDrawable(null);audioArtwork.setClipToOutline(true);artworkFrame.addView(audioArtwork,new FrameLayout.LayoutParams(-1,-1));
        int artworkDp=getResources().getConfiguration().screenHeightDp>=720?AUDIO_ARTWORK_MAX_DP:AUDIO_ARTWORK_DP;
        LinearLayout.LayoutParams artworkLp=new LinearLayout.LayoutParams(dp(artworkDp),dp(artworkDp));artworkLp.topMargin=dp(12);artworkLp.bottomMargin=dp(10);
        root.addView(artworkFrame,artworkLp);

        TextView title=text(name.isEmpty()?UiPrefs.t(this,"ملف صوتي","Audio file"):name);
        title.setTextSize(16);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        root.addView(title,new LinearLayout.LayoutParams(-1,-2));

        audioArtist=text(UiPrefs.t(this,"فنان غير معروف","Unknown artist"));
        audioArtist.setTextSize(13);audioArtist.setTextColor(muted);audioArtist.setGravity(Gravity.CENTER);
        root.addView(audioArtist,new LinearLayout.LayoutParams(-1,dp(28)));
        loadAudioMetadata();

        addFlexibleAudioSpace(root,.25f);

        AudioWaveformView waveform=new AudioWaveformView(this);
        waveform.setSource(uri);
        LinearLayout.LayoutParams waveLp=new LinearLayout.LayoutParams(-1,dp(AUDIO_WAVEFORM_DP));waveLp.topMargin=dp(8);root.addView(waveform,waveLp);

        audioSeek=new SeekBar(this);
        audioSeek.setMax(1000);
        if(Build.VERSION.SDK_INT>=21){audioSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(purple));audioSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(0xff55a7ff));}
        root.addView(audioSeek,new LinearLayout.LayoutParams(-1,dp(32)));

        audioTime=text("00:00 / 00:00");
        audioTime.setTextColor(muted);
        audioTime.setGravity(Gravity.CENTER);
        root.addView(audioTime,new LinearLayout.LayoutParams(-1,dp(26)));

        LinearLayout controls=new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        Button back=button("↶ 10");
        toggle=button("▶");
        toggle.setTextSize(27);toggle.setBackground(ProTechV6Theme.rounded(this,purple,30,Color.TRANSPARENT));
        Button forward=button("10 ↷");
        controls.addView(back,new LinearLayout.LayoutParams(0,dp(AUDIO_PRIMARY_DP),1));
        LinearLayout.LayoutParams playLp=new LinearLayout.LayoutParams(dp(AUDIO_PRIMARY_DP),dp(AUDIO_PRIMARY_DP));controls.addView(toggle,playLp);
        controls.addView(forward,new LinearLayout.LayoutParams(0,dp(AUDIO_PRIMARY_DP),1));
        root.addView(controls,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout queueControls=new LinearLayout(this);queueControls.setGravity(Gravity.CENTER);
        Button previous=button("|◀");shuffleButton=button(PlaybackQueueStore.shuffle(this)?"⇄ •":"⇄");repeatButton=button(repeatIcon());Button next=button("▶|");
        previous.setContentDescription(UiPrefs.t(this,"السابق","Previous"));next.setContentDescription(UiPrefs.t(this,"التالي","Next"));shuffleButton.setContentDescription(UiPrefs.t(this,"تشغيل عشوائي","Shuffle"));repeatButton.setContentDescription(UiPrefs.t(this,"تكرار الملف","Repeat one"));
        queueControls.addView(previous,new LinearLayout.LayoutParams(0,dp(46),1));queueControls.addView(shuffleButton,new LinearLayout.LayoutParams(0,dp(46),1));queueControls.addView(repeatButton,new LinearLayout.LayoutParams(0,dp(46),1));queueControls.addView(next,new LinearLayout.LayoutParams(0,dp(46),1));
        root.addView(queueControls,new LinearLayout.LayoutParams(-1,-2));
        TextView queueInfo=text(queuePositionLabel());queueInfo.setTextColor(muted);queueInfo.setGravity(Gravity.CENTER);root.addView(queueInfo,new LinearLayout.LayoutParams(-1,dp(30)));
        addFlexibleAudioSpace(root,.55f);

        audioFavoriteButton=button(LibraryCollections.isFavorite(this,uri)?"♥":"♡");
        audioFavoriteButton.setTextSize(29f);
        audioFavoriteButton.setTextColor(LibraryCollections.isFavorite(this,uri)?0xffff547e:Color.WHITE);
        audioFavoriteButton.setContentDescription(UiPrefs.t(this,
                "إضافة أو إزالة المقطع من المفضلة","Add or remove this audio from favorites"));
        audioFavoriteButton.setBackground(ProTechV6Theme.rounded(this,panel,12,0xff26334c));
        LinearLayout.LayoutParams heartLp=new LinearLayout.LayoutParams(dp(56),dp(52));
        heartLp.gravity=Gravity.CENTER;
        root.addView(audioFavoriteButton,heartLp);
        audioScroll.addView(root);
        UiSafeArea.apply(this,audioScroll);
        setContentView(audioScroll);

        // Explicit file selection may start audio. Activity restoration or opening
        // the player notification must never resume it without pressing Play.
        boolean restoreOnly=restoringPreviousActivity || getIntent().getBooleanExtra("restoreOnly",false);
        if(!restoreOnly)
            AudioPlaybackService.play(this,uri,mime,name);
        back.setOnClickListener(v->AudioPlaybackService.command(this,AudioPlaybackService.ACTION_BACK));
        forward.setOnClickListener(v->AudioPlaybackService.command(this,AudioPlaybackService.ACTION_FORWARD));
        toggle.setOnClickListener(v->{
            if(AudioPlaybackService.isCurrent(uri))
                AudioPlaybackService.command(this,AudioPlaybackService.ACTION_TOGGLE);
            else AudioPlaybackService.play(this,uri,mime,name);
        });
        previous.setOnClickListener(v->advanceQueue(-1));next.setOnClickListener(v->advanceQueue(1));
        shuffleButton.setOnClickListener(v->{boolean n=!PlaybackQueueStore.shuffle(this);PlaybackQueueStore.setShuffle(this,n);shuffleButton.setText(n?"⇄ •":"⇄");});
        repeatButton.setOnClickListener(v->{PlaybackQueueStore.cycleRepeatMode(this);repeatButton.setText(repeatIcon());});
        audioFavoriteButton.setOnClickListener(v->{boolean n=!LibraryCollections.isFavorite(this,uri);LibraryCollections.setFavorite(this,uri,n);audioFavoriteButton.setText(n?"♥":"♡");audioFavoriteButton.setTextColor(n?0xffff547e:Color.WHITE);Toast.makeText(this,n?UiPrefs.t(this,"تمت الإضافة لمفضلة الموسيقى","Added to music favorites"):UiPrefs.t(this,"تمت الإزالة من مفضلة الموسيقى","Removed from music favorites"),Toast.LENGTH_SHORT).show();});
        moreAudio.setOnClickListener(v->showAudioOverflow(moreAudio));

        audioSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){
                int d=AudioPlaybackService.duration();
                int target=d<=0?0:(int)((long)d*s.getProgress()/1000L);
                AudioPlaybackService.seekTo(PlayerActivity.this,target);
            }
            public void onProgressChanged(SeekBar s,int p,boolean fromUser){}
        });
        handler.post(ticker);
    }

    private void addFlexibleAudioSpace(LinearLayout root,float weight){
        View space=new View(this);
        root.addView(space,new LinearLayout.LayoutParams(1,0,weight));
    }

    private void showAudioOverflow(Button anchor){
        boolean favorite=LibraryCollections.isFavorite(this,uri);
        String[] items={favorite?UiPrefs.t(this,"إزالة من المفضلة","Remove favorite"):UiPrefs.t(this,"إضافة للمفضلة","Add favorite"),UiPrefs.t(this,"مؤقت النوم","Sleep timer"),UiPrefs.t(this,"مضخم الصوت","Audio booster")+" +"+AudioPlaybackService.boostDb(this)+" dB",UiPrefs.t(this,"فتح بمشغل خارجي","Open external player")};
        new AlertDialog.Builder(this).setItems(items,(d,which)->{
            if(which==0){boolean n=!LibraryCollections.isFavorite(this,uri);LibraryCollections.setFavorite(this,uri,n);return;}
            if(which==1){showSleepTimer(anchor);return;}
            if(which==2){showAudioBoost();return;}
            launchingExternal=true;ExternalMediaLauncher.open(this,uri,mime,name,UiPrefs.isArabic(this));
        }).show();
    }

    private void showAudioBoost(){
        final int[] values={0,3,6,9};
        final String[] options={"Off / إيقاف","Low / منخفض +3 dB","Medium / متوسط +6 dB","High / مرتفع +9 dB"};
        new AlertDialog.Builder(this).setTitle(UiPrefs.t(this,"مضخم الصوت - قد يؤدي التضخيم المرتفع إلى التشويش","Audio boost - high gain may distort sound"))
                .setSingleChoiceItems(options,-1,(dialog,which)->{AudioPlaybackService.setBoost(this,values[which]);dialog.dismiss();})
                .setNegativeButton(UiPrefs.t(this,"إلغاء","Cancel"),null).show();
    }


    private void showSleepTimer(Button button){
        final int[] values={0,15,30,60,90};
        String[] labels={UiPrefs.t(this,"إيقاف المؤقت","Off"),"15 min","30 min","60 min","90 min"};
        new AlertDialog.Builder(this).setTitle(UiPrefs.t(this,"مؤقت النوم","Sleep timer")).setItems(labels,(d,which)->{int m=values[which];AudioPlaybackService.setSleepTimer(this,m);button.setText(m<=0?UiPrefs.t(this,"🌙 مؤقت النوم","🌙 Sleep timer"):UiPrefs.t(this,"🌙 إيقاف بعد ","🌙 Stop after ")+m+" min");}).show();
    }

    private void loadAudioMetadata(){
        if(uri==null)return;
        try{
            MediaMetadataRetriever r=new MediaMetadataRetriever();
            r.setDataSource(this,uri);
            String artist=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if(artist==null||artist.trim().isEmpty())artist=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            if(audioArtist!=null&&artist!=null&&!artist.trim().isEmpty())audioArtist.setText(artist.trim());
            byte[] art=r.getEmbeddedPicture();
            if(art!=null&&art.length>0&&audioArtwork!=null){Bitmap bm=BitmapFactory.decodeByteArray(art,0,art.length);if(bm!=null)audioArtwork.setImageBitmap(bm);}
            r.release();
        }catch(Exception ignored){}
    }

    private void updateAudioUi(){
        if(!audio)return;
        int d=AudioPlaybackService.duration(),p=AudioPlaybackService.position();
        if(audioSeek!=null&&d>0)audioSeek.setProgress((int)Math.min(1000L,(long)p*1000L/d));
        if(audioTime!=null)audioTime.setText(time(p)+" / "+time(d));
        if(toggle!=null)toggle.setText(AudioPlaybackService.isPlayingNow()?"Ⅱ":"▶");
    }

    private String queuePositionLabel(){
        int size=PlaybackQueueStore.size(this),index=PlaybackQueueStore.index(this);
        if(size<=0||index<0)return UiPrefs.t(this,"قائمة التشغيل","Queue");
        return (index+1)+" / "+size;
    }

    private void advanceQueue(int delta){
        PlaybackQueueStore.Entry e=delta<0?PlaybackQueueStore.previous(this):PlaybackQueueStore.next(this);
        if(e==null||e.uri==null)return;
        if(e.uri.equals(uri)&&!PlaybackQueueStore.repeatOne(this)&&PlaybackQueueStore.size(this)<=1)return;
        switchToQueueEntry(e);
    }

    private void switchToQueueEntry(PlaybackQueueStore.Entry e){
        if(e==null||e.uri==null)return;
        if(!audio&&video!=null&&uri!=null&&UiPrefs.playerResume(this))try{PlaybackStateStore.put(this,uri,video.getCurrentPosition());}catch(Exception ignored){}
        Intent i=new Intent(this,PlayerActivity.class).setDataAndType(e.uri,e.mime).putExtra("displayName",e.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);finish();overridePendingTransition(0,0);
    }

    private String time(int ms){
        int s=Math.max(0,ms/1000);
        int h=s/3600,m=(s%3600)/60,sec=s%60;
        if(h>0)return String.format(java.util.Locale.getDefault(),"%d:%02d:%02d",h,m,sec);
        return String.format(java.util.Locale.getDefault(),"%02d:%02d",m,sec);
    }

    @Override protected void onPause(){
        super.onPause();
        if(!audio&&video!=null&&uri!=null){
            try{
                int pos=video.getCurrentPosition();
                if(UiPrefs.playerResume(this))PlaybackStateStore.put(this,uri,pos);
                boolean pip=Build.VERSION.SDK_INT>=24&&isInPictureInPictureMode();
                if(!pip&&UiPrefs.playerBackgroundAudio(this)&&videoPrepared&&video.isPlaying()&&!closeRequested&&!launchingExternal){
                    video.pause();
                    AudioPlaybackService.playAt(this,uri,mime,name,pos,"","");
                    backgroundHandoff=true;
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }else if(!pip)video.pause();
            }catch(Exception ignored){}
        }
    }

    @Override protected void onResume(){
        super.onResume();launchingExternal=false;
        if(!audio){
            applyImmersive();
            updatePipParams();
            if(backgroundHandoff&&AudioPlaybackService.isCurrent(uri)){
                int p=AudioPlaybackService.position();
                AudioPlaybackService.command(this,AudioPlaybackService.ACTION_STOP_KEEP);
                backgroundHandoff=false;
                try{if(video!=null){if(p>0)video.seekTo(p);video.start();getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}}catch(Exception ignored){}
            }
        }
    }

    private void closePlayer(){
        closeRequested=true;backgroundHandoff=false;
        try{AudioPlaybackService.command(this,AudioPlaybackService.ACTION_STOP);}catch(Exception ignored){}
        try{if(video!=null)video.stopPlayback();}catch(Exception ignored){}
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        finish();
    }

    @Override protected void onDestroy(){
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(hideControls);
        if(!audio&&video!=null&&uri!=null&&UiPrefs.playerResume(this))
            try{PlaybackStateStore.put(this,uri,video.getCurrentPosition());}catch(Exception ignored){}
        if(!audio&&closeRequested)try{video.stopPlayback();}catch(Exception ignored){}
        super.onDestroy();
    }

    private void updatePipParams(){
        if(audio||Build.VERSION.SDK_INT<26)return;
        try{
            Rational ratio=(videoW>0&&videoH>0)?new Rational(videoW,videoH):new Rational(16,9);
            PictureInPictureParams.Builder b=new PictureInPictureParams.Builder().setAspectRatio(ratio);
            if(Build.VERSION.SDK_INT>=31){
                android.graphics.Rect rect=new android.graphics.Rect();
                if(videoHolder!=null&&videoHolder.getGlobalVisibleRect(rect)&&!rect.isEmpty())b.setSourceRectHint(rect);
                b.setAutoEnterEnabled(UiPrefs.playerMiniPlayer(this)&&!launchingExternal);
            }
            setPictureInPictureParams(b.build());
        }catch(Exception ignored){}
    }

    @Override public void onUserLeaveHint(){
        super.onUserLeaveHint();
        if(!audio&&UiPrefs.playerMiniPlayer(this)&&video!=null&&video.isPlaying()&&Build.VERSION.SDK_INT>=26&&!launchingExternal){
            updatePipParams();
            if(Build.VERSION.SDK_INT<31){
                try{enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(videoW>0&&videoH>0?new Rational(videoW,videoH):new Rational(16,9)).build());}catch(Exception ignored){}
            }
        }
    }

    @Override public void onPictureInPictureModeChanged(boolean inPip,Configuration c){
        super.onPictureInPictureModeChanged(inPip,c);
        if(inPip)setControlsVisible(false);else showControlsTemporarily();
    }

    @Override public void onConfigurationChanged(Configuration c){
        super.onConfigurationChanged(c);
        if(!audio){applyImmersive();videoHolder.post(()->{applyScaleMode();updatePipParams();});}
    }

    @Override public void onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);
        if(hasFocus&&!audio)applyImmersive();
    }

    private boolean isAudio(String m,String n){
        String x=(m==null?"":m.toLowerCase(java.util.Locale.ROOT));
        String y=(n==null?"":n.toLowerCase(java.util.Locale.ROOT));
        return x.startsWith("audio/")||y.matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|opus)$");
    }

    private Button button(String s){
        Button b=new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(ProTechV6Theme.rounded(this,0x22000000,12,Color.TRANSPARENT));
        b.setMinHeight(0);b.setMinWidth(0);
        b.setPadding(dp(4),0,dp(4),0);
        return b;
    }

    private Button videoIconButton(String glyph,int textSp){
        Button b=button(glyph);
        b.setTextSize(textSp);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));
        b.setBackground(ProTechV6Theme.rounded(this,0x22000000,24,Color.TRANSPARENT));
        return b;
    }

    private TextView text(String s){
        TextView v=new TextView(this);
        v.setText(s);
        v.setTextColor(Color.WHITE);
        v.setPadding(dp(8),dp(4),dp(8),dp(4));
        return v;
    }

    private String repeatIcon(){int m=PlaybackQueueStore.repeatMode(this);return m==1?"↻ 1":(m==2?"↻ •":"↻");}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}
}
