package com.downloadanything.pro;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.media.*;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.media.MediaMetadata;
import android.net.Uri;
import android.os.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Unified background playback service for local audio, local video audio tracks,
 * and source-resolved browser streams.
 */
public final class AudioPlaybackService extends Service {
    public static final String ACTION_PLAY="com.downloadanything.pro.player.PLAY";
    public static final String ACTION_TOGGLE="com.downloadanything.pro.player.TOGGLE";
    public static final String ACTION_STOP="com.downloadanything.pro.player.STOP";
    public static final String ACTION_STOP_KEEP="com.downloadanything.pro.player.STOP_KEEP";
    public static final String ACTION_BACK="com.downloadanything.pro.player.BACK15";
    public static final String ACTION_FORWARD="com.downloadanything.pro.player.FORWARD15";
    public static final String ACTION_SEEK_TO="com.downloadanything.pro.player.SEEK_TO";
    public static final String ACTION_PREVIOUS="com.downloadanything.pro.player.PREVIOUS";
    public static final String ACTION_NEXT="com.downloadanything.pro.player.NEXT";
    public static final String ACTION_SLEEP_TIMER="com.downloadanything.pro.player.SLEEP_TIMER";

    private static final String CHANNEL="download_anything_player_v2";
    private static final int NOTIFICATION_ID=7600;
    private static volatile AudioPlaybackService live;

    private MediaPlayer player;
    private android.media.audiofx.LoudnessEnhancer loudnessEnhancer;
    public static final String ACTION_SET_BOOST="com.downloadanything.pro.player.SET_BOOST";
    private Uri current;
    private String mime="audio/*",title="Audio";
    private String userAgent="",cookie="";
    private boolean prepared;
    private int requestedStartPosition=0;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean noisyRegistered;
    private final Handler sleepHandler=new Handler(Looper.getMainLooper());
    private final Runnable sleepStop=()->{stopPlayback(false);stopSelf();};
    private int sleepMinutes=0;

    private final BroadcastReceiver noisyReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent==null?null:intent.getAction()))pauseForFocus();
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener=change->{
        if(change==AudioManager.AUDIOFOCUS_GAIN){
            try{
                if(player!=null)player.setVolume(1f,1f);
                // Never restart paused audio on focus gain or screen unlock.
            }catch(Exception ignored){}
            updateMediaSession(); refreshNotification();
        }else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){
            try{if(player!=null)player.setVolume(0.25f,0.25f);}catch(Exception ignored){}
        }else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){
            pauseForFocus();
        }else if(change==AudioManager.AUDIOFOCUS_LOSS){
            pauseForFocus();
        }
    };

    public static void play(Context c,Uri uri,String mime,String title){
        playAt(c,uri,mime,title,0,"","");
    }

    public static void playAt(Context c,Uri uri,String mime,String title,int positionMs,String userAgent,String cookie){
        if(c==null||uri==null)return;
        Intent i=new Intent(c,AudioPlaybackService.class).setAction(ACTION_PLAY).setData(uri)
                .putExtra("mime",mime).putExtra("title",title)
                .putExtra("positionMs",Math.max(0,positionMs))
                .putExtra("userAgent",userAgent==null?"":userAgent)
                .putExtra("cookie",cookie==null?"":cookie);
        try{if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}catch(Exception ignored){}
    }

    public static void command(Context c,String action){
        if(c==null)return;
        try{c.startService(new Intent(c,AudioPlaybackService.class).setAction(action));}catch(Exception ignored){}
    }

    public static void seekTo(Context c,int positionMs){
        if(c==null)return;
        try{c.startService(new Intent(c,AudioPlaybackService.class).setAction(ACTION_SEEK_TO).putExtra("positionMs",Math.max(0,positionMs)));}catch(Exception ignored){}
    }

    public static void setSleepTimer(Context c,int minutes){if(c==null)return;try{c.startService(new Intent(c,AudioPlaybackService.class).setAction(ACTION_SLEEP_TIMER).putExtra("minutes",Math.max(0,minutes)));}catch(Exception ignored){}}
    public static int sleepMinutes(){AudioPlaybackService s=live;return s==null?0:s.sleepMinutes;}
    public static boolean isCurrent(Uri uri){AudioPlaybackService s=live;return s!=null&&s.current!=null&&s.current.equals(uri);}
    public static boolean isPlayingNow(){AudioPlaybackService s=live;try{return s!=null&&s.player!=null&&s.prepared&&s.player.isPlaying();}catch(Exception e){return false;}}
    public static int position(){AudioPlaybackService s=live;try{return s!=null&&s.player!=null&&s.prepared?s.player.getCurrentPosition():0;}catch(Exception e){return 0;}}
    public static int boostDb(Context c){return c.getSharedPreferences("audio_boost",MODE_PRIVATE).getInt("db",0);}
    public static void setBoost(Context c,int db){int safe=Math.max(0,Math.min(9,db));c.getSharedPreferences("audio_boost",MODE_PRIVATE).edit().putInt("db",safe).apply();try{c.startService(new Intent(c,AudioPlaybackService.class).setAction(ACTION_SET_BOOST).putExtra("db",safe));}catch(Exception ignored){}}
    public static int duration(){AudioPlaybackService s=live;try{return s!=null&&s.player!=null&&s.prepared?s.player.getDuration():0;}catch(Exception e){return 0;}}

    @Override public void onCreate(){
        super.onCreate(); live=this; audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        createChannel(); createMediaSession(); registerNoisyReceiver();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        String action=intent.getAction();
        if(ACTION_PLAY.equals(action)&&intent.getData()!=null){
            startUri(intent.getData(),intent.getStringExtra("mime"),intent.getStringExtra("title"),
                    intent.getIntExtra("positionMs",0),intent.getStringExtra("userAgent"),intent.getStringExtra("cookie"));
        }else if(ACTION_TOGGLE.equals(action))toggle();
        else if(ACTION_BACK.equals(action))seekBy(-15000);
        else if(ACTION_FORWARD.equals(action))seekBy(15000);
        else if(ACTION_SEEK_TO.equals(action))seekToInternal(intent.getIntExtra("positionMs",0));
        else if(ACTION_PREVIOUS.equals(action))skipQueue(-1);
        else if(ACTION_NEXT.equals(action))skipQueue(1);
        else if(ACTION_SLEEP_TIMER.equals(action))setSleepInternal(intent.getIntExtra("minutes",0));
        else if(ACTION_SET_BOOST.equals(action))applyBoost();
        else if(ACTION_STOP_KEEP.equals(action)){stopPlayback(false);stopSelf();}
        else if(ACTION_STOP.equals(action)){stopPlayback(true);stopSelf();}
        return START_NOT_STICKY;
    }

    private void setSleepInternal(int minutes){sleepHandler.removeCallbacks(sleepStop);sleepMinutes=Math.max(0,minutes);if(sleepMinutes>0)sleepHandler.postDelayed(sleepStop,sleepMinutes*60_000L);refreshNotification();}
    private void startUri(Uri uri,String m,String t,int positionMs,String ua,String ck){
        if(current!=null&&current.equals(uri)&&prepared){
            if(positionMs>0)seekToInternal(positionMs);
            // A duplicate PLAY intent may arrive from a restored activity/Android
            // notification. Never revive a deliberately paused, prepared item.
            // The visible in-app Play button uses ACTION_TOGGLE explicitly.
            refreshNotification();return;
        }
        release(false);
        current=uri; mime=m==null?"audio/*":m; title=t==null||t.trim().isEmpty()?"Media":t;
        userAgent=ua==null?"":ua; cookie=ck==null?"":ck; requestedStartPosition=Math.max(0,positionMs);
        try{
            player=new MediaPlayer();
            player.setWakeMode(getApplicationContext(),PowerManager.PARTIAL_WAKE_LOCK);
            if(Build.VERSION.SDK_INT>=21)player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            else player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            if(isHttp(uri)){
                Map<String,String> headers=new HashMap<>();
                if(!userAgent.isEmpty())headers.put("User-Agent",userAgent);
                if(!cookie.isEmpty())headers.put("Cookie",cookie);
                player.setDataSource(this,uri,headers);
            }else player.setDataSource(this,uri);
            player.setOnPreparedListener(mp->{
                prepared=true; applyBoost();
                int resume=requestedStartPosition>0?requestedStartPosition:PlaybackStateStore.get(this,uri);
                if(resume>0&&resume<mp.getDuration()-1000)mp.seekTo(resume);
                if(requestFocus())mp.start();
                updateMediaSession();
                startForeground(NOTIFICATION_ID,notification());
            });
            player.setOnCompletionListener(mp->{
                PlaybackStateStore.clear(this,uri);
                PlaybackQueueStore.Entry next=PlaybackQueueStore.next(this);
                if(next!=null&&next.uri!=null&&(PlaybackQueueStore.repeatOne(this)||PlaybackQueueStore.size(this)>1)){
                    startUri(next.uri,next.mime,next.name,0,"","");
                    return;
                }
                prepared=false; stopForeground(true); stopSelf();
            });
            player.setOnErrorListener((mp,what,extra)->{
                PlaybackStateStore.put(this,uri,safePos()); stopPlayback(false); stopSelf(); return true;
            });
            startForeground(NOTIFICATION_ID,notification());
            player.prepareAsync();
        }catch(Exception e){stopPlayback(false);stopSelf();}
    }

    private boolean requestFocus(){
        if(audioManager==null)return true;
        try{
            int r;
            if(Build.VERSION.SDK_INT>=26){
                focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setOnAudioFocusChangeListener(focusListener).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(false).build();
                r=audioManager.requestAudioFocus(focusRequest);
            }else r=audioManager.requestAudioFocus(focusListener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);
            return r==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }catch(Exception e){return true;}
    }

    private void abandonFocus(){
        if(audioManager==null)return;
        try{if(Build.VERSION.SDK_INT>=26&&focusRequest!=null)audioManager.abandonAudioFocusRequest(focusRequest);else audioManager.abandonAudioFocus(focusListener);}catch(Exception ignored){}
    }

    private void pauseForFocus(){
        try{
            if(player!=null&&prepared&&player.isPlaying())player.pause();
            PlaybackStateStore.put(this,current,safePos());
        }catch(Exception ignored){}
        updateMediaSession();refreshNotification();
    }

    private void toggle(){
        if(player==null||!prepared)return;
        try{if(player.isPlaying())player.pause();else if(requestFocus())player.start();PlaybackStateStore.put(this,current,safePos());updateMediaSession();refreshNotification();}catch(Exception ignored){}
    }

    private void seekBy(int delta){if(player==null||!prepared)return;try{int d=player.getDuration();int p=Math.max(0,Math.min(d,player.getCurrentPosition()+delta));player.seekTo(p);PlaybackStateStore.put(this,current,p);updateMediaSession();refreshNotification();}catch(Exception ignored){}}
    private void seekToInternal(int target){if(player==null||!prepared)return;try{int d=player.getDuration();int p=Math.max(0,Math.min(d,target));player.seekTo(p);PlaybackStateStore.put(this,current,p);updateMediaSession();refreshNotification();}catch(Exception ignored){}}
    private void skipQueue(int delta){PlaybackQueueStore.Entry e=delta<0?PlaybackQueueStore.previous(this):PlaybackQueueStore.next(this);if(e!=null&&e.uri!=null)startUri(e.uri,e.mime,e.name,0,"","");}
    private int safePos(){try{return player!=null&&prepared?player.getCurrentPosition():0;}catch(Exception e){return 0;}}

    private void stopPlayback(boolean clear){
        if(current!=null){if(clear)PlaybackStateStore.clear(this,current);else PlaybackStateStore.put(this,current,safePos());}
        release(false); abandonFocus(); stopForeground(true);
    }

    private void applyBoost(){
        try{if(player==null)return; if(loudnessEnhancer!=null){loudnessEnhancer.release();loudnessEnhancer=null;}
            int gain=boostDb(this);if(gain<=0)return;
            loudnessEnhancer=new android.media.audiofx.LoudnessEnhancer(player.getAudioSessionId());
            loudnessEnhancer.setTargetGain(gain*100);loudnessEnhancer.setEnabled(true);
        }catch(Exception ignored){try{if(loudnessEnhancer!=null)loudnessEnhancer.release();}catch(Exception e){}loudnessEnhancer=null;}
    }
    private void release(boolean clear){
        if(loudnessEnhancer!=null){try{loudnessEnhancer.release();}catch(Exception ignored){}loudnessEnhancer=null;}
        if(player!=null){
            try{if(clear&&current!=null)PlaybackStateStore.clear(this,current);player.reset();player.release();}catch(Exception ignored){}
            player=null;
        }
        prepared=false;
    }

    private void refreshNotification(){try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,notification());}catch(Exception ignored){}}
    private PendingIntent action(String a,int req){Intent i=new Intent(this,AudioPlaybackService.class).setAction(a);return PendingIntent.getService(this,req,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}

    private void createMediaSession(){
        if(Build.VERSION.SDK_INT<21)return;
        try{
            mediaSession=new MediaSession(this,"DownloadAnythingUnifiedMedia");
            mediaSession.setCallback(new MediaSession.Callback(){
                // Keep headset/lock-screen transport available only for a live session.
                // Once the user pauses, an unsolicited Android media-session PLAY must
                // not resume automatically on unlock or audio-focus recovery.
                @Override public void onPlay(){
                    // Bluetooth/lock-screen Play is optional. Default OFF prevents an
                    // unsolicited remote event from starting audio after phone unlock.
                    // Users who want external Play can explicitly enable it in Settings.
                    boolean externalResumeAllowed=getSharedPreferences("player_safety",MODE_PRIVATE)
                            .getBoolean("allow_external_resume",false);
                    if(externalResumeAllowed&&player!=null&&prepared&&!player.isPlaying()
                            &&requestFocus()){
                        try{player.start();}catch(Exception ignored){}
                    }
                    updateMediaSession();refreshNotification();
                }
                @Override public void onPause(){if(player!=null&&prepared&&player.isPlaying()){player.pause();updateMediaSession();refreshNotification();}}
                @Override public void onSkipToNext(){skipQueue(1);}
                @Override public void onSkipToPrevious(){skipQueue(-1);}
                @Override public void onSeekTo(long pos){seekToInternal((int)Math.max(0,Math.min(Integer.MAX_VALUE,pos)));}
                @Override public void onStop(){stopPlayback(false);stopSelf();}
            });
            mediaSession.setActive(true);
        }catch(Exception ignored){}
    }

    private void updateMediaSession(){
        if(mediaSession==null)return;
        try{
            long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_SEEK_TO|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_STOP;
            int state=(player!=null&&prepared&&player.isPlaying())?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED;
            mediaSession.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title).putLong(MediaMetadata.METADATA_KEY_DURATION,duration()).build());
            mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(state,safePos(),state==PlaybackState.STATE_PLAYING?1f:0f).build());
        }catch(Exception ignored){}
    }

    private Notification notification(){
        // Tapping a notification opens controls without restarting the song.
        Intent open=new Intent(this,PlayerActivity.class).setDataAndType(current,mime)
                .putExtra("displayName",title).putExtra("restoreOnly",true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent content=PendingIntent.getActivity(this,7601,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        boolean playing=isPlayingNow();
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_download).setContentTitle(title)
                .setContentText(playing?UiPrefs.t(this,"تشغيل في الخلفية","Playing in background"):UiPrefs.t(this,"متوقف مؤقتًا","Paused"))
                .setContentIntent(content).setOngoing(playing).setOnlyAlertOnce(true).setColor(Color.rgb(124,60,255))
                .addAction(new Notification.Action.Builder(0,"Previous",action(ACTION_PREVIOUS,7602)).build())
                .addAction(new Notification.Action.Builder(0,playing?"Pause":"Play",action(ACTION_TOGGLE,7603)).build())
                .addAction(new Notification.Action.Builder(0,"Next",action(ACTION_NEXT,7604)).build())
                .addAction(new Notification.Action.Builder(0,"Stop",action(ACTION_STOP,7605)).build());
        if(Build.VERSION.SDK_INT>=21&&mediaSession!=null)try{b.setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0,1,2));}catch(Exception ignored){}
        return b.build();
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel(CHANNEL,"Download Anything Player",NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null,null);ch.setDescription("Background media playback");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private void registerNoisyReceiver(){
        try{
            IntentFilter f=new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if(Build.VERSION.SDK_INT>=33)registerReceiver(noisyReceiver,f,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(noisyReceiver,f);
            noisyRegistered=true;
        }catch(Exception ignored){}
    }

    private boolean isHttp(Uri u){String s=u==null?"":u.getScheme();return "http".equalsIgnoreCase(s)||"https".equalsIgnoreCase(s);}

    @Override public void onDestroy(){
        sleepHandler.removeCallbacks(sleepStop);sleepMinutes=0;
        if(current!=null)PlaybackStateStore.put(this,current,safePos());
        release(false);abandonFocus();
        if(noisyRegistered)try{unregisterReceiver(noisyReceiver);}catch(Exception ignored){}
        try{if(mediaSession!=null){mediaSession.setActive(false);mediaSession.release();}}catch(Exception ignored){}
        mediaSession=null;if(live==this)live=null;super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent){return null;}
}
