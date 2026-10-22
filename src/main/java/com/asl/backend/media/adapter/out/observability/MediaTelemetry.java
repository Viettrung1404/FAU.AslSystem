package com.asl.backend.media.adapter.out.observability;

import com.asl.backend.media.adapter.in.web.MediaHttpPolicy;
import com.asl.backend.media.application.api.*;
import com.asl.backend.media.application.port.out.*;
import com.asl.backend.media.domain.MediaAsset;
import com.asl.backend.platform.application.api.ApplicationFailure;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.servlet.HandlerInterceptor;

/** Adapter-only instrumentation. Tags are bounded; no URLs, IDs, IPs or exception messages. */
public final class MediaTelemetry {
    private static final Logger LOG = LoggerFactory.getLogger(MediaTelemetry.class);
    private final MeterRegistry registry;

    public MediaTelemetry(MeterRegistry registry) { this.registry = registry; }

    public MediaApi observe(MediaApi delegate) {
        return new MediaApi() {
            public List<MediaItem> refresh(List<UUID> ids) {
                if (ids != null && ids.size() <= 100) registry.summary("media.refresh.batch.size").record(ids.size());
                return measure("api", "refresh", () -> delegate.refresh(ids));
            }
            public void publish(UUID id) { measure("api", "publish", () -> { delegate.publish(id); return null; }); }
            public void revoke(UUID id) { measure("api", "revoke", () -> { delegate.revoke(id); return null; }); }
        };
    }

    public AssetRepository observe(AssetRepository delegate) {
        return new AssetRepository() {
            public List<MediaAsset> findAll(Collection<UUID> ids) {
                return measure("repository", "find", () -> delegate.findAll(ids));
            }
            public boolean publish(UUID id, Instant at) {
                return measure("repository", "publish", () -> delegate.publish(id, at));
            }
            public boolean revoke(UUID id, Instant at) {
                return measure("repository", "revoke", () -> delegate.revoke(id, at));
            }
        };
    }

    public MediaUrlProvider observe(MediaUrlProvider delegate) {
        return key -> measure("provider", "deliver", () -> delegate.deliver(key));
    }

    public HandlerInterceptor observe(MediaHttpPolicy delegate) {
        return new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                return measure("limiter", "check", () -> delegate.preHandle(request, response, handler));
            }
        };
    }

    private <T> T measure(String stage, String operation, Supplier<T> action) {
        var sample = Timer.start(registry);
        String outcome = "success";
        try {
            return action.get();
        } catch (RuntimeException failure) {
            outcome = outcome(failure);
            if (stage.equals("api") && (outcome.endsWith("unavailable") || outcome.equals("invalid_delivery")
                    || outcome.equals("unexpected"))) {
                var cause = failure.getCause() == null ? failure : failure.getCause();
                // Do not log the throwable/message: provider and JDBC errors may contain credentials.
                LOG.warn("Media operation failed: operation={}, outcome={}, causeType={}, requestId={}",
                        operation, outcome, cause.getClass().getSimpleName(), MDC.get("requestId"));
            }
            throw failure;
        } finally {
            sample.stop(registry.timer("media.operation.duration", "stage", stage, "operation", operation, "outcome", outcome));
        }
    }

    private static String outcome(RuntimeException failure) {
        var cause = failure instanceof ApplicationFailure && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof MediaUrlProvider.InvalidDelivery) return "invalid_delivery";
        if (cause instanceof MediaUrlProvider.Unavailable) return "provider_unavailable";
        if (cause instanceof DataAccessResourceFailureException || cause instanceof TransientDataAccessException
                || cause instanceof TransactionException) return "db_unavailable";
        if (failure instanceof ApplicationFailure application) {
            return switch (application.kind()) {
                case BAD_REQUEST, INVALID_INPUT -> "invalid_input";
                case NOT_FOUND -> "not_found";
                case REMOVED -> "removed";
                case UNAVAILABLE -> "unavailable";
                case RATE_LIMITED -> "rate_limited";
            };
        }
        return "unexpected";
    }
}
