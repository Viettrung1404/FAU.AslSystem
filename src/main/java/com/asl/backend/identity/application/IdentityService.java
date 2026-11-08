package com.asl.backend.identity.application;

import com.asl.backend.identity.application.api.IdentityApi;
import com.asl.backend.identity.application.port.out.AccountRepository;
import com.asl.backend.identity.application.port.out.IdTokenVerifier;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static com.asl.backend.platform.application.api.ApplicationFailure.Kind.*;

public final class IdentityService implements IdentityApi {
    private final IdTokenVerifier verifier;
    private final AccountRepository accounts;
    public IdentityService(IdTokenVerifier verifier, AccountRepository accounts) {
        this.verifier = verifier; this.accounts = accounts;
    }
    private IdTokenVerifier.Identity identity(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7))
            throw new ApplicationFailure(UNAUTHENTICATED, "UNAUTHORIZED", "Firebase sign-in is required");
        String token = authorization.substring(7);
        if (token.isBlank() || token.length() > 16384 || token.chars().anyMatch(Character::isWhitespace))
            throw new ApplicationFailure(UNAUTHENTICATED, "UNAUTHORIZED", "Invalid bearer credential");
        var identity = verifier.verify(token);
        if (identity == null || identity.uid() == null || identity.uid().isBlank() || identity.uid().length() > 128)
            throw new ApplicationFailure(UNAUTHENTICATED, "UNAUTHORIZED", "Invalid Firebase identity");
        if (identity.provider() == null || !Set.of("google.com", "apple.com").contains(identity.provider()))
            throw new ApplicationFailure(FORBIDDEN, "FORBIDDEN", "Sign-in provider is not allowed");
        return identity;
    }
    @Override public Account requireAccount(String authorization) {
        var stored = accounts.findByUid(identity(authorization).uid());
        if (stored == null) throw new ApplicationFailure(UNAUTHENTICATED, "UNAUTHORIZED", "Complete account setup first");
        if (stored.suspended()) throw new ApplicationFailure(FORBIDDEN, "FORBIDDEN", "Account is suspended");
        return stored.account();
    }
    @Override public Account optionalAccount(String authorization) {
        return authorization == null ? null : requireAccount(authorization);
    }
    @Override public Account bootstrap(String authorization, String timeZone, List<UUID> accepted) {
        var identity = identity(authorization);
        if (timeZone == null || !ZoneId.getAvailableZoneIds().contains(timeZone))
            throw new ApplicationFailure(INVALID_INPUT, "VALIDATION_FAILED", "timeZone must be an IANA timezone");
        if (accepted == null || accepted.size() > 10 || accepted.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(accepted).size() != accepted.size())
            throw new ApplicationFailure(INVALID_INPUT, "VALIDATION_FAILED", "acceptedDocumentIds must contain distinct document IDs");
        return accounts.bootstrap(identity, timeZone, List.copyOf(accepted));
    }
    @Override public Account updateName(String authorization, String name) {
        var account = requireAccount(authorization);
        if (name != null && name.codePointCount(0, name.length()) > 100)
            throw new ApplicationFailure(INVALID_INPUT, "VALIDATION_FAILED", "Name must be at most 100 characters");
        return accounts.updateName(account.id(), name);
    }
    @Override public LegalDocuments legalDocuments() { return new LegalDocuments(accounts.legalDocuments(), null); }
}
