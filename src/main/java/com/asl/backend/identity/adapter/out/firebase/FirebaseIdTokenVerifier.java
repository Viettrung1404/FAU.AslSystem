package com.asl.backend.identity.adapter.out.firebase;

import com.asl.backend.identity.application.port.out.IdTokenVerifier;
import com.asl.backend.platform.application.api.ApplicationFailure;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static com.asl.backend.platform.application.api.ApplicationFailure.Kind.*;

/** Lazily initializes ADC. Missing deployment credentials never break public learning. */
public final class FirebaseIdTokenVerifier implements IdTokenVerifier, AutoCloseable {
    private final String projectId;
    private final String credentialsFile;
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    private volatile FirebaseApp app;
    private record CachedIdentity(Identity identity, long expiresAtMillis) {}
    private final java.util.concurrent.ConcurrentHashMap<String, CachedIdentity> tokenCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_CACHE_ENTRIES = 5000;
    private static final long CACHE_TTL_MILLIS = 120_000L;
    public FirebaseIdTokenVerifier(String projectId) { this(projectId, ""); }
    public FirebaseIdTokenVerifier(String projectId, String credentialsFile) {
        this.projectId = projectId; this.credentialsFile = credentialsFile;
    }
    private GoogleCredentials credentials() throws java.io.IOException {
        if (credentialsFile == null || credentialsFile.isBlank()) return GoogleCredentials.getApplicationDefault();
        try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(credentialsFile))) {
            return GoogleCredentials.fromStream(input);
        }
    }
    private FirebaseAuth auth() {
        if (projectId == null || projectId.isBlank() || System.getenv("FIREBASE_AUTH_EMULATOR_HOST") != null)
            throw new ApplicationFailure(UNAVAILABLE, "SERVICE_UNAVAILABLE", "Firebase server configuration is unavailable");
        if (app == null) {
            lock.lock();
            try {
                if (app == null) {
                    app = FirebaseApp.initializeApp(FirebaseOptions.builder().setProjectId(projectId)
                        .setCredentials(credentials())
                        .setConnectTimeout(5000).setReadTimeout(10000).build(), "asl-" + UUID.randomUUID());
                }
            } catch (Exception failure) {
                throw new ApplicationFailure(UNAVAILABLE, "SERVICE_UNAVAILABLE", "Firebase server configuration is unavailable");
            } finally {
                lock.unlock();
            }
        }
        return FirebaseAuth.getInstance(app);
    }
    @Override public Identity verify(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
            throw new ApplicationFailure(UNAUTHENTICATED, "UNAUTHORIZED", "Invalid Firebase credential");
        long now = System.currentTimeMillis();
        CachedIdentity cached = tokenCache.get(token);
        if (cached != null && now < cached.expiresAtMillis()) {
            return cached.identity();
        }
        try {
            var verified = auth().verifyIdToken(token, true);
            Object firebase = verified.getClaims().get("firebase");
            String provider = firebase instanceof Map<?, ?> claims ? java.util.Objects.toString(claims.get("sign_in_provider"), "") : "";
            Identity identity = new Identity(verified.getUid(), verified.getEmail(), verified.getName(), provider);
            if (tokenCache.size() >= MAX_CACHE_ENTRIES) {
                tokenCache.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
            }
            if (tokenCache.size() < MAX_CACHE_ENTRIES) {
                tokenCache.put(token, new CachedIdentity(identity, now + CACHE_TTL_MILLIS));
            }
            return identity;
        } catch (FirebaseAuthException failure) {
            tokenCache.remove(token);
            String code = failure.getAuthErrorCode() == null ? "" : failure.getAuthErrorCode().name();
            if (Set.of("EXPIRED_ID_TOKEN", "REVOKED_ID_TOKEN", "INVALID_ID_TOKEN", "USER_DISABLED", "USER_NOT_FOUND", "INVALID_ARGUMENT").contains(code))
                throw new ApplicationFailure(UNAUTHENTICATED, "UNAUTHORIZED", "Firebase credential is invalid or expired");
            throw new ApplicationFailure(UNAVAILABLE, "SERVICE_UNAVAILABLE", "Firebase verification is temporarily unavailable");
        } catch (IllegalArgumentException failure) {
            tokenCache.remove(token);
            throw new ApplicationFailure(UNAUTHENTICATED, "UNAUTHORIZED", "Invalid Firebase credential");
        }
    }
    @Override public void close() {
        lock.lock();
        try {
            tokenCache.clear();
            if (app != null) { app.delete(); app = null; }
        } finally {
            lock.unlock();
        }
    }
}
