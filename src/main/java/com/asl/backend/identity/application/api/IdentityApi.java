package com.asl.backend.identity.application.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IdentityApi {
    record Account(UUID id, String email, String name) implements java.io.Serializable { }
    record LegalDocument(UUID documentId, String type, String version, String url, Instant publishedAt) implements java.io.Serializable { }
    record LegalDocuments(List<LegalDocument> items, String nextCursor) implements java.io.Serializable { }
    Account requireAccount(String authorization);
    /** Returns null only when Authorization is absent. Invalid supplied credentials always fail. */
    Account optionalAccount(String authorization);
    Account bootstrap(String authorization, String timeZone, List<UUID> acceptedDocumentIds);
    Account updateName(String authorization, String name);
    LegalDocuments legalDocuments();
}
