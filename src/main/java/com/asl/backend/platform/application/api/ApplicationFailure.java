package com.asl.backend.platform.application.api;

/** Framework-independent failures shared with inbound adapters. */
public class ApplicationFailure extends RuntimeException {
    public enum Kind { INVALID_INPUT, NOT_FOUND, REMOVED, UNAVAILABLE, RATE_LIMITED }
    private final Kind kind;
    private final String code;
    public ApplicationFailure(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }
    public Kind kind() { return kind; }
    public String code() { return code; }
}
