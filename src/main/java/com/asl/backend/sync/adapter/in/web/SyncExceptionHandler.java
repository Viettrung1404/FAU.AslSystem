package com.asl.backend.sync.adapter.in.web;
import com.asl.backend.sync.application.api.SyncFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(-10)
@RestControllerAdvice(assignableTypes=SyncController.class)
public final class SyncExceptionHandler {
    @ExceptionHandler(SyncFailure.class) public ResponseEntity<Map<String,Object>> handle(SyncFailure failure,HttpServletRequest request) {
        int status=switch(failure.kind()) { case BAD_REQUEST->400; case INVALID_INPUT->422; case CONFLICT->409; case REMOVED->410; case NOT_FOUND->404; case UNAVAILABLE->503; default->500; };
        String requestId=Objects.toString(request.getAttribute("com.asl.backend.platform.adapter.in.web.RequestIdFilter.id"),UUID.randomUUID().toString());
        Map<String,Object> error=new LinkedHashMap<>(); error.put("code",failure.code()); error.put("message",failure.getMessage()); error.put("requestId",requestId);
        if(failure.path()!=null) error.put("fields",List.of(Map.of("path",failure.path(),"message",failure.getMessage())));
        if(failure.currentRevision()!=null) error.put("currentRevision",failure.currentRevision());
        return ResponseEntity.status(status).header("X-Request-Id",requestId).body(Map.of("error",error));
    }
}
