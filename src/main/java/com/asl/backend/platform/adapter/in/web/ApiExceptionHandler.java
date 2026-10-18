package com.asl.backend.platform.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<Map<String, Object>> badRequest(Exception exception, HttpServletRequest request) {
        return ApiErrors.response(400, "BAD_REQUEST", "Malformed request", request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException exception,
                                                  HttpServletRequest request) {
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("path", "/" + error.getField().replace(".", "/")
                        .replace("[", "/").replace("]", ""),
                        "message", error.getDefaultMessage() == null ? "Invalid field" : error.getDefaultMessage()))
                .toList();
        return ApiErrors.response(422, "VALIDATION_FAILED", "Request is invalid", request, fields);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(HttpServletRequest request) {
        return ApiErrors.response(404, "NOT_FOUND", "Resource not found", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof org.springframework.web.ErrorResponse error) {
            int status = error.getStatusCode().value();
            String code = switch (status) {
                case 400 -> "BAD_REQUEST";
                case 404 -> "NOT_FOUND";
                case 405 -> "METHOD_NOT_ALLOWED";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                default -> "HTTP_ERROR";
            };
            var response = ApiErrors.response(status, code, "Request could not be processed", request, List.of());
            return ResponseEntity.status(status).headers(error.getHeaders())
                    .headers(response.getHeaders()).body(response.getBody());
        }
        LOG.error("Unhandled request failure", exception);
        return ApiErrors.response(500, "INTERNAL_ERROR", "Unexpected server error", request, List.of());
    }
}
