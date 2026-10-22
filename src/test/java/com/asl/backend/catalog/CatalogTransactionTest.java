package com.asl.backend.catalog;
import com.asl.backend.catalog.adapter.in.transaction.TransactionalCatalogApi;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;

class CatalogTransactionTest {
    @Test void responseAssemblyUsesReadOnlyRepeatableReadTransaction() {
        var manager = new RecordingManager();
        var api = new TransactionalCatalogApi(new CatalogServiceTest().service(true), manager);
        api.units(null, 20);
        assertThat(manager.definition).isNotNull();
        assertThat(manager.definition.isReadOnly()).isTrue();
        assertThat(manager.definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(manager.commits).isEqualTo(1);
    }
    @Test void unavailableDatabaseDoesNotExposeConnectionDetails() {
        var manager = new RecordingManager(); manager.fail = true;
        var api = new TransactionalCatalogApi(new CatalogServiceTest().service(true), manager);
        CatalogServiceTest.assertCode("SERVICE_UNAVAILABLE", api::config);
    }
    static class RecordingManager implements PlatformTransactionManager {
        TransactionDefinition definition; int commits; boolean fail;
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            if (fail) throw new CannotCreateTransactionException("secret credentials");
            this.definition = definition; return new SimpleTransactionStatus();
        }
        public void commit(TransactionStatus status) { commits++; }
        public void rollback(TransactionStatus status) { }
    }
}
