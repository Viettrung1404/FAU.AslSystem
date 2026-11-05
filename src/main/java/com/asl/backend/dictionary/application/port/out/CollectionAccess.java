package com.asl.backend.dictionary.application.port.out;

/** Must validate any supplied credential, then check current entitlement on every page. */
public interface CollectionAccess {
    void check(String authorization, boolean premium);
}
