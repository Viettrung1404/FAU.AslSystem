package com.asl.backend.catalog.adapter.in.transaction;
import com.asl.backend.catalog.application.api.*;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import com.asl.backend.platform.application.api.ApplicationFailure;
public final class TransactionalCatalogApi implements CatalogApi {
    private final CatalogApi delegate;
    private final TransactionTemplate read;
    public TransactionalCatalogApi(CatalogApi delegate, PlatformTransactionManager manager) {
        this.delegate = delegate;
        read = new TransactionTemplate(manager); read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public CatalogData.Config config() { return execute(delegate::config); }
    public CatalogData.Page units(String cursor, int limit) { return execute(() -> delegate.units(cursor, limit)); }
    public CatalogData.Unit unit(UUID id) { return execute(() -> delegate.unit(id)); }
    private <T> T execute(java.util.function.Supplier<T> operation) {
        try { return read.execute(status -> operation.get()); }
        catch (DataAccessResourceFailureException | TransientDataAccessException | TransactionException failure) {
            throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE, "SERVICE_UNAVAILABLE", "Catalog temporarily unavailable", failure);
        }
    }
}
