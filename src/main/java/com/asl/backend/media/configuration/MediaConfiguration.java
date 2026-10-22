package com.asl.backend.media.configuration;

import com.asl.backend.media.adapter.in.web.MediaHttpPolicy;
import com.asl.backend.media.adapter.in.transaction.TransactionalMediaApi;
import com.asl.backend.media.adapter.out.delivery.PublicCdnUrlProvider;
import com.asl.backend.media.adapter.out.observability.MediaTelemetry;
import com.asl.backend.media.application.MediaService;
import com.asl.backend.media.application.api.MediaApi;
import com.asl.backend.media.application.port.out.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
public class MediaConfiguration {
    @Bean MediaTelemetry mediaTelemetry(MeterRegistry registry) { return new MediaTelemetry(registry); }

    @Bean MediaUrlProvider mediaUrlProvider(@Value("${asl.media.public-base-url:}") String baseUrl, MediaTelemetry telemetry) {
        return telemetry.observe(new PublicCdnUrlProvider(baseUrl));
    }
    @Bean MediaApi mediaApi(AssetRepository repository, MediaUrlProvider provider, PlatformTransactionManager manager,
            MediaTelemetry telemetry, @Value("${asl.media.minimum-remaining-ttl:PT5S}") Duration minimumRemainingTtl) {
        return telemetry.observe(new TransactionalMediaApi(new MediaService(telemetry.observe(repository), provider,
                Clock.systemUTC(), minimumRemainingTtl), manager));
    }
    @Bean MediaHttpPolicy mediaHttpPolicy(@Value("${asl.media.rate-limit.requests:120}") int requests,
            @Value("${asl.media.rate-limit.window:PT1M}") Duration window,
            @Value("${asl.media.rate-limit.max-clients:10000}") int maxClients) {
        return new MediaHttpPolicy(Clock.systemUTC(), requests, window, maxClients);
    }
    @Bean WebMvcConfigurer mediaWebConfigurer(MediaHttpPolicy policy, MediaTelemetry telemetry) {
        return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(telemetry.observe(policy)).addPathPatterns("/api/v1/media/urls");
            }
        };
    }
}
