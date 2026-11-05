package com.asl.backend.practice.configuration;
import com.asl.backend.practice.application.PracticeService;
import com.asl.backend.practice.application.api.PracticeApi;
import com.asl.backend.practice.adapter.in.transaction.TransactionalPracticeApi;
import com.asl.backend.practice.adapter.in.web.PracticeHttpPolicy;
import com.asl.backend.learning.application.api.PracticeContentReader;
import com.asl.backend.media.application.api.MediaApi;
import java.time.*;
import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class PracticeConfiguration {
    @Bean PracticeApi practiceApi(PracticeContentReader content,MediaApi media,PlatformTransactionManager manager,@Value("${asl.practice.set-ttl:PT1H}") Duration ttl) {
        return new TransactionalPracticeApi(new PracticeService(content,media,Clock.systemUTC(),new SecureRandom(),ttl),manager);
    }
    @Bean PracticeHttpPolicy practiceHttpPolicy(@Value("${asl.practice.rate-limit.requests:60}") int requests,@Value("${asl.practice.rate-limit.window:PT1M}") Duration window,@Value("${asl.practice.rate-limit.max-clients:10000}") int clients) {
        return new PracticeHttpPolicy(Clock.systemUTC(),requests,window,clients);
    }
    @Bean WebMvcConfigurer practiceWebConfigurer(PracticeHttpPolicy policy) { return new WebMvcConfigurer() {
        @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(policy).addPathPatterns("/api/v1/practice/availability","/api/v1/practice/sets"); }
    }; }
}
