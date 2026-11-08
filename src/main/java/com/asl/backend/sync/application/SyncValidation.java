package com.asl.backend.sync.application;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.asl.backend.sync.application.SyncService.invalid;

/** Strict wire validation; frozen-content scoring and references are checked by persistence. */
public final class SyncValidation {
    private SyncValidation() {}
    public static void mutation(Map<String,Object> m,String p) {
        exact(m,Set.of("kind","mutationId","entityKey","baseRevision","data"),p);
        uuid(m.get("mutationId"),p+"/mutationId"); integer(m.get("baseRevision"),0,Long.MAX_VALUE,p+"/baseRevision");
        String key=string(m.get("entityKey"),1,Integer.MAX_VALUE,p+"/entityKey");
        var d=object(m.get("data"),p+"/data"); String q=p+"/data";
        String expected=switch(string(m.get("kind"),1,30,p+"/kind")) {
            case "FAVORITE" -> {
                exact(d,Set.of("targetType","targetId","isFavorite"),q);
                choice(d.get("targetType"),Set.of("SIGN","COLLECTION"),q+"/targetType"); uuid(d.get("targetId"),q+"/targetId"); bool(d.get("isFavorite"),q+"/isFavorite");
                yield d.get("targetType")+":"+d.get("targetId");
            }
            case "PERSONAL_COLLECTION" -> {
                exact(d,Set.of("id","name","signIds","deleted"),q); uuid(d.get("id"),q+"/id"); string(d.get("name"),1,100,q+"/name"); uuids(d.get("signIds"),1000,q+"/signIds"); bool(d.get("deleted"),q+"/deleted");
                if(Boolean.TRUE.equals(d.get("deleted"))&&!((List<?>)d.get("signIds")).isEmpty()) throw invalid("Deleted collection must be empty",q+"/signIds");
                yield (String)d.get("id");
            }
            case "PREFERENCES" -> {
                exact(d,Set.of("hapticsEnabled","dailyReminderEnabled","dailyReminderLocalTime","learningGoalDays","timeZone"),q);
                bool(d.get("hapticsEnabled"),q+"/hapticsEnabled"); bool(d.get("dailyReminderEnabled"),q+"/dailyReminderEnabled");
                if(d.get("dailyReminderLocalTime")!=null&&!string(d.get("dailyReminderLocalTime"),5,5,q+"/dailyReminderLocalTime").matches("([01][0-9]|2[0-3]):[0-5][0-9]")) throw invalid("Invalid reminder time",q+"/dailyReminderLocalTime");
                choice(d.get("learningGoalDays"),Set.of("7","14","30","50"),q+"/learningGoalDays"); zone(d.get("timeZone"),q+"/timeZone"); yield "preferences";
            }
            case "LESSON_ATTEMPT" -> { attempt(d,q); yield (String)d.get("attemptId"); }
            default -> throw invalid("Unknown mutation kind",p+"/kind");
        };
        if(!key.equals(expected)) throw invalid("Entity key must match data",p+"/entityKey");
    }
    static void attempt(Map<String,Object> d,String p) {
        exact(d,Set.of("attemptId","lessonId","versionId","status","startedAt","updatedAt","completedAt","completionTimeZone","checkpoint","earnedPoints","scorePercent"),p);
        for(String k:List.of("attemptId","lessonId","versionId")) uuid(d.get(k),p+"/"+k);
        choice(d.get("status"),Set.of("IN_PROGRESS","COMPLETED","ABANDONED"),p+"/status");
        Instant start=instant(d.get("startedAt"),p+"/startedAt"), update=instant(d.get("updatedAt"),p+"/updatedAt");
        if(start.isAfter(update)) throw invalid("Update precedes start",p+"/updatedAt"); zone(d.get("completionTimeZone"),p+"/completionTimeZone");
        integer(d.get("earnedPoints"),0,Integer.MAX_VALUE,p+"/earnedPoints");
        if("COMPLETED".equals(d.get("status"))) {
            Instant complete=instant(d.get("completedAt"),p+"/completedAt");
            if(complete.isBefore(start)||complete.isAfter(update)) throw invalid("Completion outside attempt interval",p+"/completedAt");
            if(d.get("scorePercent")!=null) { if(!(d.get("scorePercent") instanceof Number n)||new BigDecimal(n.toString()).compareTo(BigDecimal.ZERO)<0||new BigDecimal(n.toString()).compareTo(BigDecimal.valueOf(100))>0) throw invalid("Invalid score",p+"/scorePercent"); }
        } else if(d.get("completedAt")!=null||d.get("scorePercent")!=null||((Number)d.get("earnedPoints")).longValue()!=0) throw invalid("Incomplete attempt cannot earn points",p);
        String q=p+"/checkpoint"; var cp=object(d.get("checkpoint"),q);
        exact(cp,Set.of("phase","currentStepId","firstAnswers","reviewQueue","reviewResolvedExerciseIds","matchingProgress","draftAnswer","slowPlayback","temporaryPoints","completedStepIds"),q);
        choice(cp.get("phase"),Set.of("QUESTION","FEEDBACK","REVIEW","CONVERSATION","RESULT"),q+"/phase");
        if(cp.get("currentStepId")!=null) uuid(cp.get("currentStepId"),q+"/currentStepId"); bool(cp.get("slowPlayback"),q+"/slowPlayback"); integer(cp.get("temporaryPoints"),0,Integer.MAX_VALUE,q+"/temporaryPoints");
        for(String k:List.of("reviewQueue","reviewResolvedExerciseIds","completedStepIds")) uuids(cp.get(k),500,q+"/"+k);
        var first=array(cp.get("firstAnswers"),0,500,q+"/firstAnswers");
        for(int i=0;i<first.size();i++) { String r=q+"/firstAnswers/"+i; var a=object(first.get(i),r); exact(a,Set.of("exerciseId","answer","isCorrect"),r); uuid(a.get("exerciseId"),r+"/exerciseId"); bool(a.get("isCorrect"),r+"/isCorrect"); answer(a.get("answer"),r+"/answer"); }
        var matching=array(cp.get("matchingProgress"),0,500,q+"/matchingProgress");
        for(int i=0;i<matching.size();i++) { String r=q+"/matchingProgress/"+i; var a=object(matching.get(i),r); exact(a,Set.of("exerciseId","matchedPairs"),r); uuid(a.get("exerciseId"),r+"/exerciseId"); pairs(a.get("matchedPairs"),r+"/matchedPairs"); }
        if(cp.get("draftAnswer")!=null) answer(cp.get("draftAnswer"),q+"/draftAnswer");
        if("COMPLETED".equals(d.get("status"))&&(!"RESULT".equals(cp.get("phase"))||cp.get("currentStepId")!=null||cp.get("draftAnswer")!=null||!((List<?>)cp.get("reviewQueue")).isEmpty())) throw invalid("Completed attempt requires final checkpoint",q);
    }
    private static void answer(Object value,String p) {
        var a=object(value,p); String type=string(a.get("type"),1,10,p+"/type");
        switch(type) {
            case "CHOICE" -> { exact(a,Set.of("type","optionId"),p); string(a.get("optionId"),0,Integer.MAX_VALUE,p+"/optionId"); }
            case "TOKENS" -> { exact(a,Set.of("type","tokenIds"),p); for(Object token:array(a.get("tokenIds"),1,40,p+"/tokenIds")) string(token,0,Integer.MAX_VALUE,p+"/tokenIds"); }
            case "PAIRS" -> { exact(a,Set.of("type","pairs"),p); pairs(a.get("pairs"),p+"/pairs"); }
            default -> throw invalid("Unknown answer type",p+"/type");
        }
    }
    private static void pairs(Object value,String p) { for(Object item:array(value,0,8,p)) { var pair=object(item,p); exact(pair,Set.of("leftId","rightId"),p); string(pair.get("leftId"),0,Integer.MAX_VALUE,p+"/leftId"); string(pair.get("rightId"),0,Integer.MAX_VALUE,p+"/rightId"); } }
    public static void exact(Map<String,Object> value,Set<String> keys,String p) { if(!value.keySet().equals(keys)) throw invalid("Missing or unknown fields",p); }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value,String p) { if(!(value instanceof Map<?,?> map)||map.keySet().stream().anyMatch(k->!(k instanceof String))) throw invalid("Expected object",p); return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") public static List<Object> array(Object value,int min,int max,String p) { if(!(value instanceof List<?> l)||l.size()<min||l.size()>max) throw invalid("Invalid array size",p); return (List<Object>)value; }
    private static String string(Object value,int min,int max,String p) { if(!(value instanceof String s)||s.length()<min||s.length()>max) throw invalid("Invalid string",p); return (String)value; }
    public static UUID uuid(Object value,String p) { String s=string(value,36,36,p); try { UUID id=UUID.fromString(s); if(!id.toString().equalsIgnoreCase(s)) throw new IllegalArgumentException(); return id; } catch(IllegalArgumentException e) { throw invalid("Invalid UUID",p); } }
    private static void uuids(Object value,int max,String p) { var l=array(value,0,max,p); Set<UUID> ids=new HashSet<>(); for(Object id:l) if(!ids.add(uuid(id,p))) throw invalid("Duplicate UUID",p); }
    private static void integer(Object value,long min,long max,String p) { if(!(value instanceof Number n)) throw invalid("Expected integer",p); try { var d=new BigDecimal(n.toString()); if(d.longValueExact()<min||d.longValueExact()>max) throw invalid("Integer out of range",p); } catch(ArithmeticException e) { throw invalid("Expected integer",p); } }
    private static void bool(Object value,String p) { if(!(value instanceof Boolean)) throw invalid("Expected boolean",p); }
    private static void choice(Object value,Set<String> options,String p) { if(!(value instanceof String s)||!options.contains(s)) throw invalid("Invalid enum",p); }
    private static Instant instant(Object value,String p) { try { return OffsetDateTime.parse(string(value,1,100,p)).toInstant(); } catch(DateTimeException e) { throw invalid("Invalid timestamp",p); } }
    private static void zone(Object value,String p) { try { String id=string(value,1,100,p); if(!ZoneId.getAvailableZoneIds().contains(id)) throw new DateTimeException("Not IANA"); } catch(DateTimeException e) { throw invalid("Invalid IANA timezone",p); } }
    public static boolean equivalent(Object a,Object b) {
        if(a instanceof Number x&&b instanceof Number y) return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString()))==0;
        if(a instanceof Map<?,?> x&&b instanceof Map<?,?> y) return x.keySet().equals(y.keySet())&&x.keySet().stream().allMatch(k->equivalent(x.get(k),y.get(k)));
        if(a instanceof List<?> x&&b instanceof List<?> y) { if(x.size()!=y.size()) return false; for(int i=0;i<x.size();i++) if(!equivalent(x.get(i),y.get(i))) return false; return true; }
        return Objects.equals(a,b);
    }
}
