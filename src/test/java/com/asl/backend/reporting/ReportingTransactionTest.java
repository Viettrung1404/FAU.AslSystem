package com.asl.backend.reporting;
import com.asl.backend.reporting.adapter.in.transaction.TransactionalReportingApi;
import com.asl.backend.reporting.application.api.ReportingApi;
import com.asl.backend.platform.application.api.ApplicationFailure;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ReportingTransactionTest {
    @Test void usesWriteReadCommittedTransactionAndDoesNotExposeSensitiveDatabaseDiagnostics() {
        var manager=new Manager(); var delegate=mock(ReportingApi.class); var api=new TransactionalReportingApi(delegate,manager);
        api.submit(null,null); assertThat(manager.definition.isReadOnly()).isFalse();
        assertThat(manager.definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        doThrow(new DataIntegrityViolationException("private report body")).when(delegate).submit(any(),any());
        assertThatThrownBy(()->api.submit(null,null)).isInstanceOfSatisfying(ApplicationFailure.class,f->{
            assertThat(f.code()).isEqualTo("VALIDATION_FAILED"); assertThat(f.getMessage()).doesNotContain("private"); assertThat(f.getCause()).isNull();
        });
        assertThat(manager.rollbacks).isEqualTo(1);
    }
    static class Manager implements PlatformTransactionManager {
        TransactionDefinition definition; int rollbacks;
        public TransactionStatus getTransaction(TransactionDefinition definition) { this.definition=definition; return new SimpleTransactionStatus(); }
        public void commit(TransactionStatus status) { }
        public void rollback(TransactionStatus status) { rollbacks++; }
    }
}
