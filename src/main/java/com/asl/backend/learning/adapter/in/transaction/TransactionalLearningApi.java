package com.asl.backend.learning.adapter.in.transaction;

import com.asl.backend.learning.application.api.LearningApi;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransactionalLearningApi implements LearningApi {
    private final LearningApi delegate;
    private final TransactionTemplate read, write;
    public TransactionalLearningApi(LearningApi delegate, PlatformTransactionManager manager) {
        this.delegate = delegate; read = new TransactionTemplate(manager); read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); write = new TransactionTemplate(manager);
    }
    private <T> T execute(TransactionTemplate tx, Supplier<T> action) {
        try { return tx.execute(status -> action.get()); }
        catch (DataAccessResourceFailureException | TransientDataAccessException | TransactionException failure) {
            throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE, "SERVICE_UNAVAILABLE", "Learning service unavailable", failure);
        }
    }
    @Override public Map<String, Object> content(UUID lessonId, UUID versionId) { return execute(read, () -> delegate.content(lessonId, versionId)); }
    @Override public void publish(UUID id) { execute(write, () -> { delegate.publish(id); return null; }); }
    @Override public void revoke(UUID id) { execute(write, () -> { delegate.revoke(id); return null; }); }
}
