package com.asl.backend.learning.adapter.in.web;

import com.asl.backend.learning.application.api.LearningApi;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class LearningController {
    private final LearningApi api;
    public LearningController(LearningApi api) { this.api = api; }
    @GetMapping("/api/v1/lessons/{lessonId}")
    public Map<String, Object> content(@PathVariable String lessonId, @RequestParam(required=false) String versionId) {
        return api.content(uuid(lessonId), versionId == null ? null : uuid(versionId));
    }
    private UUID uuid(String value) {
        if (!value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new ApplicationFailure(ApplicationFailure.Kind.BAD_REQUEST, "BAD_REQUEST", "Invalid lesson or version ID");
        return UUID.fromString(value);
    }
}
