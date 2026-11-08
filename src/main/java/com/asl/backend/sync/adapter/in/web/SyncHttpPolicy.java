package com.asl.backend.sync.adapter.in.web;
import com.asl.backend.platform.application.api.*;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import org.springframework.web.servlet.HandlerInterceptor;

public final class SyncHttpPolicy implements HandlerInterceptor {
    private final RequestBudget budget=new RequestBudget(Clock.systemUTC(),120,Duration.ofMinutes(1),10000);
    public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        response.setHeader("Cache-Control","private, no-store");
        long retry=budget.take(Objects.toString(request.getRemoteAddr(),"unknown"));
        if(retry>0) { response.setHeader("Retry-After",Long.toString(retry)); throw new ApplicationFailure(ApplicationFailure.Kind.RATE_LIMITED,"RATE_LIMITED","Too many sync requests"); }
        Set<String> allowed=request.getRequestURI().endsWith("/snapshot")?Set.of("cursor","limit"):request.getRequestURI().endsWith("/changes")?Set.of("since","cursor","limit"):Set.of();
        for(var param:request.getParameterMap().entrySet()) if(!allowed.contains(param.getKey())||param.getValue().length!=1) throw new ApplicationFailure(ApplicationFailure.Kind.BAD_REQUEST,"BAD_REQUEST","Unknown or repeated query parameter");
        if(Collections.list(request.getHeaders("Authorization")).size()>1) throw new ApplicationFailure(ApplicationFailure.Kind.UNAUTHENTICATED,"UNAUTHORIZED","Ambiguous credentials");
        return true;
    }
}
