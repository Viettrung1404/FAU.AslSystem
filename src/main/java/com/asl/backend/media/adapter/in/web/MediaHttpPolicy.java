package com.asl.backend.media.adapter.in.web;

import java.time.*;
import jakarta.servlet.http.*;
import org.springframework.web.servlet.HandlerInterceptor;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.HashMap;
import java.util.Map;

public class MediaHttpPolicy implements HandlerInterceptor {
    private final Clock clock;
    private final int limit;
    private final Duration window;
    private final int maxClients;
    private final Map<String, Bucket> clients = new HashMap<>();

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
        if (clients.size() >= maxClients) clients.values().removeIf(bucket -> !bucket.until.isAfter(now));
        String key = request.getRemoteAddr();
        var bucket = clients.get(key);
        if (bucket == null || !bucket.until.isAfter(now)) {
            if (bucket == null && clients.size() >= maxClients) throw limited();
            bucket = new Bucket(now.plus(window));
            clients.put(key, bucket);
        }
        if (bucket.used >= limit) {
            response.setHeader("Retry-After", Long.toString(Math.max(1, Duration.between(now, bucket.until).toSeconds())));
            throw limited();
        }
        bucket.used++;
        return true;
    }

    private static ApplicationFailure limited() {
        return new ApplicationFailure(ApplicationFailure.Kind.RATE_LIMITED, "RATE_LIMITED", "Too many media requests");
    }
    private static final class Bucket {
        private final Instant until;
        private int used;
        private Bucket(Instant until) { this.until = until; }
    }
}
