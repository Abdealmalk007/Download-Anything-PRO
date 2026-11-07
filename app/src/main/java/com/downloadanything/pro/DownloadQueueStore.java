package com.downloadanything.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Persistent queue with a process-local cache.
 *
 * 3.0.9 avoids reparsing the complete SharedPreferences JSON array for every
 * progress broadcast/notification. Public reads return copies so callers
 * cannot mutate cached state without an explicit upsert().
 */
public final class DownloadQueueStore {
    private static final String PREFS="download_queue_v2", KEY="jobs", KEY_BACKUP="jobs_backup";
    private static final int MAX=100;
    private static ArrayList<DownloadJob> cache;
    // 4.6.0: keep the last structurally valid serialized snapshot in memory.
    // This avoids reparsing the complete queue JSON on every progress/state write.
    private static String lastValidSnapshot="[]";
    private DownloadQueueStore() {}

    private static void ensureLoaded(Context c) {
        if (cache != null) return;
        SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String primary=p.getString(KEY,"[]");
        ArrayList<DownloadJob> loaded=parseJobs(primary);
        if(loaded==null){
            String backup=p.getString(KEY_BACKUP,"[]");
            loaded=parseJobs(backup);
            if(loaded!=null){
                // Restore the last known-good queue snapshot. This is local-only and
                // prevents a malformed/truncated preferences write from appearing as
                // an empty queue after process death.
                p.edit().putString(KEY,backup).commit();
                lastValidSnapshot=backup;
                PerformanceTracker.event(c,"queue_backup_restored");
            }else{
                // Non-destructive SQL recovery is used only if both legacy snapshots fail.
                String mirrored=QueueSqliteMirror.restore(c);
                loaded=parseJobs(mirrored);
                if(loaded!=null){
                    lastValidSnapshot=mirrored;
                    p.edit().putString(KEY,mirrored).commit();
                    PerformanceTracker.event(c,"queue_sqlite_mirror_restored");
                }else{
                    loaded=new ArrayList<>();
                    lastValidSnapshot="[]";
                    PerformanceTracker.event(c,"queue_primary_backup_and_sqlite_invalid");
                }
            }
        }else{
            lastValidSnapshot=primary==null?"[]":primary;
        }
        cache=loaded;
    }

    private static ArrayList<DownloadJob> parseJobs(String raw){
        try{
            JSONArray a=new JSONArray(raw==null?"[]":raw);
            ArrayList<DownloadJob> out=new ArrayList<>();
            for(int i=0;i<a.length() && out.size()<MAX;i++){
                if(a.optJSONObject(i)==null) return null;
                DownloadJob j=DownloadJob.fromJson(a.optJSONObject(i));
                if(j==null || j.id==null || j.id.trim().isEmpty()) return null;
                out.add(j);
            }
            return out;
        }catch(Exception e){ return null; }
    }

    private static DownloadJob cp(DownloadJob j) { return j==null?null:j.copy(); }

    public static synchronized List<DownloadJob> all(Context c) {
        ensureLoaded(c);
        ArrayList<DownloadJob> out=new ArrayList<>(cache.size());
        for(DownloadJob j:cache) out.add(cp(j));
        return out;
    }

    private static String serializeSnapshot() {
        JSONArray a=new JSONArray(); int n=0;
        for(DownloadJob j:cache){ if(n++>=MAX) break; a.put(j.toJson()); }
        return a.toString();
    }

    private static boolean persistSnapshot(Context c, String snapshot, boolean durable) {
        SharedPreferences prefs=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        SharedPreferences.Editor editor=prefs.edit();
        // lastValidSnapshot was already structurally validated when loaded, or was
        // generated from DownloadJob.toJson() by this process. Avoid reparsing the
        // entire queue on every progress/state persist.
        if(lastValidSnapshot!=null && !lastValidSnapshot.isEmpty()) editor.putString(KEY_BACKUP,lastValidSnapshot);
        editor.putString(KEY,snapshot);
        if(durable){
            boolean ok=editor.commit();
            if(ok){
                lastValidSnapshot=snapshot;
                // Durable snapshots are mirrored transactionally; progress writes
                // continue on the fast legacy path and do not block on SQLite.
                if(!QueueSqliteMirror.save(c,snapshot))
                    PerformanceTracker.event(c,"queue_sqlite_mirror_unavailable");
            }
            return ok;
        }
        editor.apply();
        lastValidSnapshot=snapshot;
        return true;
    }

    private static boolean persist(Context c, boolean durable) {
        ensureLoaded(c);
        return persistSnapshot(c,serializeSnapshot(),durable);
    }

    private static void persist(Context c) { persist(c,false); }

    public static synchronized boolean enqueue(Context c,DownloadJob job) {
        ensureLoaded(c);
        if(job.requestId.isEmpty())job.requestId=java.util.UUID.randomUUID().toString();
        if(job.originalUrl.isEmpty())job.originalUrl=job.url;
        if(job.resolvedUrl.isEmpty())job.resolvedUrl=job.url;
        String key=norm(job.url)+"|"+job.audio+"|"+job.quality+"|"+job.audioQuality+"|"+job.audioFormat+"|"+job.videoContainer+formatSelectionSuffix(job);
        for(DownloadJob j:cache){
            String k=norm(j.url)+"|"+j.audio+"|"+j.quality+"|"+j.audioQuality+"|"+j.audioFormat+"|"+j.videoContainer+formatSelectionSuffix(j);
            if(j.isActiveLike() && key.equals(k)) return false;
        }
        cache.add(0,cp(job));
        while(cache.size()>MAX) cache.remove(cache.size()-1);
        long persistStarted=SystemClock.elapsedRealtime();
        String snapshot=serializeSnapshot();
        boolean persisted=persistSnapshot(c,snapshot,job.quickShare);
        if(job.quickShare){
            // 3.3.9 reliability fix, forward-ported onto the 4.3+ queue hardening:
            // retry one failed durable enqueue using the SAME serialized snapshot.
            // The retry is intentionally scoped to Quick Share enqueue only.
            if(!persisted){
                PerformanceTracker.event(c,"instant_share_durable_persist_retry");
                persisted=persistSnapshot(c,snapshot,true);
                if(persisted) PerformanceTracker.event(c,"instant_share_durable_persist_retry_recovered");
            }
            PerformanceTracker.record(c,"instant_share_persist_ms",SystemClock.elapsedRealtime()-persistStarted);
            if(!persisted) PerformanceTracker.event(c,"instant_share_durable_persist_failed");
        }
        return true;
    }

    public static synchronized DownloadJob get(Context c,String id){
        ensureLoaded(c);
        for(DownloadJob j:cache) if(j.id.equals(id)) return cp(j);
        return null;
    }

    public static synchronized void upsert(Context c,DownloadJob job){
        ensureLoaded(c); boolean found=false;
        for(int i=0;i<cache.size();i++) if(cache.get(i).id.equals(job.id)){ cache.set(i,cp(job)); found=true; break; }
        if(!found) cache.add(0,cp(job));
        while(cache.size()>MAX) cache.remove(cache.size()-1);
        persist(c);
    }


    public static synchronized void upsertDurable(Context c,DownloadJob job){
        ensureLoaded(c); boolean found=false;
        for(int i=0;i<cache.size();i++) if(cache.get(i).id.equals(job.id)){ cache.set(i,cp(job)); found=true; break; }
        if(!found) cache.add(0,cp(job));
        while(cache.size()>MAX) cache.remove(cache.size()-1);
        persist(c,true);
    }

    /** Unified recovery: explicit pause/cancel/completed states are never auto-resumed. */
    public static synchronized int recoverInterrupted(Context c, boolean boot){
        ensureLoaded(c); int n=0; long now=System.currentTimeMillis();
        for(DownloadJob j:cache){
            boolean runtimeInterrupted="downloading".equals(j.state)||"resuming".equals(j.state);
            boolean deferredRetry="waiting_retry".equals(j.state);
            boolean policyWait="waiting_engine".equals(j.state)||"waiting_network".equals(j.state);
            if(!runtimeInterrupted && !deferredRetry && !policyWait) continue;
            if(runtimeInterrupted){j.state="queued"; j.phase="queued"; j.nextRetryAt=0L;}
            else if(deferredRetry && j.nextRetryAt<=now){j.state="queued"; j.phase="queued"; j.nextRetryAt=0L;}
            // A future retry deadline survives process death/reboot. It remains in
            // waiting_retry and DownloadService restores the remaining delay.
            j.recoveryCount++; j.lastRecoveryAt=now; j.updatedAt=now;
            j.retryKind=boot?"boot_recovery":"process_recovery"; n++;
        }
        if(n>0)persist(c,true);
        return n;
    }

    public static synchronized int countActiveLike(Context c){
        ensureLoaded(c); int n=0; for(DownloadJob j:cache)if(j.isActiveLike())n++; return n;
    }

    public static synchronized DownloadJob nextQueued(Context c){
        ensureLoaded(c);
        for(int i=cache.size()-1;i>=0;i--){ DownloadJob j=cache.get(i); if("queued".equals(j.state)) return cp(j); }
        return null;
    }

    public static synchronized void markInterruptedPaused(Context c){
        ensureLoaded(c); boolean changed=false, durable=false;
        for(DownloadJob j:cache){
            if("downloading".equals(j.state)||"resuming".equals(j.state)){
                // 3.2.1: instant-share jobs are fire-and-forget. If Android kills
                // the process, requeue them so START_STICKY/service recovery can
                // continue automatically. Manual jobs keep the safer paused state.
                if (j.quickShare) {
                    j.state="queued"; j.phase="queued"; j.lastError=""; durable=true;
                } else {
                    j.state="paused"; j.phase="paused";
                }
                j.updatedAt=System.currentTimeMillis(); changed=true;
            }else if("waiting_retry".equals(j.state)){
                j.state="queued"; j.phase="queued"; j.updatedAt=System.currentTimeMillis(); changed=true;
            }
        }
        if(changed) persist(c,durable);
    }


    /** Remove one queue/history record without touching the downloaded file. */
    public static synchronized boolean remove(Context c,String id){
        if(id==null||id.trim().isEmpty())return false;
        ensureLoaded(c); boolean changed=false;
        for(int i=cache.size()-1;i>=0;i--)if(id.equals(cache.get(i).id)){cache.remove(i);changed=true;}
        if(changed)persist(c,true);
        return changed;
    }

    /** Remove finished history records that point to a file the user just deleted. */
    public static synchronized int removeFinishedByOutputUri(Context c,String uri){
        if(uri==null||uri.trim().isEmpty())return 0;
        ensureLoaded(c); int n=0;
        for(int i=cache.size()-1;i>=0;i--){
            DownloadJob j=cache.get(i);
            if(!j.isActiveLike()&&uri.equals(j.outputUri)){cache.remove(i);n++;}
        }
        if(n>0)persist(c,true);
        return n;
    }

    public static synchronized void clearFinished(Context c){
        ensureLoaded(c);
        ArrayList<DownloadJob> next=new ArrayList<>();
        for(DownloadJob j:cache) if(j.isActiveLike()) next.add(j);
        cache=next; persist(c);
    }

    public static synchronized boolean hasRecentEquivalent(Context c, DownloadJob job, long windowMs){
        ensureLoaded(c);
        if(job==null)return false;
        long now=System.currentTimeMillis();
        long window=Math.max(5_000L,windowMs);
        String key=norm(job.url)+"|"+job.audio+"|"+job.quality+"|"+job.audioQuality+"|"+job.audioFormat+"|"+job.videoContainer+formatSelectionSuffix(job);
        for(DownloadJob j:cache){
            String k=norm(j.url)+"|"+j.audio+"|"+j.quality+"|"+j.audioQuality+"|"+j.audioFormat+"|"+j.videoContainer+formatSelectionSuffix(j);
            if(key.equals(k) && now-Math.max(j.createdAt,j.updatedAt)<=window && !"failed".equals(j.state) && !"cancelled".equals(j.state))return true;
        }
        return false;
    }

    public static synchronized boolean hasWorkInProgress(Context c){
        ensureLoaded(c);
        for(DownloadJob j:cache) if(j.isActiveLike()) return true;
        return false;
    }

    public static synchronized boolean hasQueued(Context c){ return nextQueued(c)!=null; }

    public static synchronized boolean hasState(Context c,String state){
        if(state==null) return false; ensureLoaded(c);
        for(DownloadJob j:cache) if(state.equals(j.state)) return true;
        return false;
    }

    public static synchronized int requeueState(Context c,String fromState){
        if(fromState==null) return 0; ensureLoaded(c); int n=0;
        for(DownloadJob j:cache){
            if(fromState.equals(j.state)){
                j.state="queued"; j.phase="queued"; j.updatedAt=System.currentTimeMillis(); n++;
            }
        }
        if(n>0) persist(c);
        return n;
    }

    /** Requeue only deferred retries whose persisted deadline has actually arrived. */
    public static synchronized int requeueDueRetries(Context c,long now){
        ensureLoaded(c); int n=0;
        for(DownloadJob j:cache){
            if("waiting_retry".equals(j.state) && (j.nextRetryAt<=0L || j.nextRetryAt<=now)){
                j.state="queued"; j.phase="queued"; j.nextRetryAt=0L; j.updatedAt=now; n++;
            }
        }
        if(n>0) persist(c,true);
        return n;
    }

    public static synchronized int activeCount(Context c){
        ensureLoaded(c); int n=0;
        for(DownloadJob j:cache) if("downloading".equals(j.state)||"resuming".equals(j.state)) n++;
        return n;
    }

    public static synchronized int countState(Context c,String... states){
        ensureLoaded(c); int n=0;
        outer: for(DownloadJob j:cache){ for(String s:states) if(s.equals(j.state)){ n++; continue outer; } }
        return n;
    }

    private static String formatSelectionSuffix(DownloadJob j){
        if(j==null)return "";
        return "|vf="+(j.selectedVideoFormatId==null?"":j.selectedVideoFormatId)+"|af="+(j.selectedAudioFormatId==null?"":j.selectedAudioFormatId);
    }

    private static String norm(String s){
        if(s==null) return ""; return LinkCapture.normalize(s.trim());
    }
}
