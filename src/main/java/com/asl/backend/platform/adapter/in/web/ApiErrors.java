package com.asl.backend.platform.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

final class ApiErrors {
    private ApiErrors() { }

    static ResponseEntity<Map<String, Object>> response(int status, String code, String message,
            HttpServletRequest request, List<Map<String, String>> fields) {
        Object id = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (id == null) id = UUID.randomUUID().toString();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("message", message);
        detail.put("requestId", id);
        if (!fields.isEmpty()) detail.put("fields", fields.stream().limit(100).toList());
        return ResponseEntity.status(status).header(RequestIdFilter.HEADER, id.toString())
                .body(Map.of("error", detail));
    }
}
