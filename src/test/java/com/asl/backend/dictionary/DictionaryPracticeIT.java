package com.asl.backend.dictionary;

import com.asl.backend.dictionary.application.api.DictionaryApi;
import com.asl.backend.practice.application.api.PracticeApi;
import com.asl.backend.learning.application.api.PracticeContentReader.Source;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Synthetic metadata only. Runs exclusively on TEST_DB_*; no development/production seed. */
@ActiveProfiles("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.config.import=",
    "asl.dictionary.share-origin=https://share.example.org","asl.dictionary.cursor-secret="+DictionaryServiceTest.KEY,
    "asl.media.development-fixtures=false","asl.media.public-base-url=https://cdn.example.org"})
class DictionaryPracticeIT {
    @Autowired DictionaryApi dictionary;
    @Autowired PracticeApi practice;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @LocalServerPort int port;
    final UUID free=UUID.randomUUID(),premium=UUID.randomUUID();
    final List<UUID> signs=new ArrayList<>(),videos=new ArrayList<>(),images=new ArrayList<>();
    final List<Source> sources=new ArrayList<>();
    final JsonMapper json=JsonMapper.builder().build();
    UUID draft,revoked,retiredSign;
    @BeforeAll void seed() {
        new TransactionTemplate(manager).executeWithoutResult(status->{
            UUID unit=UUID.randomUUID();
            jdbc.update("insert into asl.units(id,title,position,published_at) values (?,'Dictionary/practice integration',(select coalesce(max(position)+1,0) from asl.units),now())",unit);
            for(int i=0;i<5;i++) {
                var sid=UUID.randomUUID(); var video=asset("video/mp4"); var image=asset("image/png");
                signs.add(sid); videos.add(video); images.add(image);
                String name=i==0?"É%_.*":i==1?"A":i==2?"B":"Practice sign "+i;
                jdbc.update("insert into asl.signs(id,name,content_kind,video_asset_id,published_at) values (?,?,?, ?,now()-interval '1 second')",sid,name,i==1||i==2?"LETTER":"WORD",video);
            }
            retiredSign=UUID.randomUUID(); jdbc.update("insert into asl.signs(id,name,content_kind,video_asset_id,published_at,retired_at) values (?,'Retired','WORD',?,now()-interval '1 day',now())",retiredSign,videos.getFirst());
            for(UUID id:List.of(free,premium)) {
                jdbc.update("insert into asl.collections(id,name,is_premium,published_at) values (?,?,?,now()-interval '1 second')",id,"Integration collection "+id,id.equals(premium));
                for(int i=0;i<signs.size();i++) jdbc.update("insert into asl.collection_signs values (?,?,?)",id,signs.get(i),i);
            }
            for(int i=0;i<3;i++) {
                UUID lesson=UUID.randomUUID(); jdbc.update("insert into asl.lessons(id,unit_id,position) values (?,?,?)",lesson,unit,i);
                UUID version=version(lesson,1,true); sources.add(new Source(lesson,version));
            }
            draft=version(sources.getFirst().lessonId(),2,false);
            revoked=version(sources.getFirst().lessonId(),3,true);
            jdbc.update("update asl.lesson_versions set revoked_at=now() where id=?",revoked);
        });
    }
    UUID asset(String mime) {
        UUID id=UUID.randomUUID(); jdbc.update("insert into asl.media_assets(id,storage_key,mime_type,sha256_checksum,rights_reference,published_at) values (?,?,?,?,?,now()-interval '1 day')",id,"dictionary-practice-it/"+id,mime,"0".repeat(64),"Synthetic integration metadata; no production rights/bytes"); return id;
    }
    UUID version(UUID lesson,int number,boolean publish) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into asl.lesson_versions(id,lesson_id,version_no,title,lesson_type) values (?,?,?,'Frozen source','LESSON')",id,lesson,number);
        for(int i=0;i<signs.size();i++) {
            UUID eid=UUID.randomUUID(); String label=i==0?"É%_.*":i==1?"A":i==2?"B":"Practice sign "+i;
            jdbc.update("insert into asl.lesson_version_signs values (?,?,?,?,?,?)",id,signs.get(i),label,i==1||i==2?"LETTER":"WORD",videos.get(i),i);
            jdbc.update("insert into asl.lesson_exercises(id,lesson_version_id,position,exercise_type,prompt,payload,answer_key,points_per_correct) values (?,?,?,'VIDEO_TO_IMAGE','Choose',?::jsonb,?::jsonb,10)",eid,id,i,
                "{\"schemaVersion\":1,\"videoSlot\":\"target\",\"options\":[{\"id\":\"correct\",\"imageSlot\":\"image\"},{\"id\":\"other\",\"imageSlot\":\"otherImage\"}]}","{\"correctOptionId\":\"correct\"}");
            jdbc.update("insert into asl.exercise_signs values (?,?,?)",id,eid,signs.get(i));
            jdbc.update("insert into asl.lesson_exercise_media values (?,?,?,?)",id,eid,"target",videos.get(i));
            jdbc.update("insert into asl.lesson_exercise_media values (?,?,?,?)",id,eid,"image",images.get(i));
            jdbc.update("insert into asl.lesson_exercise_media values (?,?,?,?)",id,eid,"otherImage",images.get((i+1)%images.size()));
        }
        if(publish) jdbc.update("update asl.lesson_versions set published_at=now()-interval '1 second' where id=?",id);
        return id;
    }
    @Test void actualDictionarySuccessResponsesAndOrderedMembershipPages() throws Exception {
        for(var entry:Map.of("dictionary_signs","/signs?limit=1","dictionary_sign","/signs/"+signs.getFirst(),
                "dictionary_collections","/collections?limit=1","dictionary_collection","/collections/"+premium,
                "dictionary_collectionSigns","/collections/"+free+"/signs?limit=2","dictionary_search","/search?q=Integration").entrySet()) {
            var response=get(entry.getValue()); assertThat(response.statusCode()).isEqualTo(200); capture("dictionary",entry.getKey(),response,"success");
        }
        List<UUID> seen=new ArrayList<>(); String cursor=null;
        do { var page=dictionary.page("membership",free,null,"SIGN",cursor,2,null);
            for(var item:(List<Map<String,Object>>)page.get("items")) seen.add((UUID)item.get("id")); cursor=(String)page.get("nextCursor");
        } while(cursor!=null);
        assertThat(seen).containsExactlyElementsOf(signs);
        assertThat(get("/collections/"+premium+"/signs").statusCode()).isEqualTo(401);
        var invalid=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/collections/"+free+"/signs")).header("Authorization","Bearer invalid").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(invalid.statusCode()).isEqualTo(401); assertThat(invalid.headers().firstValue("Cache-Control")).contains("private, no-store");
    }
    @Test void literalNfcSearchAndQueryBoundCursorAgainstRealPostgres() {
        var page=dictionary.page("search",null," E\u0301%_.* ","SIGN",null,100,null);
        assertThat((List<?>)page.get("items")).hasSize(1);
        for(String q:List.of("%","_",".*")) assertThat((List<?>)dictionary.page("search",null,q,"SIGN",null,100,null).get("items")).hasSize(1);
        var first=dictionary.page("signs",null,null,"SIGN",null,1,null);
        String cursor=(String)first.get("nextCursor"); assertThat(cursor).isNotNull();
        DictionaryServiceTest.code("BAD_REQUEST",()->dictionary.page("search",null,"changed","SIGN",cursor,1,null));
        DictionaryServiceTest.code("CONTENT_REMOVED",()->dictionary.sign(retiredSign));
        List<UUID> ids=new ArrayList<>(); String next=null;
        do { var p=dictionary.page("search",null,"Integration","ALL",next,1,null); for(var item:(List<Map<String,Object>>)p.get("items")) ids.add((UUID)((Map<?,?>)item.get("collection")).get("id")); next=(String)p.get("nextCursor"); } while(next!=null);
        assertThat(ids).containsExactlyInAnyOrder(free,premium);
    }
    @Test void actualPracticeSuccessForAllFourModesAndOnlyFrozenSource() throws Exception {
        var availability=post("/practice/availability",Map.of("completedLessons",sources));
        assertThat(availability.statusCode()).isEqualTo(200); capture("practice","practice_availability",availability,"ready");
        for(String mode:List.of("QUIZ","FLASHCARDS","SIGN_MATCHING","ALPHABET_MATCHING")) {
            var response=post("/practice/sets",Map.of("completedLessons",sources,"mode",mode,"itemCount",30));
            assertThat(response.statusCode()).isEqualTo(200); capture("practice","practice_generate",response,mode);
            var body=json.readTree(response.body()); assertThat(body.get("sourceLessons").size()).isEqualTo(3);
            assertThat(body.get("awardsLessonPoints").asBoolean()).isFalse(); assertThat(body.get("countsForStreak").asBoolean()).isFalse();
        }
        // Changing live dictionary metadata cannot change retained lesson snapshots.
        new TransactionTemplate(manager).executeWithoutResult(status->{
            jdbc.update("update asl.signs set name='Changed after completion' where id=?",signs.getFirst());
            assertThat(json.writeValueAsString(practice.generate("FLASHCARDS",30,sources))).contains("É%_.*").doesNotContain("Changed after completion");
            status.setRollbackOnly();
        });
    }
    @Test void invalidSourceRetiredSourceAndLegacyIdsFailThroughRealHttp() throws Exception {
        var lesson=sources.getFirst().lessonId();
        for(var pair:List.of(new Source(lesson,draft),new Source(lesson,UUID.randomUUID()),new Source(sources.get(1).lessonId(),sources.getFirst().versionId()))) {
            var response=post("/practice/availability",Map.of("completedLessons",List.of(pair))); assertThat(response.statusCode()).isEqualTo(422);
        }
        var response=post("/practice/availability",Map.of("completedLessons",List.of(new Source(lesson,revoked)))); assertThat(response.statusCode()).isEqualTo(410);
        var legacy=post("/practice/sets",Map.of("completedLessonIds",List.of(lesson),"mode","QUIZ")); assertThat(legacy.statusCode()).isEqualTo(400);
        var duplicate=post("/practice/availability",Map.of("completedLessons",List.of(sources.getFirst(),sources.getFirst()))); assertThat(duplicate.statusCode()).isEqualTo(422);
    }
    HttpResponse<String> get(String suffix) throws Exception { return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+suffix)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
    HttpResponse<String> post(String suffix,Object body) throws Exception { return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+suffix)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString()); }
    void capture(String module,String operation,HttpResponse<String> response,String suffix) throws Exception {
        Path folder=Path.of("target/"+module+"-http"); Files.createDirectories(folder); Files.writeString(folder.resolve(operation+"-"+response.statusCode()+"-integration-"+suffix+".json"),response.body());
    }
}
