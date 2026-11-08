package com.asl.backend.identity.adapter.out.persistence;

import com.asl.backend.identity.application.api.IdentityApi.Account;
import com.asl.backend.identity.application.api.IdentityApi.LegalDocument;
import com.asl.backend.identity.application.port.out.AccountRepository;
import com.asl.backend.identity.application.port.out.IdTokenVerifier.Identity;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.asl.backend.platform.application.api.ApplicationFailure.Kind.*;

@Repository
public class JdbcAccountRepository implements AccountRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public JdbcAccountRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager);
    }
    @Override public Stored findByUid(String uid) {
        var rows = jdbc.query("select id,email,display_name,suspended_at from asl.app_users where firebase_uid=?",
            (rs, n) -> new Stored(new Account(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name")),
                rs.getTimestamp("suspended_at") != null), uid);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    @Override public List<LegalDocument> legalDocuments() {
        return jdbc.query("""
            select distinct on (document_type) id,document_type,version,public_url,published_at
            from asl.legal_documents where published_at <= now()
            order by document_type,published_at desc,id desc
            """, (rs, n) -> new LegalDocument(rs.getObject("id", UUID.class), rs.getString("document_type"),
                rs.getString("version"), rs.getString("public_url"), rs.getTimestamp("published_at").toInstant()));
    }
    @Override public Account bootstrap(Identity identity, String zone, List<UUID> accepted) {
        return transaction.execute(status -> {
            // The account may not exist yet: serialize bootstrap by UID across application instances.
            jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, identity.uid());
            var stored = findByUid(identity.uid());
            if (stored != null && stored.suspended())
                throw new ApplicationFailure(FORBIDDEN, "FORBIDDEN", "Account is suspended");
            var documents = legalDocuments();
            Map<UUID, LegalDocument> current = new HashMap<>();
            documents.forEach(document -> current.put(document.documentId(), document));
            if (accepted.stream().anyMatch(id -> !current.containsKey(id)))
                throw new ApplicationFailure(INVALID_INPUT, "VALIDATION_FAILED", "Accept only currently published legal document versions");
            var required = documents.stream().filter(d -> Set.of("TERMS", "PRIVACY").contains(d.type())).toList();
            if (stored == null && required.size() != 2)
                throw new ApplicationFailure(UNAVAILABLE, "SERVICE_UNAVAILABLE", "Current Terms and Privacy documents must be configured");
            Set<UUID> consent = new HashSet<>(accepted);
            if (stored != null) consent.addAll(jdbc.query("select document_id from asl.user_consents where user_id=?",
                (rs, n) -> rs.getObject(1, UUID.class), stored.account().id()));
            if (required.stream().anyMatch(d -> !consent.contains(d.documentId())))
                throw new ApplicationFailure(INVALID_INPUT, "VALIDATION_FAILED", "Current Terms and Privacy consent is required");
            Account account;
            if (stored == null) {
                String name = identity.name();
                if (name != null && name.codePointCount(0, name.length()) > 100)
                    name = name.substring(0, name.offsetByCodePoints(0, 100));
                UUID id = UUID.randomUUID();
                jdbc.update("insert into asl.app_users(id,firebase_uid,email,display_name,time_zone) values (?,?,?,?,?)",
                    id, identity.uid(), identity.email(), name, zone);
                account = new Account(id, identity.email(), name);
            } else account = stored.account();
            for (UUID document : accepted)
                jdbc.update("insert into asl.user_consents(user_id,document_id) values (?,?) on conflict do nothing", account.id(), document);
            jdbc.update("insert into asl.sync_streams(user_id) values (?) on conflict do nothing", account.id());
            return account;
        });
    }
    @Override public Account updateName(UUID id, String name) {
        var rows = jdbc.query("update asl.app_users set display_name=?,updated_at=now() where id=? and suspended_at is null returning id,email,display_name",
            (rs, n) -> new Account(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name")), name, id);
        if (rows.isEmpty()) throw new ApplicationFailure(FORBIDDEN, "FORBIDDEN", "Account is unavailable");
        return rows.getFirst();
    }
}
