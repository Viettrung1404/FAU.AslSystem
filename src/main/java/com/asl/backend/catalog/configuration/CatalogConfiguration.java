package com.asl.backend.catalog.configuration;
import com.asl.backend.catalog.adapter.in.web.CatalogHttpPolicy;
import com.asl.backend.catalog.adapter.in.transaction.TransactionalCatalogApi;
import com.asl.backend.catalog.adapter.out.cursor.HmacCatalogCursor;
import com.asl.backend.catalog.application.CatalogService;
import com.asl.backend.catalog.application.api.CatalogApi;
import com.asl.backend.catalog.application.port.out.*;
import com.asl.backend.learning.application.api.LessonSummaryReader;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
@Configuration
public class CatalogConfiguration {
    @Bean CatalogCursor catalogCursor(@Value("${asl.catalog.cursor-secret:}") String secret) { return new HmacCatalogCursor(secret); }
    @Bean CatalogApi catalogApi(CatalogRepository repository, LessonSummaryReader lessons, CatalogCursor cursor, PlatformTransactionManager manager,
            @Value("${asl.catalog.lesson-prerequisites-enabled:true}") boolean prerequisites,
            @Value("${asl.catalog.support-url:}") String support) {
        return new TransactionalCatalogApi(new CatalogService(repository, lessons, cursor, prerequisites, support, Clock.systemUTC()), manager);
    }
    @Bean CatalogHttpPolicy catalogHttpPolicy(@Value("${asl.catalog.rate-limit.requests:120}") int budget,
            @Value("${asl.catalog.rate-limit.window:PT1M}") Duration window,
            @Value("${asl.catalog.rate-limit.max-clients:10000}") int maxClients) {
        return new CatalogHttpPolicy(Clock.systemUTC(), budget, window, maxClients);
    }
    @Bean WebMvcConfigurer catalogWebConfigurer(CatalogHttpPolicy policy) {
        return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(policy).addPathPatterns("/api/v1/config", "/api/v1/home", "/api/v1/units", "/api/v1/units/*");
            }
        };
    }
}
