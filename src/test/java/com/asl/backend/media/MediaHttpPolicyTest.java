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

    @Test void fullCapacityKeepsExistingQuotasAndReportsWhenNewClientsCanRetry() {
        var clock = new MutableClock();
        var policy = new MediaHttpPolicy(clock, 2, Duration.ofSeconds(10), 10_000);
        for (int i = 0; i < 10_000; i++) assertThat(allow(policy, "client-" + i)).isTrue();

        assertThat(allow(policy, "client-5000")).isTrue();
        var limitedExisting = new MockHttpServletResponse();
        assertLimited(policy, "client-5000", limitedExisting);
        assertThat(limitedExisting.getHeader("Retry-After")).isEqualTo("10");

        var limitedNew = new MockHttpServletResponse();
        assertLimited(policy, "new-client", limitedNew);
        assertThat(limitedNew.getHeader("Retry-After")).isEqualTo("10");
        assertThat(limitedNew.getHeader("Cache-Control")).isEqualTo("no-store");

        clock.now = clock.now.plusSeconds(10);
        for (int i = 0; i < 10_000; i++) assertThat(allow(policy, "replacement-" + i)).isTrue();
        assertLimited(policy, "one-too-many", new MockHttpServletResponse());
    }

    @Test void renewingExistingClientDoesNotLeaveAnOldExpiryEntry() {
        var clock = new MutableClock();
        var policy = new MediaHttpPolicy(clock, 1, Duration.ofSeconds(10), 2);
        assertThat(allow(policy, "first")).isTrue();
        clock.now = clock.now.plusSeconds(5);
        assertThat(allow(policy, "second")).isTrue();
        clock.now = clock.now.plusSeconds(5);
        assertThat(allow(policy, "first")).isTrue();

        var response = new MockHttpServletResponse();
        assertLimited(policy, "third", response);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        clock.now = clock.now.plusSeconds(5);
        assertThat(allow(policy, "third")).isTrue();
        assertLimited(policy, "first", new MockHttpServletResponse());
        assertLimited(policy, "second", new MockHttpServletResponse());
    }

    @Test void expiryOrderingSurvivesClockMovingBackwards() {
        var clock = new MutableClock();
        var policy = new MediaHttpPolicy(clock, 1, Duration.ofSeconds(10), 2);
        assertThat(allow(policy, "first")).isTrue();
        clock.now = clock.now.minusSeconds(5);
        assertThat(allow(policy, "second")).isTrue();

        var response = new MockHttpServletResponse();
        assertLimited(policy, "third", response);
        assertThat(response.getHeader("Retry-After")).isEqualTo("10");
        clock.now = clock.now.plusSeconds(10);
        assertThat(allow(policy, "third")).isTrue();
        assertLimited(policy, "first", new MockHttpServletResponse());
    }

    @Test void retryAfterRoundsUpUntilTheWindowActuallyExpires() {
        var clock = new MutableClock();
        var policy = new MediaHttpPolicy(clock, 1, Duration.ofSeconds(10), 1);
        assertThat(allow(policy, "first")).isTrue();
        clock.now = clock.now.plusMillis(1);
        for (String address : java.util.List.of("first", "second")) {
            var response = new MockHttpServletResponse();
            assertLimited(policy, address, response);
            assertThat(response.getHeader("Retry-After")).isEqualTo("10");
        }
    }

    @Test void forwardedHeadersCannotBypassTheRemoteAddressQuota() {
        var policy = new MediaHttpPolicy(new MutableClock(), 1, Duration.ofMinutes(1), 100);
        var request = request("192.0.2.1");
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        request.addHeader("Forwarded", "for=198.51.100.1");
        assertThat(policy.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "198.51.100.2");
        request.removeHeader("Forwarded");
        request.addHeader("Forwarded", "for=198.51.100.2");
        assertThatThrownBy(() -> policy.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApplicationFailure.class);
    }

    @Test void concurrentNewClientsCannotExceedCapacity() throws Exception {
        var policy = new MediaHttpPolicy(new MutableClock(), 1, Duration.ofMinutes(1), 7);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var results = java.util.stream.IntStream.range(0, 50).mapToObj(i -> executor.submit(() -> {
                start.await();
                try { return allow(policy, "client-" + i); } catch (ApplicationFailure ignored) { return false; }
            })).toList();
            start.countDown();
            int successes = 0;
            for (var result : results) if (result.get()) successes++;
            assertThat(successes).isEqualTo(7);
        }
    }

    void assertLimited(MediaHttpPolicy policy, String ip, MockHttpServletResponse response) {
        assertThatThrownBy(() -> policy.preHandle(request(ip), response, new Object()))
                .isInstanceOf(ApplicationFailure.class);
    }

    boolean allow(MediaHttpPolicy policy, String ip) {
        return policy.preHandle(request(ip), new MockHttpServletResponse(), new Object());
    }

    MockHttpServletRequest request(String ip) {
        var request = new MockHttpServletRequest("POST", "/api/v1/media/urls");
        request.setRemoteAddr(ip);
        return request;
    }
    static class MutableClock extends Clock {
        Instant now = MediaServiceTest.NOW;
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
}
