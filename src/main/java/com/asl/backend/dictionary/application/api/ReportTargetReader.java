package com.asl.backend.dictionary.application.api;
import java.util.UUID;
public interface ReportTargetReader {
    boolean signExists(UUID id);
    boolean collectionExists(UUID id);
}
