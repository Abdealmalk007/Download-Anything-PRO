package com.downloadanything.pro;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Per-attempt cancellation survives extractor/direct-downloader fallback. */
final class DownloadControl {
    static final class Run {
        final String jobId;
        final Object lock;
        volatile boolean cancelled, discard;
        Run(String jobId, Object lock) { this.jobId=jobId; this.lock=lock; }
    }
    private static final Map<String, Run> runs = new HashMap<>();
    static synchronized Run register(String jobId, String processId) {
        Object lock = new Object();
        for (Run other : runs.values()) if (other.jobId.equals(jobId)) { lock=other.lock; break; }
        Run run = new Run(jobId, lock); runs.put(processId, run); return run;
    }
    static synchronized void stop(String processId, boolean discard) {
        Run run=runs.get(processId);
        if(run!=null) { run.discard|=discard; run.cancelled=true; }
    }
    static synchronized boolean discardJob(String jobId) {
        boolean pending=false;
        for(Run run:runs.values())if(run.jobId.equals(jobId)){
            run.discard=true;run.cancelled=true;pending=true;
        }
        return pending;
    }
    static synchronized void check(String processId) throws IOException {
        Run run=runs.get(processId);
        if(run==null || run.cancelled) throw new IOException("Download cancelled");
    }
    static synchronized void finish(String processId) { runs.remove(processId); }
}
