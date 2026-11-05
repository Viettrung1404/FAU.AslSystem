package com.asl.backend.dictionary.adapter.in.transaction;
import com.asl.backend.dictionary.application.api.DictionaryApi;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
public final class TransactionalDictionaryApi implements DictionaryApi {
    private final DictionaryApi delegate; private final TransactionTemplate read;
    public TransactionalDictionaryApi(DictionaryApi delegate,PlatformTransactionManager manager) {
        this.delegate=delegate; read=new TransactionTemplate(manager); read.setReadOnly(true); read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private Map<String,Object> execute(Supplier<Map<String,Object>> work) {
        try { return read.execute(s -> work.get()); }
        catch (DataAccessResourceFailureException | TransientDataAccessException | TransactionException e) {
            throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE,"SERVICE_UNAVAILABLE","Dictionary unavailable",e);
        }
    }
    public Map<String,Object> page(String s,UUID id,String q,String type,String c,int limit,String auth) { return execute(() -> delegate.page(s,id,q,type,c,limit,auth)); }
    public Map<String,Object> sign(UUID id) { return execute(() -> delegate.sign(id)); }
    public Map<String,Object> collection(UUID id) { return execute(() -> delegate.collection(id)); }
}
