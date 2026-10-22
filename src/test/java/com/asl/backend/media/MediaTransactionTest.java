package com.asl.backend.media;

import com.asl.backend.media.adapter.in.transaction.TransactionalMediaApi;
import com.asl.backend.media.application.MediaService;
import com.asl.backend.media.application.port.out.MediaUrlProvider;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;

class MediaTransactionTest {
    @Test void cannotOpenWriteTransactionReturnsUnavailableWithoutCredentials() {
        var outage = new CannotCreateTransactionException("secret connection details");
        var manager = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                throw outage;
            }
            public void commit(TransactionStatus status) { throw new AssertionError("No transaction was opened"); }
            public void rollback(TransactionStatus status) { throw new AssertionError("No transaction was opened"); }
        };
        var api = new TransactionalMediaApi(new MediaService(new MediaServiceTest.MemoryAssets(),
                key -> { throw new AssertionError("Do not contact provider when database is down"); }, Clock.systemUTC()), manager);
        assertThatThrownBy(() -> api.publish(UUID.randomUUID()))
                .isInstanceOfSatisfying(ApplicationFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("SERVICE_UNAVAILABLE");
                    assertThat(failure.getMessage()).doesNotContain("secret", "connection details");
                    assertThat(failure.getCause()).isSameAs(outage);
                });
    }

    @Test void readFailureIsTranslatedWithoutOpeningAnApiTransactionOrCallingProvider() {
        var outage = new DataAccessResourceFailureException("secret connection details");
        var repository = new MediaServiceTest.MemoryAssets() {
            @Override public java.util.List<com.asl.backend.media.domain.MediaAsset> findAll(java.util.Collection<UUID> ids) {
                throw outage;
            }
        };
        var api = new TransactionalMediaApi(new MediaService(repository,
                key -> { throw new AssertionError("Provider must not run after DB failure"); }, Clock.systemUTC()), noTransactions());
        assertThatThrownBy(() -> api.refresh(List.of(UUID.randomUUID())))
                .isInstanceOf(ApplicationFailure.class).hasCause(outage).hasMessage("Media service unavailable");
    }

    @Test void standaloneRefreshDoesNotWrapProviderInAnApiTransaction() {
        var fixture = new MediaServiceTest();
        var asset = fixture.asset("video/mp4", 1000, MediaServiceTest.NOW, null);
        var api = new TransactionalMediaApi(fixture.service(), noTransactions());
        assertThat(api.refresh(List.of(asset.id()))).hasSize(1);
        assertThat(fixture.deliveries).hasValue(1);
    }

    @Test void invalidInputIsRejectedBeforeRequestingDatabaseResources() {
        var fixture = new MediaServiceTest();
        var api = new TransactionalMediaApi(fixture.service(), noTransactions());
        assertThatThrownBy(() -> api.refresh(List.of())).isInstanceOf(ApplicationFailure.class)
                .hasMessage("Invalid assetIds");
        assertThat(fixture.repository.reads).isZero();
    }

    @Test void publicationAndRevocationStillUseRequiredWriteTransactions() {
        var fixture = new MediaServiceTest();
        var asset = fixture.asset("video/mp4", 1000, null, null);
        var begins = new java.util.concurrent.atomic.AtomicInteger();
        var commits = new java.util.concurrent.atomic.AtomicInteger();
        var manager = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
                assertThat(definition.isReadOnly()).isFalse();
                begins.incrementAndGet();
                return new SimpleTransactionStatus();
            }
            public void commit(TransactionStatus status) { commits.incrementAndGet(); }
            public void rollback(TransactionStatus status) { throw new AssertionError("Unexpected rollback"); }
        };
        var api = new TransactionalMediaApi(fixture.service(), manager);
        api.publish(asset.id());
        api.revoke(asset.id());
        assertThat(begins).hasValue(2);
        assertThat(commits).hasValue(2);
    }

    private PlatformTransactionManager noTransactions() {
        return new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { throw new AssertionError("No API transaction for refresh"); }
            public void commit(TransactionStatus status) { throw new AssertionError("No API transaction for refresh"); }
            public void rollback(TransactionStatus status) { throw new AssertionError("No API transaction for refresh"); }
        };
    }
}
