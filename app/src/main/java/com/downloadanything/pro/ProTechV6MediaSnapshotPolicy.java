package com.downloadanything.pro;

/** Thread-safe policy for media snapshot freshness and stale request rejection. */
final class ProTechV6MediaSnapshotPolicy {
    private final long ttlMs;
    private long generation;
    ProTechV6MediaSnapshotPolicy(long ttlMs){this.ttlMs=Math.max(0L,ttlMs);}
    synchronized long beginRequest(){return ++generation;}
    synchronized boolean isCurrent(long requestGeneration){return generation==requestGeneration;}
    synchronized void invalidate(){generation++;}
    boolean canReuse(long loadedAtMs,long nowMs){
        return loadedAtMs>0L&&nowMs>=loadedAtMs&&nowMs-loadedAtMs<=ttlMs;
    }
}
