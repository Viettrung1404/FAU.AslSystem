package com.asl.backend.sync.application.port.out;
import java.util.UUID;

public interface SyncCursor {
    record Position(String mode, long watermark, long after, long expires, long baseline) {}
    String encode(UUID owner, Position position);
    Position decode(UUID owner, String token);
}
