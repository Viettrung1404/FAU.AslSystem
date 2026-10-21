package com.asl.backend.media.application.port.out;

import java.net.URI;
import java.time.Instant;

public interface MediaUrlProvider {
    Delivery deliver(String storageKey);
    record Delivery(URI url, Instant expiresAt) { }
    class Unavailable extends RuntimeException {
        public Unavailable(String message) { super(message); }
    }
}
