package com.asl.backend.reporting.application;

import com.asl.backend.reporting.application.api.ReportingApi;
import com.asl.backend.reporting.application.port.out.ReportRepository;
import com.asl.backend.learning.application.api.ReportContentReader;
import com.asl.backend.dictionary.application.api.ReportTargetReader;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

public final class ReportingService implements ReportingApi {
    private final ReportRepository repository;
    private final ReportContentReader learning;
    private final ReportTargetReader dictionary;
    private final Clock clock;
    public ReportingService(ReportRepository repository,ReportContentReader learning,ReportTargetReader dictionary,Clock clock) {
        this.repository=repository; this.learning=learning; this.dictionary=dictionary; this.clock=clock;
    }
    public Receipt submit(Command command, UUID owner) {
        validateBasic(command);
        byte[] hash=fingerprint(command);
        var previous=repository.find(command.clientReportId());
        if(previous.isPresent()) return replay(previous.get(),hash,owner);
        boolean exists=switch(command.targetType()) {
            case "SIGN" -> dictionary.signExists(command.targetId());
            case "COLLECTION" -> dictionary.collectionExists(command.targetId());
            case "LESSON" -> learning.lessonExists(command.targetId());
            case "EXERCISE" -> learning.exerciseExists(command.targetId(),command.lessonVersionId());
            case "PRACTICE_ITEM" -> { validatePractice(command); yield true; }
            default -> false;
        };
        if(!exists) throw new ApplicationFailure(ApplicationFailure.Kind.NOT_FOUND,"NOT_FOUND","Report target not found");
        return replay(repository.insert(command,owner,hash,new Receipt(UUID.randomUUID(),"RECEIVED",clock.instant())),hash,owner);
    }
    private static Receipt replay(ReportRepository.Stored stored,byte[] hash,UUID owner) {
        if(!MessageDigest.isEqual(hash,stored.fingerprint()) || !Objects.equals(owner,stored.owner()))
            throw new ApplicationFailure(ApplicationFailure.Kind.CONFLICT,"REPORT_ID_REUSED","Report id reused");
        return stored.receipt();
    }
    private static void validateBasic(Command c) {
        if(c==null || c.clientReportId()==null || c.targetId()==null || c.targetType()==null || c.reason()==null || c.message()==null) throw invalid();
        if(!Set.of("SIGN","LESSON","EXERCISE","COLLECTION","PRACTICE_ITEM").contains(c.targetType())
            || !Set.of("WRONG_CONTENT","VIDEO_PROBLEM","WRONG_ANSWER","OTHER").contains(c.reason())
            || c.message().isEmpty() || c.message().codePointCount(0,c.message().length())>2000
            || c.targetType().equals("EXERCISE")!=(c.lessonVersionId()!=null)
            || c.targetType().equals("PRACTICE_ITEM")!=(c.practiceContext()!=null)) throw invalid();
    }
    private void validatePractice(Command command) {
        var context=command.practiceContext();
        keys(context,"setId","mode","sourceLessons","generatedAt","item");
        uuid(context.get("setId"));
        if(!"QUIZ".equals(context.get("mode"))) throw invalid();
        try { OffsetDateTime.parse(text(context.get("generatedAt"))); } catch(java.time.format.DateTimeParseException ex) { throw invalid(); }
        var sources=list(context.get("sourceLessons")); if(sources.size()>10000) throw invalid();
        Set<ReportContentReader.Source> pairs=new LinkedHashSet<>(); Set<UUID> allowedSigns=new HashSet<>();
        for(var value:sources) {
            var source=object(value); keys(source,"lessonId","versionId");
            UUID lesson=uuid(source.get("lessonId")),version=uuid(source.get("versionId"));
            if(!pairs.add(new ReportContentReader.Source(lesson,version))) throw invalid();
        }
        var snapshots=learning.sourceSigns(List.copyOf(pairs));
        if(!snapshots.keySet().containsAll(pairs)) throw invalid();
        snapshots.values().forEach(allowedSigns::addAll);
        var item=object(context.get("item")); keys(item,"id","signId","type","payload","answerKey");
        if(!command.targetId().equals(uuid(item.get("id"))) || !allowedSigns.contains(uuid(item.get("signId")))) throw invalid();
        String type=text(item.get("type"));
        if(!Set.of("VIDEO_TO_WORD","WORD_TO_VIDEO","VIDEO_TO_IMAGE").contains(type)) throw invalid();
        var payload=object(item.get("payload")); keys(payload,type.equals("WORD_TO_VIDEO")?"word":"video","layout","options");
        if(type.equals("WORD_TO_VIDEO")) boundedText(payload.get("word")); else media(payload.get("video"),true);
        var options=list(payload.get("options"));
        if(options.size()<2 || options.size()>4 || !Set.of("LIST","GRID_2X2").contains(payload.get("layout"))
            || ("GRID_2X2".equals(payload.get("layout")) && options.size()!=4)) throw invalid();
        Set<String> ids=new HashSet<>(); Set<UUID> signs=new HashSet<>();
        for(var value:options) {
            var option=object(value);
            if(type.equals("WORD_TO_VIDEO")) keys(option,"id","signId","video");
            else if(type.equals("VIDEO_TO_IMAGE")) keys(option,"id","signId","label","image");
            else keys(option,"id","signId","label");
            String id=text(option.get("id")); UUID sign=uuid(option.get("signId"));
            if(!id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id) || !signs.add(sign) || !allowedSigns.contains(sign)) throw invalid();
            if(type.equals("WORD_TO_VIDEO")) media(option.get("video"),true);
            else { boundedText(option.get("label")); if(type.equals("VIDEO_TO_IMAGE")) media(option.get("image"),false); }
        }
        var answer=object(item.get("answerKey")); keys(answer,"correctOptionId");
        // Validate a resolvable answer reference, not whether the reported answer is correct.
        if(!ids.contains(text(answer.get("correctOptionId")))) throw invalid();
    }
    private static void media(Object value,boolean video) {
        var media=object(value);
        keys(media,"assetId","url","mimeType","expiresAt","durationMs");
        uuid(media.get("assetId"));
        try { var uri=java.net.URI.create(text(media.get("url"))); if(!uri.isAbsolute()) throw invalid(); } catch(IllegalArgumentException ex) { throw invalid(); }
        if(video ? !"video/mp4".equals(media.get("mimeType")) : !Set.of("image/png","image/webp").contains(media.get("mimeType"))) throw invalid();
        if(media.get("expiresAt")!=null) try { OffsetDateTime.parse(text(media.get("expiresAt"))); } catch(java.time.format.DateTimeParseException ex) { throw invalid(); }
        if(media.get("durationMs")!=null && (!(media.get("durationMs") instanceof Number n) || n.longValue()<0 || n.doubleValue()!=n.longValue())) throw invalid();
    }
    private static void keys(Map<String,Object> object,String... keys) { if(!object.keySet().equals(Set.of(keys))) throw invalid(); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { if(!(value instanceof Map<?,?>)) throw invalid(); return (Map<String,Object>)value; }
    private static List<?> list(Object value) { if(!(value instanceof List<?> list)) throw invalid(); return list; }
    private static String text(Object value) { if(!(value instanceof String text)) throw invalid(); return text; }
    private static void boundedText(Object value) { String text=text(value); if(text.isEmpty() || text.codePointCount(0,text.length())>2000) throw invalid(); }
    private static UUID uuid(Object value) { try { return value instanceof UUID id?id:UUID.fromString(text(value)); } catch(IllegalArgumentException ex) { throw invalid(); } }
    private static ApplicationFailure invalid() { return new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT,"VALIDATION_FAILED","Report is invalid"); }
    private static byte[] fingerprint(Command c) {
        var body=new LinkedHashMap<String,Object>(); body.put("targetType",c.targetType()); body.put("targetId",c.targetId());
        body.put("lessonVersionId",c.lessonVersionId()); body.put("reason",c.reason()); body.put("message",c.message()); body.put("practiceContext",c.practiceContext());
        try { return MessageDigest.getInstance("SHA-256").digest(canonical(body).getBytes(StandardCharsets.UTF_8)); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static String canonical(Object value) {
        if(value==null) return "n";
        if(value instanceof Map<?,?> map) { var sorted=new TreeMap<String,Object>(); map.forEach((k,v)->sorted.put(k.toString(),v)); var result=new StringBuilder("{"); sorted.forEach((k,v)->result.append(canonical(k)).append(canonical(v))); return result.append('}').toString(); }
        if(value instanceof List<?> list) { var result=new StringBuilder("["); list.forEach(v->result.append(canonical(v))); return result.append(']').toString(); }
        String text=value.toString(); return (value instanceof Number?"d":"s")+text.length()+":"+text;
    }
}
