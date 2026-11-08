package com.asl.backend.sync.application.api;

import com.asl.backend.platform.application.api.ApplicationFailure;

public final class SyncFailure extends ApplicationFailure {
    private final String path;
    private final Long currentRevision;
    public SyncFailure(Kind kind, String code, String message, String path, Long currentRevision) {
        super(kind,code,message); this.path=path; this.currentRevision=currentRevision;
    }
    public String path() { return path; }
    public Long currentRevision() { return currentRevision; }
}
