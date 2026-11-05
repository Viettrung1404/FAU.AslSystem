package com.asl.backend.practice.domain;

import java.text.Normalizer;
import java.util.*;
import java.util.random.RandomGenerator;

/** Pure question generation. Choices, meanings and media references stay within the supplied snapshots. */
public final class PracticeGenerator {
    public record Sign(UUID id,String name,UUID videoAssetId,UUID imageAssetId,boolean alphabet) { }
    private final RandomGenerator random;
    public PracticeGenerator(RandomGenerator random) { this.random=random; }
    private static String meaning(Sign sign) { return Normalizer.normalize(sign.name().strip(),Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
    public List<Sign> eligible(List<Sign> signs,String mode) {
        if (mode.equals("FLASHCARDS")) return signs;
        Map<String,Sign> unique=new LinkedHashMap<>(); Set<UUID> videos=new HashSet<>();
        for (var sign:signs) if ((!mode.equals("ALPHABET_MATCHING") || sign.alphabet()) && !unique.containsKey(meaning(sign)) && videos.add(sign.videoAssetId())) unique.put(meaning(sign),sign);
        return List.copyOf(unique.values());
    }

    public List<Sign> select(List<Sign> source,String mode,int count) {
        var signs=shuffle(eligible(source,mode));
        // Media work is bounded by the requested set, even with thousands of source snapshots.
        if (mode.equals("QUIZ")) {
            Map<UUID,Sign> selected=new LinkedHashMap<>();
            signs.stream().filter(s->s.imageAssetId()!=null).limit(4).forEach(s->selected.put(s.id(),s));
            signs.stream().limit(Math.max(4,count)).forEach(s->selected.putIfAbsent(s.id(),s));
            signs=new ArrayList<>(selected.values());
        } else signs=new ArrayList<>(signs.subList(0,Math.min(signs.size(),mode.equals("FLASHCARDS")?count:count*8)));
        return signs;
    }
    public List<Map<String,Object>> items(List<Sign> signs,String mode,int count,Map<UUID,?> urls) {
        List<Map<String,Object>> items=new ArrayList<>();
        if (mode.equals("FLASHCARDS")) {
            signs.stream().limit(count).forEach(s->items.add(Map.of("id",UUID.randomUUID(),"sign",Map.of("id",s.id(),"name",s.name(),"video",urls.get(s.videoAssetId())))));
        } else if (mode.endsWith("MATCHING")) {
            // Each learned sign occurs once in a set; never pad with unlearned signs or a singleton item.
            for (int i=0;i+1<signs.size() && items.size()<count;) {
                int size=Math.min(8,signs.size()-i); if (signs.size()-i-size==1 && size>2) size--;
                items.add(matching(signs.subList(i,i+size),urls)); i+=size;
            }
        } else {
            var imageSigns=signs.stream().filter(s->s.imageAssetId()!=null).toList();
            // Duplicate images cannot become ambiguous alternatives.
            Map<UUID,Sign> distinctImages=new LinkedHashMap<>(); imageSigns.forEach(s->distinctImages.putIfAbsent(s.imageAssetId(),s));
            imageSigns=List.copyOf(distinctImages.values());
            for (var target:signs) {
                for (var type:List.of("VIDEO_TO_WORD","WORD_TO_VIDEO","VIDEO_TO_IMAGE")) {
                    var choices=type.equals("VIDEO_TO_IMAGE")?imageSigns:signs;
                    if (choices.size()<2 || choices.stream().noneMatch(s->s.id().equals(target.id()))) continue;
                    items.add(quiz(target,type,choices,urls)); if (items.size()>=count) break;
                }
                if (items.size()>=count) break;
            }
        }
        return items;
    }
    private Map<String,Object> quiz(Sign target,String type,List<Sign> pool,Map<UUID,?> urls) {
        var choices=new ArrayList<Sign>(); choices.add(target);
        shuffle(pool.stream().filter(s->!s.id().equals(target.id())).toList()).stream().limit(3).forEach(choices::add);
        choices=shuffle(choices); List<Map<String,Object>> options=new ArrayList<>(); String answer=null;
        for (int i=0;i<choices.size();i++) {
            var s=choices.get(i); String id="option_"+i; if (s.id().equals(target.id())) answer=id;
            Map<String,Object> option=new LinkedHashMap<>(); option.put("id",id); option.put("signId",s.id());
            if (type.equals("WORD_TO_VIDEO")) option.put("video",urls.get(s.videoAssetId()));
            else { option.put("label",s.name()); if (type.equals("VIDEO_TO_IMAGE")) option.put("image",urls.get(s.imageAssetId())); }
            options.add(option);
        }
        Map<String,Object> payload=new LinkedHashMap<>(); payload.put("layout",choices.size()==4?"GRID_2X2":"LIST"); payload.put("options",options);
        if (type.equals("WORD_TO_VIDEO")) payload.put("word",target.name()); else payload.put("video",urls.get(target.videoAssetId()));
        return Map.of("id",UUID.randomUUID(),"signId",target.id(),"type",type,"payload",payload,"answerKey",Map.of("correctOptionId",answer));
    }
    private Map<String,Object> matching(List<Sign> signs,Map<UUID,?> urls) {
        var left=new ArrayList<Map<String,Object>>(); var right=new ArrayList<Map<String,Object>>(); var pairs=new ArrayList<Map<String,Object>>();
        for (int i=0;i<signs.size();i++) { var s=signs.get(i); String l="left_"+i,r="right_"+i;
            left.add(Map.of("id",l,"signId",s.id(),"video",urls.get(s.videoAssetId()))); right.add(Map.of("id",r,"label",s.name())); pairs.add(Map.of("leftId",l,"rightId",r)); }
        return Map.of("id",UUID.randomUUID(),"left",shuffle(left),"right",shuffle(right),"correctPairs",pairs);
    }
    private <T> ArrayList<T> shuffle(List<T> source) {
        var result=new ArrayList<>(source); for(int i=result.size()-1;i>0;i--) { int j=random.nextInt(i+1); var item=result.get(i); result.set(i,result.get(j)); result.set(j,item); } return result;
    }
}
