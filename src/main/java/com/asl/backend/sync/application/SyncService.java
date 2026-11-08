package com.asl.backend.sync.application;

import com.asl.backend.sync.application.api.*;
import com.asl.backend.sync.application.port.out.*;
import com.asl.backend.platform.application.api.ApplicationFailure.Kind;
import java.time.*;
import java.util.*;

public final class SyncService implements SyncApi {
    private final SyncRepository repository;
    private final SyncCursor cursors;
    private final Clock clock;
    public SyncService(SyncRepository repository, SyncCursor cursors, Clock clock) { this.repository=repository; this.cursors=cursors; this.clock=clock; }
    @Override public Map<String,Object> push(UUID owner, Map<String,Object> batch) {
        SyncValidation.exact(batch,Set.of("mutations"),"");
        var mutations=SyncValidation.array(batch.get("mutations"),1,100,"/mutations");
        Set<String> entities=new HashSet<>(); Set<UUID> ids=new HashSet<>();
        // Validate the entire wire shape before acquiring the per-account transaction lock.
        for(int i=0;i<mutations.size();i++) {
            var m=SyncValidation.object(mutations.get(i),"/mutations/"+i);
            SyncValidation.mutation(m,"/mutations/"+i);
            UUID id=SyncValidation.uuid(m.get("mutationId"),"/mutations/"+i+"/mutationId");
            if(!ids.add(id)||!entities.add(m.get("kind")+":"+m.get("entityKey"))) throw invalid("One mutation per ID and entity per batch","/mutations/"+i);
        }
        repository.lock(owner);
        List<Map<String,Object>> results=new ArrayList<>();
        for(int i=0;i<mutations.size();i++) {
            String path="/mutations/"+i;
            var m=SyncValidation.object(mutations.get(i),path);
            UUID id=UUID.fromString((String)m.get("mutationId"));
            var prior=repository.receipt(owner,id);
            if(prior.isPresent()) {
                if(!SyncValidation.equivalent(prior.get().body(),m)) throw new SyncFailure(Kind.CONFLICT,"MUTATION_ID_REUSED","Mutation ID was already used for another body",path+"/mutationId",null);
                results.add(Map.of("mutationId",id,"outcome","DUPLICATE","change",prior.get().change().wire()));
                continue;
            }
            String kind=(String)m.get("kind"), key=(String)m.get("entityKey");
            var current=repository.current(owner,kind,key);
            long revision=current.map(SyncRepository.Change::revision).orElse(0L);
            if(((Number)m.get("baseRevision")).longValue()!=revision) throw new SyncFailure(Kind.CONFLICT,"ENTITY_REVISION_CONFLICT","Entity revision changed",path+"/baseRevision",revision);
            if(kind.equals("LESSON_ATTEMPT")&&current.isPresent()&&!"IN_PROGRESS".equals(current.get().data().get("status"))&&SyncValidation.equivalent(current.get().data(),m.get("data"))) {
                repository.receipt(owner,id,m,current.get());
                results.add(Map.of("mutationId",id,"outcome","DUPLICATE","change",current.get().wire()));
                continue;
            }
            var change=repository.save(owner,kind,key,revision+1,SyncValidation.object(m.get("data"),path+"/data"),path+"/data");
            repository.receipt(owner,id,m,change);
            results.add(Map.of("mutationId",id,"outcome","APPLIED","change",change.wire()));
        }
        return Map.of("results",results);
    }
    @Override public Map<String,Object> snapshot(UUID owner,String cursor,int limit) {
        bounds(limit);
        var p=cursor==null ? new SyncCursor.Position("snapshot",repository.watermark(owner),0,clock.instant().plus(Duration.ofDays(90)).getEpochSecond(),0) : cursors.decode(owner,cursor);
        if(!p.mode().equals("snapshot")) throw badCursor();
        return page(owner,p,repository.snapshot(owner,p.watermark(),p.after(),limit+1),limit);
    }
    @Override public Map<String,Object> changes(UUID owner,String since,String cursor,int limit) {
        bounds(limit); var baseline=cursors.decode(owner,since);
        if(!baseline.mode().equals("sync")) throw badCursor();
        var p=cursor==null ? new SyncCursor.Position("changes",repository.watermark(owner),baseline.watermark(),baseline.expires(),baseline.watermark()) : cursors.decode(owner,cursor);
        if(!p.mode().equals("changes")||p.baseline()!=baseline.watermark()) throw badCursor();
        return page(owner,p,repository.changes(owner,p.watermark(),p.after(),limit+1),limit);
    }
    private Map<String,Object> page(UUID owner,SyncCursor.Position p,List<SyncRepository.Change> rows,int limit) {
        boolean more=rows.size()>limit; var shown=more ? rows.subList(0,limit) : rows;
        Map<String,Object> result=new LinkedHashMap<>(); result.put("items",shown.stream().map(SyncRepository.Change::wire).toList());
        result.put("nextCursor",more ? cursors.encode(owner,new SyncCursor.Position(p.mode(),p.watermark(),shown.getLast().sequence(),p.expires(),p.baseline())) : null);
        result.put("syncCursor",more ? null : cursors.encode(owner,new SyncCursor.Position("sync",p.watermark(),p.watermark(),clock.instant().plus(Duration.ofDays(90)).getEpochSecond(),p.watermark())));
        return result;
    }
    @Override public Map<String,Object> stats(UUID owner) { return repository.stats(owner); }
    static void bounds(int limit) { if(limit<1||limit>100) throw invalid("Limit must be between 1 and 100","/limit"); }
    static SyncFailure invalid(String message,String path) { return new SyncFailure(Kind.INVALID_INPUT,"VALIDATION_FAILED",message,path,null); }
    static SyncFailure badCursor() { return new SyncFailure(Kind.BAD_REQUEST,"BAD_REQUEST","Invalid sync cursor","/cursor",null); }
}
