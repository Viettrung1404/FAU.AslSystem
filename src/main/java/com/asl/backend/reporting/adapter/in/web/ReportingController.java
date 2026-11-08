package com.asl.backend.reporting.adapter.in.web;
import com.asl.backend.reporting.application.api.ReportingApi;
import com.asl.backend.identity.application.api.IdentityApi;
import com.asl.backend.platform.application.api.*;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController
public class ReportingController {
    private final ReportingApi api;
    private final IdentityApi identity;
    private final RequestBudget budget;
    public ReportingController(ReportingApi api,IdentityApi identity,
            @Value("${asl.reporting.rate-limit.requests:10}") int requests,
            @Value("${asl.reporting.rate-limit.window:PT1M}") Duration window,
            @Value("${asl.reporting.rate-limit.max-clients:10000}") int clients) {
        this.api=api; this.identity=identity; budget=new RequestBudget(Clock.systemUTC(),requests,window,clients);
    }
    public static class Request {
        public UUID clientReportId;
        public String targetType;
        public UUID targetId;
        @JsonSetter(nulls=Nulls.FAIL) public UUID lessonVersionId;
        public String reason;
        public String message;
        @JsonSetter(nulls=Nulls.FAIL) public Map<String,Object> practiceContext;
    }
    @PostMapping("/api/v1/reports") @ResponseStatus(HttpStatus.CREATED)
    public ReportingApi.Receipt submit(@RequestBody Request body,@RequestHeader(value="Authorization",required=false) String authorization,
                                       HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        // Apply the socket budget before expensive Firebase verification, then the verified account budget.
        long retry=budget.take("ip:"+Objects.toString(request.getRemoteAddr(),"unknown"));
        if(retry>0) { response.setHeader("Retry-After",Long.toString(retry)); throw new ApplicationFailure(ApplicationFailure.Kind.RATE_LIMITED,"RATE_LIMITED","Too many reports"); }
        var owner=identity.optionalAccount(authorization);
        if(owner!=null) retry=Math.max(retry,budget.take("account:"+owner.id()));
        if(retry>0) { response.setHeader("Retry-After",Long.toString(retry)); throw new ApplicationFailure(ApplicationFailure.Kind.RATE_LIMITED,"RATE_LIMITED","Too many reports"); }
        if(!request.getParameterMap().isEmpty()) throw new ApplicationFailure(ApplicationFailure.Kind.BAD_REQUEST,"BAD_REQUEST","Reports do not accept query parameters");
        return api.submit(new ReportingApi.Command(body.clientReportId,body.targetType,body.targetId,body.lessonVersionId,body.reason,body.message,body.practiceContext),owner==null?null:owner.id());
    }
}
