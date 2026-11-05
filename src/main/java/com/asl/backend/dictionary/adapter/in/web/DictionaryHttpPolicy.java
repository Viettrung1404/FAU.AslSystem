package com.asl.backend.dictionary.adapter.in.web;
import com.asl.backend.platform.application.api.ApplicationFailure;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import org.springframework.web.servlet.HandlerInterceptor;

public final class DictionaryHttpPolicy implements HandlerInterceptor {
    private final com.asl.backend.platform.application.api.RequestBudget budget;
    public DictionaryHttpPolicy(Clock clock,int requests,Duration window,int clients) { budget = new com.asl.backend.platform.application.api.RequestBudget(clock,requests,window,clients); }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        response.setHeader("Cache-Control",path.matches("/api/v1/collections/[^/]+/signs") ? "private, no-store" : "no-store");
        long retry = budget.take(Objects.toString(request.getRemoteAddr(),"unknown"));
        if (retry > 0) { response.setHeader("Retry-After",Long.toString(retry)); throw new ApplicationFailure(ApplicationFailure.Kind.RATE_LIMITED,"RATE_LIMITED","Too many dictionary requests"); }
        Set<String> allowed = path.equals("/api/v1/search") ? Set.of("q","type","cursor","limit")
            : path.equals("/api/v1/signs") || path.equals("/api/v1/collections") || path.endsWith("/signs") ? Set.of("cursor","limit") : Set.of();
        for (var entry : request.getParameterMap().entrySet()) if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1)
            throw new ApplicationFailure(ApplicationFailure.Kind.BAD_REQUEST,"BAD_REQUEST","Unknown or repeated query parameter");
        if (request.getHeaders("Authorization") != null && Collections.list(request.getHeaders("Authorization")).size()>1)
            throw new ApplicationFailure(ApplicationFailure.Kind.UNAUTHENTICATED,"UNAUTHORIZED","Ambiguous credentials");
        return true;
    }
}
