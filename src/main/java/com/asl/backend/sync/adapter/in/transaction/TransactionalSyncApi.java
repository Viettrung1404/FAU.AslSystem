package com.asl.backend.sync.adapter.in.transaction;
import com.asl.backend.sync.application.api.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransactionalSyncApi implements SyncApi {
    private final SyncApi delegate; private final TransactionTemplate read,write;
    public TransactionalSyncApi(SyncApi delegate,PlatformTransactionManager manager) {
        this.delegate=delegate; write=new TransactionTemplate(manager);
        read=new TransactionTemplate(manager); read.setReadOnly(true); read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private Map<String,Object> execute(TransactionTemplate tx,Supplier<Map<String,Object>> work) {
        try { return tx.execute(s->work.get()); }
        catch(DataIntegrityViolationException e) { throw new SyncFailure(ApplicationFailure.Kind.INVALID_INPUT,"VALIDATION_FAILED","Mutation violates frozen-content scoring, checkpoint progression or catalog references","/mutations",null); }
        catch(DataAccessResourceFailureException|TransientDataAccessException|TransactionException e) { throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE,"SERVICE_UNAVAILABLE","Sync temporarily unavailable",e); }
    }
    public Map<String,Object> push(UUID owner,Map<String,Object> batch) { return execute(write,()->delegate.push(owner,batch)); }
    public Map<String,Object> snapshot(UUID owner,String cursor,int limit) { return execute(read,()->delegate.snapshot(owner,cursor,limit)); }
    public Map<String,Object> changes(UUID owner,String since,String cursor,int limit) { return execute(read,()->delegate.changes(owner,since,cursor,limit)); }
    public Map<String,Object> stats(UUID owner) { return execute(read,()->delegate.stats(owner)); }
}
