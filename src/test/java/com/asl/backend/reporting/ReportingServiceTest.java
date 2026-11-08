package com.asl.backend.reporting;
import com.asl.backend.reporting.application.ReportingService;
import com.asl.backend.reporting.application.api.ReportingApi.*;
import com.asl.backend.reporting.application.port.out.ReportRepository;
import com.asl.backend.learning.application.api.ReportContentReader;
import com.asl.backend.dictionary.application.api.ReportTargetReader;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ReportingServiceTest {
    final ReportContentReader learning=mock(ReportContentReader.class);
    final ReportTargetReader dictionary=mock(ReportTargetReader.class);
    final Map<UUID,ReportRepository.Stored> reports=new HashMap<>();
    final ReportRepository repository=new ReportRepository() {
        public Optional<Stored> find(UUID id) { return Optional.ofNullable(reports.get(id)); }
        public Stored insert(Command c,UUID owner,byte[] hash,Receipt receipt) { return reports.computeIfAbsent(c.clientReportId(),id->new Stored(hash,owner,receipt)); }
    };
    final ReportingService service=new ReportingService(repository,learning,dictionary,Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"),ZoneOffset.UTC));
    final UUID target=UUID.randomUUID(), version=UUID.randomUUID(),sign=UUID.randomUUID(),other=UUID.randomUUID();
    @BeforeEach void setup() { when(dictionary.signExists(any())).thenReturn(true); when(learning.sourceSigns(anyList())).thenAnswer(invocation->{
        Map<ReportContentReader.Source,Set<UUID>> result=new HashMap<>(); for(var source:invocation.<List<ReportContentReader.Source>>getArgument(0)) result.put(source,Set.of(sign,other)); return result;
    }); }
    Command command(String type,UUID version,Map<String,Object> context) { return new Command(UUID.randomUUID(),type,target,version,"WRONG_ANSWER","The answer seems incorrect",context); }
    @Test void guestRetryKeepsOriginalReceiptAndDoesNotRevalidateRetiredTarget() {
        var command=command("SIGN",null,null); var receipt=service.submit(command,null);
        when(dictionary.signExists(any())).thenReturn(false);
        assertThat(service.submit(command,null)).isEqualTo(receipt); assertThat(reports).hasSize(1);
        verify(dictionary,times(1)).signExists(target);
    }
    @Test void changedBodyOrOwnerConflicts() {
        var c=command("SIGN",null,null); service.submit(c,null);
        code("REPORT_ID_REUSED",()->service.submit(new Command(c.clientReportId(),c.targetType(),c.targetId(),null,c.reason(),"changed",null),null));
        code("REPORT_ID_REUSED",()->service.submit(c,UUID.randomUUID()));
    }
    @Test void missingAndMismatchedTargetsFail() {
        code("NOT_FOUND",()->service.submit(command("EXERCISE",version,null),null));
        code("NOT_FOUND",()->service.submit(command("LESSON",null,null),null));
        verify(learning).exerciseExists(target,version);
    }
    @Test void exerciseRequiresVersionAndOtherTargetsOmitIt() {
        code("VALIDATION_FAILED",()->service.submit(command("EXERCISE",null,null),null));
        code("VALIDATION_FAILED",()->service.submit(command("SIGN",version,null),null));
        code("VALIDATION_FAILED",()->service.submit(command("PRACTICE_ITEM",null,null),null));
        code("VALIDATION_FAILED",()->service.submit(command("SIGN",null,context()),null));
    }
    @Test void practiceAcceptsExpiredMediaAndReportedWrongAnswerWithoutRegeneration() {
        var context=context(); var c=command("PRACTICE_ITEM",null,context);
        var receipt=service.submit(c,null); assertThat(receipt.status()).isEqualTo("RECEIVED");
        assertThat(service.submit(c,null)).isEqualTo(receipt); verifyNoInteractions(dictionary);
    }
    @Test void practiceRejectsMissingSourcesUnknownSignsAndDanglingAnswers() {
        var context=context(); context.put("sourceLessons",List.of());
        code("VALIDATION_FAILED",()->service.submit(command("PRACTICE_ITEM",null,context),null));
        var next=context(); item(next).put("answerKey",Map.of("correctOptionId","missing"));
        code("VALIDATION_FAILED",()->service.submit(command("PRACTICE_ITEM",null,next),null));
        when(learning.sourceSigns(anyList())).thenReturn(Map.of());
        code("VALIDATION_FAILED",()->service.submit(command("PRACTICE_ITEM",null,context()),null));
    }
    @Test void practiceRejectsDifferentTargetExtraFieldsAndDuplicateOptions() {
        var context=context(); item(context).put("id",UUID.randomUUID().toString());
        code("VALIDATION_FAILED",()->service.submit(command("PRACTICE_ITEM",null,context),null));
        var extra=context(); extra.put("issuedByServer",true);
        code("VALIDATION_FAILED",()->service.submit(command("PRACTICE_ITEM",null,extra),null));
        var duplicate=context(); var payload=(Map<String,Object>)item(duplicate).get("payload");
        var options=(List<?>)payload.get("options"); payload.put("options",List.of(options.getFirst(),options.getFirst()));
        code("VALIDATION_FAILED",()->service.submit(command("PRACTICE_ITEM",null,duplicate),null));
    }
    @Test void snapshotFingerprintIgnoresMapKeyOrderButPreservesArrayAndOriginalMedia() {
        var snapshot=context(); var command=command("PRACTICE_ITEM",null,snapshot); service.submit(command,null);
        var reordered=new TreeMap<>(snapshot);
        assertThat(service.submit(new Command(command.clientReportId(),"PRACTICE_ITEM",target,null,command.reason(),command.message(),reordered),null)).isNotNull();
        var changed=context(); changed.put("generatedAt","2026-11-07T00:00:01Z");
        code("REPORT_ID_REUSED",()->service.submit(new Command(command.clientReportId(),"PRACTICE_ITEM",target,null,command.reason(),command.message(),changed),null));
    }
    @Test void boundsAndUnknownEnumsAreRejected() {
        code("VALIDATION_FAILED",()->service.submit(new Command(UUID.randomUUID(),"SIGN",target,null,"OTHER","x".repeat(2001),null),null));
        code("VALIDATION_FAILED",()->service.submit(command("UNKNOWN",null,null),null));
    }
    Map<String,Object> context() {
        var media=new LinkedHashMap<String,Object>(); media.put("assetId",UUID.randomUUID().toString()); media.put("url","https://cdn.example.org/expired.mp4"); media.put("mimeType","video/mp4"); media.put("expiresAt","2020-01-01T00:00:00Z"); media.put("durationMs",null);
        var payload=new LinkedHashMap<String,Object>(); payload.put("video",media); payload.put("layout","LIST");
        payload.put("options",List.of(Map.of("id","a","signId",sign.toString(),"label","Hello"),Map.of("id","b","signId",other.toString(),"label","Bye")));
        var item=new LinkedHashMap<String,Object>(); item.put("id",target.toString()); item.put("signId",sign.toString()); item.put("type","VIDEO_TO_WORD"); item.put("payload",payload);
        item.put("answerKey",Map.of("correctOptionId","b"));
        var context=new LinkedHashMap<String,Object>(); context.put("setId",UUID.randomUUID().toString()); context.put("mode","QUIZ"); context.put("generatedAt","2026-11-07T00:00:00Z");
        context.put("sourceLessons",List.of(Map.of("lessonId",UUID.randomUUID().toString(),"versionId",version.toString()))); context.put("item",item); return context;
    }
    @SuppressWarnings("unchecked") static Map<String,Object> item(Map<String,Object> context) { return (Map<String,Object>)context.get("item"); }
    static void code(String code,Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(ApplicationFailure.class,f->assertThat(f.code()).isEqualTo(code)); }
}
