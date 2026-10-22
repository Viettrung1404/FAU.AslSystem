package com.asl.backend.catalog;
import com.asl.backend.catalog.adapter.in.web.CatalogHttpPolicy;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class CatalogHttpPolicyTest {
    @Test void boundedClientCapacityAndWindowResetIncludeRetryAfter() {
        var clock = new MutableClock();
        var policy = new CatalogHttpPolicy(clock, 1, Duration.ofSeconds(10), 1);
        assertThat(allow(policy, "192.0.2.1")).isTrue();
        assertThatThrownBy(() -> allow(policy, "192.0.2.1")).isInstanceOf(ApplicationFailure.class);
        assertThatThrownBy(() -> allow(policy, "192.0.2.2")).isInstanceOf(ApplicationFailure.class);
        clock.now = clock.now.plusSeconds(10);
        assertThat(allow(policy, "192.0.2.2")).isTrue();
    }
    @Test void concurrentRequestsRespectTheBudget() throws Exception {
        var policy = new CatalogHttpPolicy(new MutableClock(), 7, Duration.ofSeconds(10), 100);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 50).<Callable<Boolean>>mapToObj(i -> () -> {
                try { return allow(policy, "192.0.2.1"); } catch (ApplicationFailure expected) { return false; }
            }).toList();
            int accepted = 0; for (var result : executor.invokeAll(tasks)) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(7);
        }
    }
    boolean allow(CatalogHttpPolicy policy, String address) {
        var request = new MockHttpServletRequest("GET", "/api/v1/units"); request.setRemoteAddr(address);
        return policy.preHandle(request, new MockHttpServletResponse(), new Object());
    }
    static class MutableClock extends Clock {
        Instant now = CatalogServiceTest.NOW;
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
}
