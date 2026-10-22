package com.asl.backend.media.adapter.in.transaction;

import com.asl.backend.media.application.api.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Writes join the publisher's transaction. Standalone reads are scoped by the persistence adapter. */
public final class TransactionalMediaApi implements MediaApi {
    private final MediaApi delegate;
    private final TransactionTemplate write;

    public TransactionalMediaApi(MediaApi delegate, PlatformTransactionManager manager) {
        this.delegate = delegate;
        this.write = new TransactionTemplate(manager);
    }

    @Override public List<MediaItem> refresh(List<UUID> ids) {
        // Do not hold a new DB transaction while delivering URLs. If a caller already
        // owns a transaction, preserve it (including visibility of its uncommitted writes).
        return translateFailure(() -> delegate.refresh(ids));
    }
    @Override public void publish(UUID id) { execute(write, () -> { delegate.publish(id); return null; }); }
    @Override public void revoke(UUID id) { execute(write, () -> { delegate.revoke(id); return null; }); }

    private <T> T execute(TransactionTemplate transaction, java.util.function.Supplier<T> action) {
        return translateFailure(() -> transaction.execute(status -> action.get()));
    }

    private <T> T translateFailure(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessResourceFailureException | TransientDataAccessException | TransactionException failure) {
            throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE, "SERVICE_UNAVAILABLE", "Media service unavailable", failure);
        }
    }
}
