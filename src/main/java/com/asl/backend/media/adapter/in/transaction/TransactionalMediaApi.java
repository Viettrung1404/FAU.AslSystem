package com.asl.backend.media.adapter.in.transaction;

import com.asl.backend.media.application.api.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** REQUIRED propagation joins the publisher's transaction, including its rollback. */
public final class TransactionalMediaApi implements MediaApi {
    private final MediaApi delegate;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public TransactionalMediaApi(MediaApi delegate, PlatformTransactionManager manager) {
        this.delegate = delegate;
        this.read = new TransactionTemplate(manager);
        this.read.setReadOnly(true);
        this.write = new TransactionTemplate(manager);
    }

    @Override public List<MediaItem> refresh(List<UUID> ids) {
        return execute(read, () -> delegate.refresh(ids));
    }
    @Override public void publish(UUID id) { execute(write, () -> { delegate.publish(id); return null; }); }
    @Override public void revoke(UUID id) { execute(write, () -> { delegate.revoke(id); return null; }); }

    private <T> T execute(TransactionTemplate transaction, java.util.function.Supplier<T> action) {
        try {
            return transaction.execute(status -> action.get());
        } catch (DataAccessResourceFailureException | TransientDataAccessException | TransactionException failure) {
            throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE, "SERVICE_UNAVAILABLE", "Media service unavailable");
        }
    }
}
