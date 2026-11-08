package com.asl.backend.sync;
import com.asl.backend.sync.adapter.out.cursor.HmacSyncCursor;
import com.asl.backend.sync.application.port.out.SyncCursor;
import com.asl.backend.sync.application.api.SyncFailure;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SyncCursorTest {
    final Instant now=Instant.parse("2026-11-07T08:00:00Z");
    final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    final HmacSyncCursor cursor=new HmacSyncCursor("test-only-cursor-secret-32-bytes-long",clock);
    final UUID owner=UUID.randomUUID();
    @Test void cursorIsSignedOwnerBoundAndExpires() {
        var p=new SyncCursor.Position("snapshot",10,3,now.plusSeconds(60).getEpochSecond(),0);
        String token=cursor.encode(owner,p); assertThat(cursor.decode(owner,token)).isEqualTo(p);
        assertThatThrownBy(()->cursor.decode(UUID.randomUUID(),token)).isInstanceOfSatisfying(SyncFailure.class,e->assertThat(e.code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(()->cursor.decode(owner,token.substring(0,token.length()-2)+"aa")).isInstanceOf(SyncFailure.class);
        var expired=cursor.encode(owner,new SyncCursor.Position("sync",10,10,now.getEpochSecond(),10));
        assertThatThrownBy(()->cursor.decode(owner,expired)).isInstanceOfSatisfying(SyncFailure.class,e->assertThat(e.code()).isEqualTo("SYNC_CURSOR_EXPIRED"));
    }
    @Test void missingSecretFailsClosed() { assertThatThrownBy(()->new HmacSyncCursor("",clock).encode(owner,new SyncCursor.Position("sync",0,0,now.plusSeconds(10).getEpochSecond(),0))).isInstanceOfSatisfying(SyncFailure.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE")); }
}
