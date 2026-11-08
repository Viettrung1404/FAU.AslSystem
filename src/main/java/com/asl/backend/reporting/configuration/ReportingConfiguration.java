package com.asl.backend.reporting.configuration;
import com.asl.backend.reporting.application.ReportingService;
import com.asl.backend.reporting.application.api.ReportingApi;
import com.asl.backend.reporting.application.port.out.ReportRepository;
import com.asl.backend.reporting.adapter.in.transaction.TransactionalReportingApi;
import com.asl.backend.learning.application.api.ReportContentReader;
import com.asl.backend.dictionary.application.api.ReportTargetReader;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
@Configuration
public class ReportingConfiguration {
    @Bean ReportingApi reportingApi(ReportRepository repository,ReportContentReader learning,ReportTargetReader dictionary,PlatformTransactionManager manager) {
        return new TransactionalReportingApi(new ReportingService(repository,learning,dictionary,Clock.systemUTC()),manager);
    }
}
