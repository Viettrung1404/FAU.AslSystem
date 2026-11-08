package com.asl.backend.identity.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Database diagnostics can contain account data. Return a safe dependency failure without logging row values. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=IdentityController.class)
public class IdentityExceptionHandler {
    @ExceptionHandler({DataAccessException.class, org.springframework.transaction.TransactionException.class})
    ResponseEntity<Map<String,Object>> unavailable(HttpServletRequest request) {
        Object id=request.getAttribute("com.asl.backend.platform.adapter.in.web.RequestIdFilter.id");
        String requestId=id==null?UUID.randomUUID().toString():id.toString();
        return ResponseEntity.status(503).header("Cache-Control","private, no-store").header("X-Request-Id",requestId)
            .body(Map.of("error",Map.of("code","SERVICE_UNAVAILABLE","message","Account storage is temporarily unavailable","requestId",requestId)));
    }
}
