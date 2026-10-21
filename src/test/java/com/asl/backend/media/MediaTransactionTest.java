package com.asl.backend.media;

import com.asl.backend.media.adapter.in.transaction.TransactionalMediaApi;
import com.asl.backend.media.application.MediaService;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import static org.assertj.core.api.Assertions.*;

class MediaTransactionTest {
    @Test void cannotOpenDatabaseTransactionReturnsUnavailableWithoutCredentials() {
        var manager = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                throw new CannotCreateTransactionException("secret connection details");
            }
            public void commit(TransactionStatus status) { throw new AssertionError("No transaction was opened"); }
            public void rollback(TransactionStatus status) { throw new AssertionError("No transaction was opened"); }
        };
        var api = new TransactionalMediaApi(new MediaService(new MediaServiceTest.MemoryAssets(),
                key -> { throw new AssertionError("Do not contact provider when database is down"); }, Clock.systemUTC()), manager);
        assertThatThrownBy(() -> api.refresh(List.of(UUID.randomUUID())))
                .isInstanceOfSatisfying(ApplicationFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("SERVICE_UNAVAILABLE");
                    assertThat(failure.getMessage()).doesNotContain("secret", "connection details");
                });
    }
}
