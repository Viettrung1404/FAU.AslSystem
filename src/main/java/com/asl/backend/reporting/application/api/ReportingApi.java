package com.asl.backend.reporting.application.api;

import java.time.Instant;
import java.util.*;

public interface ReportingApi {
    Receipt submit(Command command, UUID owner);
    record Command(UUID clientReportId, String targetType, UUID targetId, UUID lessonVersionId,
                   String reason, String message, Map<String,Object> practiceContext) { }
    record Receipt(UUID id, String status, Instant receivedAt) { }
}
