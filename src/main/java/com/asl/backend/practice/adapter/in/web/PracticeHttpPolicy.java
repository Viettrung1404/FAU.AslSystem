package com.asl.backend.practice.adapter.in.web;
import com.asl.backend.platform.application.api.*;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import org.springframework.web.servlet.HandlerInterceptor;
public final class PracticeHttpPolicy implements HandlerInterceptor {
    private final RequestBudget budget;
    public PracticeHttpPolicy(Clock clock,int requests,Duration window,int clients) { budget=new RequestBudget(clock,requests,window,clients); }
    public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        response.setHeader("Cache-Control","no-store");
        long retry=budget.take(Objects.toString(request.getRemoteAddr(),"unknown"));
        if(retry>0) { response.setHeader("Retry-After",Long.toString(retry)); throw new ApplicationFailure(ApplicationFailure.Kind.RATE_LIMITED,"RATE_LIMITED","Too many practice requests"); }
        if(!request.getParameterMap().isEmpty()) throw new ApplicationFailure(ApplicationFailure.Kind.BAD_REQUEST,"BAD_REQUEST","Practice does not accept query parameters");
        return true;
    }
}
