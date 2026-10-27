package com.asl.backend.learning.configuration;

import com.asl.backend.learning.application.LearningService;
import com.asl.backend.learning.application.api.LearningApi;
import com.asl.backend.learning.application.port.out.LessonRepository;
import com.asl.backend.learning.adapter.in.transaction.TransactionalLearningApi;
import com.asl.backend.learning.adapter.in.web.LearningHttpPolicy;
import com.asl.backend.media.application.api.MediaApi;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class LearningConfiguration {
    @Bean LearningApi learningApi(LessonRepository repository, MediaApi media, PlatformTransactionManager manager) {
        return new TransactionalLearningApi(new LearningService(repository, media, Clock.systemUTC()), manager);
    }
    @Bean LearningHttpPolicy learningHttpPolicy(@Value("${asl.learning.rate-limit.requests:120}") int budget,
            @Value("${asl.learning.rate-limit.window:PT1M}") Duration window,
            @Value("${asl.learning.rate-limit.max-clients:10000}") int maxClients) {
        return new LearningHttpPolicy(Clock.systemUTC(), budget, window, maxClients);
    }
    @Bean WebMvcConfigurer learningWebConfigurer(LearningHttpPolicy policy) {
        return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(policy).addPathPatterns("/api/v1/lessons/*");
            }
        };
    }
}
