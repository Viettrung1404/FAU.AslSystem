package com.asl.backend.practice.application;

import com.asl.backend.practice.application.api.PracticeApi;
import com.asl.backend.learning.application.api.PracticeContentReader;
import com.asl.backend.learning.application.api.PracticeContentReader.Source;
import com.asl.backend.practice.domain.PracticeGenerator;
import com.asl.backend.practice.domain.PracticeGenerator.Sign;
import com.asl.backend.media.application.api.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.time.*;
import java.util.*;
import java.util.random.RandomGenerator;

public final class PracticeService implements PracticeApi {
    public static final List<String> MODES=List.of("QUIZ","FLASHCARDS","SIGN_MATCHING","ALPHABET_MATCHING");
    private final PracticeContentReader content;
    private final MediaApi media;
    private final Clock clock;
    private final PracticeGenerator generator;
    private final Duration ttl;
    public PracticeService(PracticeContentReader content,MediaApi media,Clock clock,RandomGenerator random,Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Positive practice TTL required");
        this.content=content; this.media=media; this.clock=clock; this.generator=new PracticeGenerator(random); this.ttl=ttl;
    }
    private record Pool(List<Source> sources,List<Sign> signs,int distinct) { }
    private Pool pool(List<Source> sources) {
        if (sources==null || sources.size()>10000 || sources.stream().anyMatch(s -> s==null || s.lessonId()==null || s.versionId()==null)
                || new HashSet<>(sources).size()!=sources.size()) throw invalid();
        var ordered=sources.stream().sorted(Comparator.comparing((Source s)->s.lessonId().toString()).thenComparing(s->s.versionId().toString())).toList();
        Map<UUID,Sign> signs=new LinkedHashMap<>();
        for (var source:ordered) for (var sign:content.signs(source)) signs.putIfAbsent(sign.id(),new Sign(sign.id(),sign.name(),sign.videoAssetId(),sign.imageAssetId(),sign.alphabet()));
        return new Pool(List.copyOf(sources),List.copyOf(signs.values()),(int)sources.stream().map(Source::lessonId).distinct().count());
    }
    private Map<String,Object> mode(Pool pool,String mode) {
        int count=generator.eligible(pool.signs(),mode).size(),min=mode.equals("FLASHCARDS")?1:2;
        boolean unlocked=pool.distinct()>=3,can=unlocked && count>=min;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("mode",mode); result.put("unlocked",unlocked); result.put("canGenerate",can);
        result.put("reason",can?null:unlocked?"INSUFFICIENT_CONTENT":"NEED_MORE_LESSONS");
        result.put("eligibleSignCount",count); result.put("minimumSigns",min); result.put("defaultItemCount",10); result.put("maxItemCount",30); return result;
    }
    @Override public Map<String,Object> availability(List<Source> sources) {
        var pool=pool(sources); return Map.of("minimumDistinctLessons",3,"completedDistinctLessons",pool.distinct(),"modes",MODES.stream().map(m->mode(pool,m)).toList());
    }
    @Override public Map<String,Object> generate(String mode,Integer requested,List<Source> sources) {
        int count=requested==null?10:requested;
        if (mode==null || !MODES.contains(mode) || count<1 || count>30) throw invalid();
        var pool=pool(sources); var availability=mode(pool,mode);
        if (!(boolean)availability.get("canGenerate")) throw new ApplicationFailure(ApplicationFailure.Kind.CONFLICT,(String)availability.get("reason"),"Practice mode cannot be generated from these sources");
        var signs=generator.select(pool.signs(),mode,count);
        Set<UUID> assetIds=new LinkedHashSet<>();
        signs.forEach(s->{assetIds.add(s.videoAssetId()); if (mode.equals("QUIZ") && s.imageAssetId()!=null) assetIds.add(s.imageAssetId());});
        Map<UUID,MediaItem> urls=new HashMap<>(); var ids=new ArrayList<>(assetIds);
        try {
            for (int i=0;i<ids.size();i+=100) media.refresh(ids.subList(i,Math.min(i+100,ids.size()))).forEach(m->urls.put(m.assetId(),m));
        } catch (ApplicationFailure failure) {
            if (failure.kind()!=ApplicationFailure.Kind.NOT_FOUND && failure.kind()!=ApplicationFailure.Kind.REMOVED) throw failure;
            throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE,"SERVICE_UNAVAILABLE","Published practice media unavailable");
        }
        for (var sign:signs) {
            var video=urls.get(sign.videoAssetId());
            if (video==null || !video.mimeType().equals("video/mp4")) throw unavailableMedia();
            if (mode.equals("QUIZ") && sign.imageAssetId()!=null) {
                var image=urls.get(sign.imageAssetId());
                if (image==null || !Set.of("image/png","image/webp").contains(image.mimeType())) throw unavailableMedia();
            }
        }
        var items=generator.items(signs,mode,count,urls);
        var now=clock.instant(); Instant expiry=now.plus(ttl);
        for (var url:urls.values()) if (url.expiresAt()!=null && url.expiresAt().isBefore(expiry)) expiry=url.expiresAt();
        return Map.of("setId",UUID.randomUUID(),"mode",mode,"generatedAt",now,"expiresAt",expiry,"sourceLessons",pool.sources(),"items",items,"awardsLessonPoints",false,"countsForStreak",false);
    }
    private static ApplicationFailure invalid() { return new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT,"VALIDATION_FAILED","Invalid practice request or duplicate lesson/version pair"); }
    private static ApplicationFailure unavailableMedia() { return new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE,"SERVICE_UNAVAILABLE","Published practice media has invalid metadata"); }
}
