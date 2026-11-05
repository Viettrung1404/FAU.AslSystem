package com.asl.backend.practice.adapter.in.web;
import com.asl.backend.practice.application.api.PracticeApi;
import com.asl.backend.learning.application.api.PracticeContentReader.Source;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
public class PracticeController {
    private final PracticeApi api;
    public PracticeController(PracticeApi api) { this.api=api; }
    public record Pair(@NotNull UUID lessonId,@NotNull UUID versionId) { Source source() { return new Source(lessonId,versionId); } }
    public record Availability(@NotNull @Size(max=10000) List<@NotNull @Valid Pair> completedLessons) { }
    public record Generate(@NotNull String mode,@Min(1) @Max(30) Integer itemCount,@NotNull @Size(max=10000) List<@NotNull @Valid Pair> completedLessons) { }
    @PostMapping("/api/v1/practice/availability") public Map<String,Object> availability(@Valid @RequestBody Availability request) { return api.availability(request.completedLessons().stream().map(Pair::source).toList()); }
    @PostMapping("/api/v1/practice/sets") public Map<String,Object> generate(@Valid @RequestBody Generate request) { return api.generate(request.mode(),request.itemCount(),request.completedLessons().stream().map(Pair::source).toList()); }
}
