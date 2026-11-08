package com.asl.backend.identity.application.port.out;

public interface IdTokenVerifier {
    record Identity(String uid, String email, String name, String provider) { }
    Identity verify(String token);
}
