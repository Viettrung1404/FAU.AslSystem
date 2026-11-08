package com.asl.backend.sync.application.api;

import java.util.*;

public interface SyncApi {
    Map<String,Object> push(UUID owner, Map<String,Object> batch);
    Map<String,Object> snapshot(UUID owner, String cursor, int limit);
    Map<String,Object> changes(UUID owner, String since, String cursor, int limit);
    Map<String,Object> stats(UUID owner);
}
