package com.asl.backend.practice.adapter.in.transaction;
import com.asl.backend.practice.application.api.PracticeApi;
import com.asl.backend.learning.application.api.PracticeContentReader.Source;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
public final class TransactionalPracticeApi implements PracticeApi {
    private final PracticeApi delegate; private final TransactionTemplate read;
    public TransactionalPracticeApi(PracticeApi delegate,PlatformTransactionManager manager) {
        this.delegate=delegate; read=new TransactionTemplate(manager); read.setReadOnly(true); read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private Map<String,Object> execute(Supplier<Map<String,Object>> work) {
        try { return read.execute(s->work.get()); }
        catch(DataAccessResourceFailureException | TransientDataAccessException | TransactionException e) { throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE,"SERVICE_UNAVAILABLE","Practice unavailable",e); }
    }
    public Map<String,Object> availability(List<Source> sources) { return execute(()->delegate.availability(sources)); }
    public Map<String,Object> generate(String mode,Integer count,List<Source> sources) { return execute(()->delegate.generate(mode,count,sources)); }
}
