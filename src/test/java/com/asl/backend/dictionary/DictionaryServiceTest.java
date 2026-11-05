package com.asl.backend.dictionary;
import com.asl.backend.dictionary.application.DictionaryService;
import com.asl.backend.dictionary.application.port.out.*;
import com.asl.backend.dictionary.application.port.out.DictionaryRepository.*;
import com.asl.backend.dictionary.adapter.out.cursor.HmacDictionaryCursor;
import com.asl.backend.media.application.api.*;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class DictionaryServiceTest {
    public static final String KEY="dictionary-test-key-at-least-32-bytes";
    final DictionaryRepository repository=mock(DictionaryRepository.class);
    final CollectionAccess access=mock(CollectionAccess.class);
    final MediaApi media=mock(MediaApi.class);
    final HmacDictionaryCursor cursor=new HmacDictionaryCursor(KEY);
    final UUID cid=UUID.randomUUID(),sid=UUID.randomUUID(),asset=UUID.randomUUID();
    final Entry collection=new Entry(cid,"COLLECTION","Greetings","greetings",null,"Public intro",true,1,0);
    final Entry sign=new Entry(sid,"SIGN","Hello","hello",asset,"",false,0,0);
    DictionaryService service;
    @BeforeEach void setup() {
        service=new DictionaryService(repository,cursor,access,media,Clock.systemUTC(),"https://share.example.org");
        when(repository.find(eq(cid),eq("COLLECTION"),any())).thenReturn(Optional.of(collection));
        when(repository.find(eq(sid),eq("SIGN"),any())).thenReturn(Optional.of(sign));
        when(repository.page(anyString(),any(),anyString(),anyString(),any(),anyInt(),any())).thenReturn(List.of());
        when(media.refresh(anyList())).thenReturn(List.of(new MediaItem(asset,URI.create("https://cdn.example.org/hello.mp4"),null,"video/mp4",1000)));
    }
    @Test void publicPremiumIntroAndSignAreNotGated() {
        assertThat(service.collection(cid)).containsEntry("isPremium",true).containsEntry("shareUrl",URI.create("https://share.example.org/collections/"+cid)).containsEntry("cover",null);
        assertThat(service.sign(sid)).containsEntry("name","Hello"); verifyNoInteractions(access);
    }
    @Test void membershipChecksCredentialsAndPremiumBeforeEveryPageAndBeforeCursor() {
        service.page("membership",cid,null,"SIGN",null,1,"Bearer verified");
        verify(access).check("Bearer verified",false); verify(access).check("Bearer verified",true);
        reset(access,repository);
        doThrow(new ApplicationFailure(ApplicationFailure.Kind.UNAUTHENTICATED,"UNAUTHORIZED","bad token")).when(access).check("Bearer bad",false);
        code("UNAUTHORIZED",()->service.page("membership",cid,null,"SIGN","bad cursor",1,"Bearer bad")); verifyNoInteractions(repository);
        reset(access); when(repository.find(eq(cid),eq("COLLECTION"),any())).thenReturn(Optional.of(collection));
        doThrow(new ApplicationFailure(ApplicationFailure.Kind.FORBIDDEN,"PREMIUM_REQUIRED","expired")).when(access).check("Bearer expired",true);
        code("PREMIUM_REQUIRED",()->service.page("membership",cid,null,"SIGN",null,1,"Bearer expired"));
        verify(repository,never()).page(anyString(),any(),anyString(),anyString(),any(),anyInt(),any());
    }
    @Test void nfcCaseAndTrimNormalizeWhileMetacharactersStayLiteral() {
        service.page("search",null," E\u0301%_.* ","ALL",null,20,null);
        verify(repository).page(eq("search"),isNull(),eq("é%_.*"),eq("ALL"),isNull(),eq(21),any());
        code("VALIDATION_FAILED",()->service.page("search",null,"  ","ALL",null,20,null));
        code("BAD_REQUEST",()->service.page("search",null,"hello","PRIVATE",null,20,null));
    }
    @Test void cursorBindsFiltersAndSupportsUnicodeNamesAndTamperDetection() {
        var p=new Position("é\nname","SIGN",sid,3); var value=cursor.encode("search:all:hello",p);
        assertThat(cursor.decode("search:all:hello",value)).isEqualTo(p);
        code("BAD_REQUEST",()->cursor.decode("search:all:changed",value));
        code("BAD_REQUEST",()->cursor.decode("search:all:hello",value+"x"));
        code("BAD_REQUEST",()->cursor.decode("search:all:hello","garbage"));
    }
    @Test void missingShareConfigFailsWithoutFabricatingLinksAndRemovedSignsAre410() {
        var unconfigured=new DictionaryService(repository,cursor,access,media,Clock.systemUTC(),"https://share.example.invalid");
        code("SERVICE_UNAVAILABLE",()->unconfigured.collection(cid));
        when(repository.removedSign(eq(sid),any())).thenReturn(true); code("CONTENT_REMOVED",()->service.sign(sid));
    }
    @Test void extraRowCreatesCursorAndChangingSearchScopeRejectsIt() {
        when(repository.page(eq("search"),isNull(),eq("hello"),eq("ALL"),isNull(),eq(2),any())).thenReturn(List.of(sign,sign));
        var page=service.page("search",null,"hello","ALL",null,1,null);
        assertThat((List<?>)page.get("items")).hasSize(1);
        String next=(String)page.get("nextCursor"); assertThat(next).isNotBlank();
        code("BAD_REQUEST",()->service.page("search",null,"different","ALL",next,1,null));
    }
    @Test void entitlementIsRecheckedWithNextCursorBeforeMembershipQuery() {
        var active=new java.util.concurrent.atomic.AtomicBoolean(true);
        CollectionAccess changing=(token,premium)-> { if (premium && !active.get()) throw new ApplicationFailure(ApplicationFailure.Kind.FORBIDDEN,"PREMIUM_REQUIRED","expired"); };
        var gated=new DictionaryService(repository,cursor,changing,media,Clock.systemUTC(),"https://share.example.org");
        when(repository.page(eq("membership"),eq(cid),eq(""),eq("SIGN"),isNull(),eq(2),any())).thenReturn(List.of(sign,sign));
        var first=gated.page("membership",cid,null,"SIGN",null,1,"Bearer verified");
        active.set(false);
        code("PREMIUM_REQUIRED",()->gated.page("membership",cid,null,"SIGN",(String)first.get("nextCursor"),1,"Bearer verified"));
        verify(repository,times(1)).page(anyString(),any(),anyString(),anyString(),any(),anyInt(),any());
    }
    @Test void listMediaFailuresDoNotLeakDetailOnlyErrorStatuses() {
        when(repository.page(eq("signs"),isNull(),eq(""),eq("SIGN"),isNull(),eq(21),any())).thenReturn(List.of(sign));
        when(media.refresh(anyList())).thenThrow(new ApplicationFailure(ApplicationFailure.Kind.REMOVED,"CONTENT_REMOVED","revoked media"));
        code("SERVICE_UNAVAILABLE",()->service.page("signs",null,null,"SIGN",null,20,null));
        code("CONTENT_REMOVED",()->service.sign(sid));
    }
    static void code(String code,Runnable work) { assertThatThrownBy(work::run).isInstanceOfSatisfying(ApplicationFailure.class,e->assertThat(e.code()).isEqualTo(code)); }
}
