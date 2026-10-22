package com.asl.backend.media.adapter.in.web;

import java.time.*;
import jakarta.servlet.http.*;
import org.springframework.web.servlet.HandlerInterceptor;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.HashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.NavigableSet;
import java.util.TreeSet;

public class MediaHttpPolicy implements HandlerInterceptor {
    private final Clock clock;
    private final int limit;
    private final Duration window;
    private final int maxClients;
    private final Map<String, Bucket> clients = new HashMap<>();
    // Exactly one expiry entry per tracked client, including after its window is renewed.
    private final NavigableSet<Bucket> expirations = new TreeSet<>(Comparator
            .comparing((Bucket bucket) -> bucket.until)
            .thenComparing(bucket -> bucket.address, Comparator.nullsFirst(Comparator.naturalOrder())));

    public MediaHttpPolicy(Clock clock, int limit, Duration window, int maxClients) {
        if (limit < 1 || window == null || window.isZero() || window.isNegative() || maxClients < 1) {
            throw new IllegalArgumentException("Media rate limit settings must be positive");
        }
        this.clock = clock;
        this.limit = limit;
        this.window = window;
        this.maxClients = maxClients;
    }

    @Override public synchronized boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("Cache-Control", "no-store");
        var now = clock.instant();
        String key = request.getRemoteAddr();
        var bucket = clients.get(key);
        if (bucket == null || !bucket.until.isAfter(now)) {
            if (bucket != null) {
                expirations.remove(bucket);
            } else if (clients.size() >= maxClients) {
                var earliest = expirations.first();
                if (earliest.until.isAfter(now)) {
                    retryAfter(response, now, earliest.until);
                    throw limited();
                }
                // Reclaim only the slot needed by this request; never scan all clients.
                expirations.pollFirst();
                clients.remove(earliest.address);
            }
            bucket = new Bucket(key, now.plus(window));
            clients.put(key, bucket);
            expirations.add(bucket);
        }
        if (bucket.used >= limit) {
            retryAfter(response, now, bucket.until);
            throw limited();
        }
        bucket.used++;
        return true;
    }

    private static void retryAfter(HttpServletResponse response, Instant now, Instant until) {
        var remaining = Duration.between(now, until);
        long seconds = remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);
        response.setHeader("Retry-After", Long.toString(Math.max(1, seconds)));
    }

    private static ApplicationFailure limited() {
        return new ApplicationFailure(ApplicationFailure.Kind.RATE_LIMITED, "RATE_LIMITED", "Too many media requests");
    }
    private static final class Bucket {
        private final String address;
        private final Instant until;
        private int used;
        private Bucket(String address, Instant until) { this.address = address; this.until = until; }
    }
}
