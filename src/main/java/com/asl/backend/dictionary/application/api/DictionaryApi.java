package com.asl.backend.dictionary.application.api;

import java.util.*;

public interface DictionaryApi {
    Map<String, Object> page(String scope, UUID collectionId, String query, String type, String cursor, int limit, String authorization);
    Map<String, Object> sign(UUID id);
    Map<String, Object> collection(UUID id);
}
