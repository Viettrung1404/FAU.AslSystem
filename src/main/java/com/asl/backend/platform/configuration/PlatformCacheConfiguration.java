package com.asl.backend.platform.configuration;

import io.lettuce.core.RedisURI;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Configuration
@EnableCaching
public class PlatformCacheConfiguration implements CachingConfigurer {
    private static final Logger log = LoggerFactory.getLogger(PlatformCacheConfiguration.class);

    private final String redisUrl;

    public PlatformCacheConfiguration(@Value("${spring.data.redis.url:${REDIS_URL:}}") String redisUrl) {
        this.redisUrl = cleanUrl(redisUrl);
    }

    private static String cleanUrl(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        } else if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    @Bean
    @Primary
    @Override
    public CacheManager cacheManager() {
        if (redisUrl.isBlank()) {
            log.info("No valid REDIS_URL provided; using in-memory ConcurrentMapCacheManager (Graceful Fallback active).");
            return new ConcurrentMapCacheManager();
        }

        try {
            log.info("Configuring Upstash Redis CacheManager with URL: {}", maskUrl(redisUrl));
            RedisURI redisUri = RedisURI.create(redisUrl);
            LettuceClientConfiguration.LettuceClientConfigurationBuilder builder = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(2000));
            if (redisUrl.startsWith("rediss://") || redisUri.isSsl()) {
                builder.useSsl();
            }
            LettuceClientConfiguration clientConfig = builder.build();
            RedisConfiguration redisConfig = LettuceConnectionFactory.createRedisConfiguration(redisUri);
            LettuceConnectionFactory factory = new LettuceConnectionFactory(redisConfig, clientConfig);
            factory.afterPropertiesSet();

            RedisCacheConfiguration defaultCacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues();

            Map<String, RedisCacheConfiguration> initialCaches = Map.of(
                "legal-documents", defaultCacheConfig.entryTtl(Duration.ofHours(1)),
                "config", defaultCacheConfig.entryTtl(Duration.ofMinutes(10)),
                "home", defaultCacheConfig.entryTtl(Duration.ofMinutes(5)),
                "units", defaultCacheConfig.entryTtl(Duration.ofMinutes(10)),
                "collections", defaultCacheConfig.entryTtl(Duration.ofMinutes(10))
            );

            return RedisCacheManager.builder(factory)
                .cacheDefaults(defaultCacheConfig)
                .withInitialCacheConfigurations(initialCaches)
                .build();
        } catch (Exception ex) {
            log.warn("Failed to initialize Redis CacheManager ({}). Gracefully falling back to in-memory cache.", ex.getMessage());
            return new ConcurrentMapCacheManager();
        }
    }

    @Bean
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Redis GET failed for cache='{}', key='{}': {}. Falling back to database source.",
                    cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                log.warn("Redis PUT failed for cache='{}', key='{}': {}. Continuing without cache write.",
                    cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log.warn("Redis EVICT failed for cache='{}', key='{}': {}. Continuing without cache evict.",
                    cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log.warn("Redis CLEAR failed for cache='{}': {}. Continuing without cache clear.",
                    cache.getName(), exception.getMessage());
            }
        };
    }

    @Bean
    public org.springframework.web.filter.ShallowEtagHeaderFilter shallowEtagHeaderFilter() {
        return new org.springframework.web.filter.ShallowEtagHeaderFilter();
    }

    private static String maskUrl(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            int atIndex = url.indexOf('@');
            if (atIndex > 0) {
                int colonSlashSlash = url.indexOf("://");
                String scheme = colonSlashSlash > 0 ? url.substring(0, colonSlashSlash + 3) : "";
                return scheme + "***@" + url.substring(atIndex + 1);
            }
        } catch (Exception ignored) { }
        return "***";
    }
}
