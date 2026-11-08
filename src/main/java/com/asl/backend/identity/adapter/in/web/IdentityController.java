package com.asl.backend.identity.adapter.in.web;

import com.asl.backend.identity.application.api.IdentityApi;
import com.asl.backend.platform.application.api.ApplicationFailure;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class IdentityController {
    private final IdentityApi identity;
    public IdentityController(IdentityApi identity) { this.identity = identity; }
    public record Bootstrap(@NotNull String timeZone, @NotNull @Size(max=10) List<@NotNull UUID> acceptedDocumentIds) { }
    @GetMapping("/api/v1/legal-documents")
    public IdentityApi.LegalDocuments legal() { return identity.legalDocuments(); }
    @PostMapping("/api/v1/auth/bootstrap")
    public IdentityApi.Account bootstrap(@RequestHeader(value="Authorization", required=false) String authorization,
            @Valid @RequestBody Bootstrap body) {
        return identity.bootstrap(authorization, body.timeZone(), body.acceptedDocumentIds());
    }
    @GetMapping("/api/v1/me")
    public IdentityApi.Account profile(@RequestHeader(value="Authorization", required=false) String authorization) {
        return identity.requireAccount(authorization);
    }
    @PatchMapping("/api/v1/me")
    public IdentityApi.Account update(@RequestHeader(value="Authorization", required=false) String authorization,
            @RequestBody Map<String, Object> body) {
        if (body == null || !body.keySet().equals(Set.of("name")) || (body.get("name") != null && !(body.get("name") instanceof String)))
            throw new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT, "VALIDATION_FAILED", "Only the required name field may be updated");
        return identity.updateName(authorization, (String) body.get("name"));
    }
}
