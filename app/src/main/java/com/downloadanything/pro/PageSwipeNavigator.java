package com.downloadanything.pro;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

import java.util.WeakHashMap;

/**
 * Horizontal swipe navigation for the five primary application pages.
 *
 * It observes touch events without consuming them so vertical scrolling,
 * buttons and WebView interaction continue to work normally.
 */
public final class PageSwipeNavigator {
    public static final int HOME=0;
    public static final int DOWNLOADS=1;
    public static final int BROWSER=2;
    public static final int MEDIA=3;
    public static final int TOOLS=4;

    private static final class State {
        float downX,downY;
        long downAt;
        int pointerId=-1;
        boolean multiTouch=false;
        boolean fired=false;
        boolean downOnEditText=false;
    }

    private static final WeakHashMap<Activity,State> STATES=new WeakHashMap<>();

    private PageSwipeNavigator(){}

    public static void observe(Activity activity,int page,MotionEvent ev){
        if(activity==null||ev==null||page<HOME||page>TOOLS)return;
        State s;
        synchronized(STATES){
            s=STATES.get(activity);
            if(s==null){s=new State();STATES.put(activity,s);}
        }

        final int action=ev.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            s.downX=ev.getRawX();
            s.downY=ev.getRawY();
            s.downAt=SystemClock.uptimeMillis();
            s.pointerId=ev.getPointerId(0);
            s.multiTouch=false;
            s.fired=false;
            View focus=activity.getCurrentFocus();
            s.downOnEditText=focus instanceof EditText && inside(focus,s.downX,s.downY);
            return;
        }
        if(action==MotionEvent.ACTION_POINTER_DOWN){
            s.multiTouch=true;
            return;
        }
        if(action==MotionEvent.ACTION_CANCEL){
            s.pointerId=-1;
            return;
        }
        // 5.3.1: child scroll views/WebView may consume or cancel ACTION_UP.
        // Evaluate deliberate horizontal movement while MOVE is still flowing through
        // Activity.dispatchTouchEvent, then keep ACTION_UP as a fallback.
        if((action!=MotionEvent.ACTION_MOVE&&action!=MotionEvent.ACTION_UP)||s.fired||s.multiTouch)return;

        long elapsed=Math.max(1L,SystemClock.uptimeMillis()-s.downAt);
        float dx=ev.getRawX()-s.downX;
        float dy=ev.getRawY()-s.downY;
        float absX=Math.abs(dx);
        float absY=Math.abs(dy);

        // Do not steal horizontal text-selection/cursor gestures, but an EditText
        // that merely remains focused must not disable swiping elsewhere on the page.
        if(s.downOnEditText)return;

        float density=activity.getResources().getDisplayMetrics().density;
        // Historical 5.3.1 acceptance baseline retained for regression: widthPixels*0.16f, absX<absY*1.30f, velocity<140f.
        float widthPixels=activity.getResources().getDisplayMetrics().widthPixels;
        float minDistance=Math.max(64f*density,widthPixels*0.14f);
        float velocity=absX*1000f/elapsed;

        // Deliberate one-finger horizontal gesture only. Keep vertical scrolling
        // and browser site carousels working; in Browser use an edge gesture.
        if(elapsed>1600L||absX<minDistance||absX<absY*1.20f||velocity<120f)return;
        if(page==BROWSER && s.downX>widthPixels*.17f && s.downX<widthPixels*.83f)return;

        // Arabic RTL ordering: Home -> Downloads -> Browser -> Media -> Tools
        // advances on right swipe. English LTR advances on left swipe.
        boolean rtl=UiPrefs.isArabic(activity);
        int target=(dx>0)==rtl?page+1:page-1;
        if(target<HOME||target>TOOLS)return;
        s.fired=true;
        try{activity.getWindow().getDecorView().performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);}catch(Exception ignored){}
        navigate(activity,target);
    }

    private static boolean inside(View v,float rawX,float rawY){
        if(v==null||!v.isShown())return false;
        int[] loc=new int[2];
        try{
            v.getLocationOnScreen(loc);
            return rawX>=loc[0]&&rawX<=loc[0]+v.getWidth()&&rawY>=loc[1]&&rawY<=loc[1]+v.getHeight();
        }catch(Exception ignored){return false;}
    }

    public static void navigate(Activity activity,int target){
        Class<?> cls;
        switch(target){
            case DOWNLOADS: cls=DownloadsActivity.class; break;
            case BROWSER: cls=BrowserActivity.class; break;
            case MEDIA: cls=MediaHubActivity.class; break;
            case TOOLS: cls=AudioConverterActivity.class; break;
            case HOME:
            default: cls=MainActivity.class; break;
        }

        if(cls.isInstance(activity))return;

        Intent i=new Intent(activity,cls)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if(target==BROWSER)i.putExtra("start_url","https://www.google.com");
        activity.startActivity(i);
        try{activity.overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out);}catch(Exception ignored){}
    }
}
