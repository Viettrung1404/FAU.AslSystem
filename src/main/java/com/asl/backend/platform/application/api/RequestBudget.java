package com.asl.backend.platform.application.api;
import java.time.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded per-instance, per-socket-IP request budget; returns retry seconds or zero. */
public final class RequestBudget {
    private record Bucket(Instant until,int used) { }
    private final Clock clock;
    private final int requests,maxClients;
    private final Duration window;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<String,Bucket> buckets = new LinkedHashMap<>();
    public RequestBudget(Clock clock,int requests,Duration window,int maxClients) {
        if (requests<1 || maxClients<1 || window==null || window.isNegative() || window.isZero()) throw new IllegalArgumentException("Positive rate settings required");
        this.clock=clock; this.requests=requests; this.window=window; this.maxClients=maxClients;
    }
    public long take(String client) {
        var now=clock.instant();
        lock.lock();
        try {
            while (!buckets.isEmpty() && !buckets.firstEntry().getValue().until().isAfter(now)) buckets.pollFirstEntry();
            var bucket=buckets.get(client);
            if (bucket!=null && !bucket.until().isAfter(now)) {
                buckets.remove(client);
                bucket=null;
            }
            if (bucket==null && buckets.size()>=maxClients) return seconds(now,buckets.firstEntry().getValue().until());
            if (bucket==null) bucket=new Bucket(now.plus(window),0);
            if (bucket.used()>=requests) return seconds(now,bucket.until());
            buckets.put(client,new Bucket(bucket.until(),bucket.used()+1)); return 0;
        } finally {
            lock.unlock();
        }
    }
    private static long seconds(Instant now,Instant until) { return Math.max(1,(Duration.between(now,until).toMillis()+999)/1000); }
}
