package com.asl.backend.media;

import com.asl.backend.media.adapter.in.web.MediaHttpPolicy;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class MediaHttpPolicyTest {
    @Test void windowsExpireAndTrackedAddressesStayBounded() {
        var clock = new MutableClock();
        var policy = new MediaHttpPolicy(clock, 1, Duration.ofSeconds(10), 1);
        assertThat(allow(policy, "192.0.2.1")).isTrue();
        assertThatThrownBy(() -> allow(policy, "192.0.2.1")).isInstanceOf(ApplicationFailure.class);
        assertThatThrownBy(() -> allow(policy, "192.0.2.2")).isInstanceOf(ApplicationFailure.class);
        clock.now = clock.now.plusSeconds(10);
        assertThat(allow(policy, "192.0.2.2")).isTrue();
    }
    @Test void concurrentRequestsCannotExceedLimit() throws Exception {
        var policy = new MediaHttpPolicy(new MutableClock(), 7, Duration.ofMinutes(1), 100);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 50).<Callable<Boolean>>mapToObj(i -> () -> {
                try { return allow(policy, "192.0.2.1"); } catch (ApplicationFailure ignored) { return false; }
            }).toList();
            int successes = 0;
            for (var result : executor.invokeAll(tasks)) if (result.get()) successes++;
            assertThat(successes).isEqualTo(7);
        }
    }
    boolean allow(MediaHttpPolicy policy, String ip) {
        var request = new MockHttpServletRequest("POST", "/api/v1/media/urls");
        request.setRemoteAddr(ip);
        return policy.preHandle(request, new MockHttpServletResponse(), new Object());
    }
    static class MutableClock extends Clock {
        Instant now = MediaServiceTest.NOW;
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
}
