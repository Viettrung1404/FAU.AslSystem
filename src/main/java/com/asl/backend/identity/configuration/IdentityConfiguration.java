package com.asl.backend.identity.configuration;

import com.asl.backend.identity.application.IdentityService;
import com.asl.backend.identity.application.api.IdentityApi;
import com.asl.backend.identity.application.port.out.*;
import com.asl.backend.identity.adapter.out.firebase.FirebaseIdTokenVerifier;
import com.asl.backend.identity.adapter.in.web.IdentityHttpPolicy;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class IdentityConfiguration {
    @Bean(destroyMethod="close") @ConditionalOnMissingBean(IdTokenVerifier.class)
    FirebaseIdTokenVerifier firebaseIdTokenVerifier(@Value("${asl.identity.firebase-project-id:}") String projectId,
            @Value("${asl.identity.firebase-credentials-file:}") String credentialsFile) {
        return new FirebaseIdTokenVerifier(projectId, credentialsFile);
    }
    @Bean IdentityApi identityApi(IdTokenVerifier verifier, AccountRepository accounts) {
        return new IdentityService(verifier, accounts);
    }
    @Bean IdentityHttpPolicy identityHttpPolicy(@Value("${asl.identity.rate-limit.requests:60}") int requests,
            @Value("${asl.identity.rate-limit.window:PT1M}") Duration window,
            @Value("${asl.identity.rate-limit.max-clients:10000}") int clients) {
        return new IdentityHttpPolicy(Clock.systemUTC(), requests, window, clients);
    }
    @Bean WebMvcConfigurer identityWebConfigurer(IdentityHttpPolicy policy) { return new WebMvcConfigurer() {
        @Override public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(policy).addPathPatterns("/api/v1/legal-documents", "/api/v1/auth/bootstrap", "/api/v1/me");
        }
    }; }
}
