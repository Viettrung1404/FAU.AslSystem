package com.asl.backend.sync.configuration;
import com.asl.backend.sync.application.SyncService;
import com.asl.backend.sync.application.api.SyncApi;
import com.asl.backend.sync.application.port.out.SyncRepository;
import com.asl.backend.sync.adapter.out.cursor.HmacSyncCursor;
import com.asl.backend.sync.adapter.in.transaction.TransactionalSyncApi;
import com.asl.backend.sync.adapter.in.web.SyncHttpPolicy;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class SyncConfiguration {
    @Bean SyncApi syncApi(SyncRepository repository,PlatformTransactionManager manager,@Value("${asl.sync.cursor-secret:${ASL_SYNC_CURSOR_SECRET:}}") String secret) { return new TransactionalSyncApi(new SyncService(repository,new HmacSyncCursor(secret,Clock.systemUTC()),Clock.systemUTC()),manager); }
    @Bean WebMvcConfigurer syncWebConfigurer() { return new WebMvcConfigurer() { @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(new SyncHttpPolicy()).addPathPatterns("/api/v1/sync/**","/api/v1/me/stats"); } }; }
}
