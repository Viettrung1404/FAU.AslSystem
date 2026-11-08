package com.asl.backend.identity.application.port.out;

import com.asl.backend.identity.application.api.IdentityApi.Account;
import com.asl.backend.identity.application.api.IdentityApi.LegalDocument;
import com.asl.backend.identity.application.port.out.IdTokenVerifier.Identity;
import java.util.List;
import java.util.UUID;

public interface AccountRepository {
    record Stored(Account account, boolean suspended) { }
    Stored findByUid(String uid);
    Account bootstrap(Identity identity, String timeZone, List<UUID> acceptedDocumentIds);
    Account updateName(UUID id, String name);
    List<LegalDocument> legalDocuments();
}
