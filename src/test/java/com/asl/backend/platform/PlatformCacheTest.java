package com.asl.backend.platform;

import com.asl.backend.platform.configuration.PlatformCacheConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformCacheTest {

    @Test
    void emptyUrlFallsBackToConcurrentMapCacheManager() {
        var config = new PlatformCacheConfiguration("");
        var cacheManager = config.cacheManager();
        assertThat(cacheManager).isInstanceOf(ConcurrentMapCacheManager.class);
    }

    @Test
    void quotedEmptyUrlFallsBackToConcurrentMapCacheManager() {
        var config = new PlatformCacheConfiguration("\"  \"");
        var cacheManager = config.cacheManager();
        assertThat(cacheManager).isInstanceOf(ConcurrentMapCacheManager.class);
    }

    @Test
    void nullUrlFallsBackToConcurrentMapCacheManager() {
        var config = new PlatformCacheConfiguration(null);
        var cacheManager = config.cacheManager();
        assertThat(cacheManager).isInstanceOf(ConcurrentMapCacheManager.class);
    }

    @Test
    void errorHandlerSuppressesAllExceptionsWithoutThrowing() {
        var config = new PlatformCacheConfiguration("");
        CacheErrorHandler handler = config.errorHandler();
        Cache cache = mock(Cache.class);
        when(cache.getName()).thenReturn("test-cache");
        RuntimeException failure = new RuntimeException("Redis connection refused / quota exceeded");

        assertThatCode(() -> handler.handleCacheGetError(failure, cache, "key1"))
            .doesNotThrowAnyException();
        assertThatCode(() -> handler.handleCachePutError(failure, cache, "key1", "val1"))
            .doesNotThrowAnyException();
        assertThatCode(() -> handler.handleCacheEvictError(failure, cache, "key1"))
            .doesNotThrowAnyException();
        assertThatCode(() -> handler.handleCacheClearError(failure, cache))
            .doesNotThrowAnyException();
    }

    @Test
    void upstashRedisConnectionAndOperationsIfConfigured() {
        String redisUrl = System.getenv("REDIS_URL");
        if (redisUrl == null || redisUrl.isBlank()) {
            return; // Skip live network test if not in environment
        }
        var config = new PlatformCacheConfiguration(redisUrl);
        var cacheManager = config.cacheManager();
        assertThat(cacheManager).isNotNull();
        var cache = cacheManager.getCache("config");
        if (cache != null) {
            String testKey = "ping-" + System.currentTimeMillis();
            cache.put(testKey, "pong");
            assertThat(cache.get(testKey, String.class)).isEqualTo("pong");
            cache.evict(testKey);
        }
    }

    @Test
    void shallowEtagFilterGeneratesEtagAndReturns304OnMatchingIfNoneMatch() throws Exception {
        var filter = new org.springframework.web.filter.ShallowEtagHeaderFilter();
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/config");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var servlet = new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void doGet(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse resp) throws java.io.IOException {
                resp.setContentType("application/json");
                resp.getWriter().write("{\"status\":\"ok\"}");
            }
        };

        filter.doFilter(request, response, new org.springframework.mock.web.MockFilterChain(servlet));
        assertThat(response.getStatus()).isEqualTo(200);
        String etag = response.getHeader("ETag");
        assertThat(etag).isNotBlank();

        var request304 = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/config");
        request304.addHeader("If-None-Match", etag);
        var response304 = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(request304, response304, new org.springframework.mock.web.MockFilterChain(servlet));
        assertThat(response304.getStatus()).isEqualTo(304);
        assertThat(response304.getContentAsString()).isEmpty();
    }
}
