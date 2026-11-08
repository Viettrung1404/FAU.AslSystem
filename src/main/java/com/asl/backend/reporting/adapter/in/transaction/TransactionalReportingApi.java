package com.asl.backend.reporting.adapter.in.transaction;
import com.asl.backend.reporting.application.api.ReportingApi;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.UUID;
import org.springframework.dao.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
public final class TransactionalReportingApi implements ReportingApi {
    private final ReportingApi delegate;
    private final TransactionTemplate transaction;
    public TransactionalReportingApi(ReportingApi delegate,PlatformTransactionManager manager) {
        this.delegate=delegate; transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Receipt submit(Command command,UUID owner) {
        try { return transaction.execute(status->delegate.submit(command,owner)); }
        catch(DataAccessResourceFailureException | TransientDataAccessException | TransactionException failure) {
            throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE,"SERVICE_UNAVAILABLE","Report service unavailable",failure);
        }
        catch(DataIntegrityViolationException failure) {
            // Database diagnostics may include message/snapshot contents. Never let them reach generic error logging.
            throw new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT,"VALIDATION_FAILED","Report is invalid");
        }
        catch(DataAccessException failure) {
            throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE,"SERVICE_UNAVAILABLE","Report service unavailable");
        }
    }
}
