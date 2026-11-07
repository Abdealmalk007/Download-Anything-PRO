package com.downloadanything.pro;

import android.os.SystemClock;
import android.util.Log;

/** Small, non-sensitive Stage 15 critical-path markers for matched startup diagnosis. */
final class ProTechV6StartupTrace {
    private static final String TAG="DA.Stage15";
    private static volatile long launchStartedMs=SystemClock.elapsedRealtime();
    private ProTechV6StartupTrace(){}
    static void beginLaunch(){launchStartedMs=SystemClock.elapsedRealtime();}
    static void mark(String event){mark(event,-1L,-1);}
    static void mark(String event,long durationMs,int count){
        StringBuilder line=new StringBuilder(96)
                .append("event=").append(event)
                .append(" elapsed_ms=").append(Math.max(0L,SystemClock.elapsedRealtime()-launchStartedMs))
                .append(" thread=").append(Thread.currentThread().getName());
        if(durationMs>=0L)line.append(" duration_ms=").append(durationMs);
        if(count>=0)line.append(" count=").append(count);
        android.util.Log.i(TAG,line.toString());
    }
}
