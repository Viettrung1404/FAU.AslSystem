package com.asl.backend.identity.adapter.in.web;

import com.asl.backend.platform.application.api.ApplicationFailure;
import com.asl.backend.platform.application.api.RequestBudget;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.Objects;
import org.springframework.web.servlet.HandlerInterceptor;

public final class IdentityHttpPolicy implements HandlerInterceptor {
    private final RequestBudget budget;
    public IdentityHttpPolicy(Clock clock, int requests, Duration window, int clients) {
        budget = new RequestBudget(clock, requests, window, clients);
    }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("Cache-Control", "private, no-store");
        long retry = budget.take(Objects.toString(request.getRemoteAddr(), "unknown"));
        if (retry > 0) {
            response.setHeader("Retry-After", Long.toString(retry));
            throw new ApplicationFailure(ApplicationFailure.Kind.RATE_LIMITED, "RATE_LIMITED", "Too many account requests");
        }
        if (!request.getParameterMap().isEmpty())
            throw new ApplicationFailure(ApplicationFailure.Kind.BAD_REQUEST, "BAD_REQUEST", "Unexpected query parameters");
        return true;
    }
}
