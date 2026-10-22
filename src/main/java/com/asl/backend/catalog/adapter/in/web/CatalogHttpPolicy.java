package com.asl.backend.catalog.adapter.in.web;
import java.time.*;
import jakarta.servlet.http.*;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
public final class CatalogHttpPolicy implements HandlerInterceptor {
    private final Clock clock;
    private final int budget;
    private final Duration window;
    private final int maxClients;
    private final Map<String, Bucket> clients = new HashMap<>();
    private final NavigableSet<Bucket> expiry = new TreeSet<>(Comparator.comparing((Bucket b) -> b.until).thenComparing(b -> b.ip));
    public CatalogHttpPolicy(Clock clock, int budget, Duration window, int maxClients) {
        if (budget < 1 || window == null || window.isZero() || window.isNegative() || maxClients < 1)
            throw new IllegalArgumentException("Catalog rate settings must be positive");
        this.clock = clock; this.budget = budget; this.window = window; this.maxClients = maxClients;
    }
    @Override public synchronized boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("Cache-Control", "no-store");
        var now = clock.instant(); String ip = Objects.toString(request.getRemoteAddr(), "unknown");
        var bucket = clients.get(ip);
        if (bucket == null || !bucket.until.isAfter(now)) {
            if (bucket != null) expiry.remove(bucket);
            else if (clients.size() >= maxClients) {
                if (expiry.first().until.isAfter(now)) throw limited(response, now, expiry.first().until);
                clients.remove(expiry.pollFirst().ip);
            }
            bucket = new Bucket(ip, now.plus(window)); clients.put(ip, bucket); expiry.add(bucket);
        }
        if (bucket.used >= budget) throw limited(response, now, bucket.until);
        bucket.used++;
        boolean paged = Set.of("/api/v1/home", "/api/v1/units").contains(request.getRequestURI().substring(request.getContextPath().length()));
        var allowed = paged ? Set.of("cursor", "limit") : Set.<String>of();
        for (var entry : request.getParameterMap().entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1)
                throw new ApplicationFailure(ApplicationFailure.Kind.BAD_REQUEST, "BAD_REQUEST", "Unknown or repeated query parameter");
        }
        return true;
    }
    private static ApplicationFailure limited(HttpServletResponse response, Instant now, Instant until) {
        var remaining = Duration.between(now, until);
        response.setHeader("Retry-After", Long.toString(Math.max(1, remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1))));
        return new ApplicationFailure(ApplicationFailure.Kind.RATE_LIMITED, "RATE_LIMITED", "Too many catalog requests");
    }
    private static final class Bucket {
        final String ip; final Instant until; int used;
        Bucket(String ip, Instant until) { this.ip = ip; this.until = until; }
    }
}
