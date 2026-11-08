package com.asl.backend.reporting.application.port.out;
import com.asl.backend.reporting.application.api.ReportingApi.*;
import java.util.*;
public interface ReportRepository {
    record Stored(byte[] fingerprint, UUID owner, Receipt receipt) { }
    Optional<Stored> find(UUID clientReportId);
    Stored insert(Command command, UUID owner, byte[] fingerprint, Receipt receipt);
}
