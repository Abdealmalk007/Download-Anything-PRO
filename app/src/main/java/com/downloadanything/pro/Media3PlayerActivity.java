package com.downloadanything.pro;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.*;
import android.widget.*;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

/**
 * Enhanced Media3 player - optional progressive migration from legacy VideoView.
 * The original player remains the default/fallback until on-device verification.
 */
@androidx.media3.common.util.UnstableApi
public final class Media3PlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView surface;
    private Uri mediaUri;
    private float speed=1.0f;
    private boolean fill=false;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        mediaUri=getIntent()==null?null:getIntent().getData();
        if(mediaUri==null){finish();return;}
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root=new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface=new PlayerView(this);
        surface.setUseController(true);
        surface.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        surface.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0x99000000);
        Button back=button("✕");back.setOnClickListener(v->finish());
        Button rate=button("1.0×");
        rate.setOnClickListener(v->{float[] rates={1f,1.25f,1.5f,1.75f,2f,0.5f,0.75f};
            int next=0;
            for(int i=0;i<rates.length;i++){if(Math.abs(speed-rates[i])<0.01f){next=(i+1)%rates.length;break;}}
            speed=rates[next];if(player!=null)player.setPlaybackSpeed(speed);
            rate.setText(speed+"×");
        });
        Button scale=button(UiPrefs.t(this,"ملاءمة","Fit"));
        scale.setOnClickListener(v->{fill=!fill;surface.setResizeMode(fill?
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM:AspectRatioFrameLayout.RESIZE_MODE_FIT);
            scale.setText(fill?UiPrefs.t(this,"تعبئة","Fill"):UiPrefs.t(this,"ملاءمة","Fit"));});
        Button rotate=button("⟳");
        rotate.setOnClickListener(v->{
            setRequestedOrientation(getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE?
              ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        });
        top.addView(back,new LinearLayout.LayoutParams(0,dp(50),1));
        top.addView(rate,new LinearLayout.LayoutParams(0,dp(50),1));
        top.addView(scale,new LinearLayout.LayoutParams(0,dp(50),1));
        top.addView(rotate,new LinearLayout.LayoutParams(0,dp(50),1));
        if(Build.VERSION.SDK_INT>=26){
            Button pip=button("PiP");pip.setOnClickListener(v->{
                try{enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16,9)).build());}
                catch(Exception ignored){}
            });
            top.addView(pip,new LinearLayout.LayoutParams(0,dp(50),1));
        }
        root.addView(top,new FrameLayout.LayoutParams(-1,dp(56),Gravity.TOP));
        setContentView(root);
        try{
            player=new ExoPlayer.Builder(this).build();
            surface.setPlayer(player);
            player.addListener(new Player.Listener(){
                @Override public void onPlayerError(PlaybackException e){
                    Toast.makeText(Media3PlayerActivity.this,
                         UiPrefs.t(Media3PlayerActivity.this,"تعذر تشغيل الملف بالمشغل المتقدم","Unable to play with advanced player"),
                         Toast.LENGTH_LONG).show();
                }
                @Override public void onPlaybackStateChanged(int state){
                    if(state==Player.STATE_ENDED)PlaybackStateStore.clear(Media3PlayerActivity.this,mediaUri);
                }
            });
            player.setMediaItem(MediaItem.fromUri(mediaUri));
            player.prepare();
            int saved=UiPrefs.playerResume(this)?PlaybackStateStore.get(this,mediaUri):0;
            if(saved>5000)player.seekTo(saved);
            player.play();
        }catch(Exception ex){
            Toast.makeText(this,UiPrefs.t(this,"فشل تهيئة المشغل المتقدم","Advanced player initialization failed"),Toast.LENGTH_LONG).show();
            finish();
        }
    }
    private Button button(String label){
        Button b=new Button(this);
        b.setText(label);b.setAllCaps(false);b.setTextColor(Color.WHITE);
        b.setTextSize(12);b.setBackgroundColor(0x22000000);
        return b;
    }
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}
    @Override protected void onStop(){
        if(player!=null && mediaUri!=null && player.getDuration()>0 &&
              player.getCurrentPosition()>=1000 && player.getCurrentPosition()<player.getDuration()-2000)
            PlaybackStateStore.put(this,mediaUri,(int)Math.min(Integer.MAX_VALUE,player.getCurrentPosition()));
        super.onStop();
    }
    @Override protected void onDestroy(){
        if(surface!=null)surface.setPlayer(null);
        if(player!=null){player.release();player=null;}
        super.onDestroy();
    }
}
