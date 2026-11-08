package com.asl.backend.sync;

import com.asl.backend.sync.application.*;
import com.asl.backend.sync.application.api.*;
import com.asl.backend.sync.application.port.out.*;
import com.asl.backend.sync.adapter.out.cursor.HmacSyncCursor;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SyncServiceTest {
    final UUID owner=UUID.randomUUID();
    final Clock clock=Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"),ZoneOffset.UTC);
    final SyncRepository repository=mock(SyncRepository.class);
    final SyncCursor cursors=new HmacSyncCursor("test-only-cursor-secret-32-bytes-long",clock);
    final SyncService service=new SyncService(repository,cursors,clock);
    Map<String,Object> preferences(UUID id,long revision) { return Map.of("kind","PREFERENCES","mutationId",id.toString(),"entityKey","preferences","baseRevision",revision,"data",Map.of("hapticsEnabled",true,"dailyReminderEnabled",false,"dailyReminderLocalTime","19:00","learningGoalDays","7","timeZone","Asia/Ho_Chi_Minh")); }
    @Test void duplicateIsCheckedBeforeStaleRevisionAndNeverWritesAgain() {
        UUID id=UUID.randomUUID(); var body=preferences(id,0);
        var change=new SyncRepository.Change(1,"PREFERENCES","preferences",1,Map.of());
        when(repository.receipt(owner,id)).thenReturn(Optional.of(new SyncRepository.Receipt(body,change)));
        var result=service.push(owner,Map.of("mutations",List.of(body)));
        assertThat(result.toString()).contains("DUPLICATE"); verify(repository,never()).current(any(),any(),any()); verify(repository,never()).save(any(),any(),any(),anyLong(),any(),any());
    }
    @Test void changedBodyUnderOldMutationIdIsConflict() {
        UUID id=UUID.randomUUID(); var body=preferences(id,0); var change=new SyncRepository.Change(1,"PREFERENCES","preferences",1,Map.of());
        when(repository.receipt(owner,id)).thenReturn(Optional.of(new SyncRepository.Receipt(preferences(id,2),change)));
        assertThatThrownBy(()->service.push(owner,Map.of("mutations",List.of(body)))).isInstanceOfSatisfying(SyncFailure.class,f->assertThat(f.code()).isEqualTo("MUTATION_ID_REUSED"));
    }
    @Test void revisionConflictReportsCurrentRevisionAndPath() {
        UUID id=UUID.randomUUID(); when(repository.receipt(owner,id)).thenReturn(Optional.empty());
        when(repository.current(owner,"PREFERENCES","preferences")).thenReturn(Optional.of(new SyncRepository.Change(5,"PREFERENCES","preferences",5,Map.of())));
        assertThatThrownBy(()->service.push(owner,Map.of("mutations",List.of(preferences(id,0))))).isInstanceOfSatisfying(SyncFailure.class,f->{ assertThat(f.code()).isEqualTo("ENTITY_REVISION_CONFLICT"); assertThat(f.currentRevision()).isEqualTo(5); assertThat(f.path()).isEqualTo("/mutations/0/baseRevision"); });
    }
    @Test void batchRejectsUnknownFieldsAndRepeatedEntitiesBeforeWriting() {
        var extra=new HashMap<>(preferences(UUID.randomUUID(),0)); extra.put("premium",true);
        assertThatThrownBy(()->service.push(owner,Map.of("mutations",List.of(extra)))).isInstanceOf(SyncFailure.class);
        assertThatThrownBy(()->service.push(owner,Map.of("mutations",List.of(preferences(UUID.randomUUID(),0),preferences(UUID.randomUUID(),0))))).isInstanceOf(SyncFailure.class);
        verifyNoInteractions(repository);
    }
    @Test void paginatedSnapshotPinsWatermarkAndOnlyLastPageHasSyncCursor() {
        var a=new SyncRepository.Change(1,"PREFERENCES","preferences",1,Map.of()); var b=new SyncRepository.Change(2,"FAVORITE","SIGN:x",1,Map.of());
        when(repository.watermark(owner)).thenReturn(2L,100L);
        when(repository.snapshot(owner,2,0,2)).thenReturn(List.of(a,b));
        when(repository.snapshot(owner,2,1,2)).thenReturn(List.of(b));
        var first=service.snapshot(owner,null,1); assertThat(first.get("syncCursor")).isNull();
        var last=service.snapshot(owner,(String)first.get("nextCursor"),1); assertThat(last.get("nextCursor")).isNull();
        assertThat(cursors.decode(owner,(String)last.get("syncCursor")).watermark()).isEqualTo(2);
        verify(repository,times(1)).watermark(owner);
    }
    @Test void changesCursorCannotBeReusedWithAnotherSince() {
        var since=cursors.encode(owner,new SyncCursor.Position("sync",2,2,clock.instant().plusSeconds(100).getEpochSecond(),2));
        var page=cursors.encode(owner,new SyncCursor.Position("changes",5,4,clock.instant().plusSeconds(100).getEpochSecond(),3));
        assertThatThrownBy(()->service.changes(owner,since,page,20)).isInstanceOf(SyncFailure.class);
    }
    @Test void receiptsCompareJsonNumbersSemantically() { assertThat(SyncValidation.equivalent(Map.of("score",90),Map.of("score",90.00))).isTrue(); }
}
