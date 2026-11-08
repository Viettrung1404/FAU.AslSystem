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
    private FirebaseApp app;
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
    private synchronized FirebaseAuth auth() {
        if (projectId == null || projectId.isBlank() || System.getenv("FIREBASE_AUTH_EMULATOR_HOST") != null)
            throw new ApplicationFailure(UNAVAILABLE, "SERVICE_UNAVAILABLE", "Firebase server configuration is unavailable");
        if (app == null) {
            try {
                app = FirebaseApp.initializeApp(FirebaseOptions.builder().setProjectId(projectId)
                    .setCredentials(credentials())
                    .setConnectTimeout(5000).setReadTimeout(10000).build(), "asl-" + UUID.randomUUID());
            } catch (Exception failure) {
                throw new ApplicationFailure(UNAVAILABLE, "SERVICE_UNAVAILABLE", "Firebase server configuration is unavailable");
            }
        }
        return FirebaseAuth.getInstance(app);
    }
    @Override public Identity verify(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
            throw new ApplicationFailure(UNAUTHENTICATED, "UNAUTHORIZED", "Invalid Firebase credential");
        try {
            var verified = auth().verifyIdToken(token, true);
            Object firebase = verified.getClaims().get("firebase");
            String provider = firebase instanceof Map<?, ?> claims ? java.util.Objects.toString(claims.get("sign_in_provider"), "") : "";
            return new Identity(verified.getUid(), verified.getEmail(), verified.getName(), provider);
        } catch (FirebaseAuthException failure) {
            String code = failure.getAuthErrorCode() == null ? "" : failure.getAuthErrorCode().name();
            if (Set.of("EXPIRED_ID_TOKEN", "REVOKED_ID_TOKEN", "INVALID_ID_TOKEN", "USER_DISABLED", "USER_NOT_FOUND", "INVALID_ARGUMENT").contains(code))
                throw new ApplicationFailure(UNAUTHENTICATED, "UNAUTHORIZED", "Firebase credential is invalid or expired");
            throw new ApplicationFailure(UNAVAILABLE, "SERVICE_UNAVAILABLE", "Firebase verification is temporarily unavailable");
        } catch (IllegalArgumentException failure) {
            throw new ApplicationFailure(UNAUTHENTICATED, "UNAUTHORIZED", "Invalid Firebase credential");
        }
    }
    @Override public synchronized void close() { if (app != null) { app.delete(); app = null; } }
}
