package com.asl.backend.dictionary.configuration;
import com.asl.backend.dictionary.application.*;
import com.asl.backend.dictionary.application.api.*;
import com.asl.backend.dictionary.application.port.out.*;
import com.asl.backend.dictionary.adapter.out.cursor.*;
import com.asl.backend.dictionary.adapter.in.transaction.*;
import com.asl.backend.dictionary.adapter.in.web.*;
import com.asl.backend.media.application.api.MediaApi;
import com.asl.backend.platform.application.api.ApplicationFailure;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class DictionaryConfiguration {
    @Bean @ConditionalOnMissingBean(CollectionAccess.class) CollectionAccess collectionAccess() {
        // No identity/billing module yet. Never accept an unverified bearer or fabricate entitlement.
        return (authorization,premium) -> {
            if (authorization!=null || premium) throw new ApplicationFailure(ApplicationFailure.Kind.UNAUTHENTICATED,"UNAUTHORIZED","A verified account is required");
        };
    }
    @Bean DictionaryApi dictionaryApi(DictionaryRepository repository,CollectionAccess access,MediaApi media,PlatformTransactionManager manager,
            @Value("${asl.dictionary.cursor-secret:}") String secret,@Value("${asl.dictionary.share-origin:}") String origin) {
        return new TransactionalDictionaryApi(new DictionaryService(repository,new HmacDictionaryCursor(secret),access,media,Clock.systemUTC(),origin),manager);
    }
    @Bean DictionaryHttpPolicy dictionaryHttpPolicy(@Value("${asl.dictionary.rate-limit.requests:120}") int requests,
            @Value("${asl.dictionary.rate-limit.window:PT1M}") Duration window,@Value("${asl.dictionary.rate-limit.max-clients:10000}") int clients) {
        return new DictionaryHttpPolicy(Clock.systemUTC(),requests,window,clients);
    }
    @Bean WebMvcConfigurer dictionaryWebConfigurer(DictionaryHttpPolicy policy) { return new WebMvcConfigurer() {
        @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(policy).addPathPatterns("/api/v1/signs","/api/v1/signs/*","/api/v1/collections","/api/v1/collections/*","/api/v1/collections/*/signs","/api/v1/search"); }
    }; }
}
