package com.asl.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConfigurationTest {
    // Load real application config without starting JPA/Flyway or reading local secrets.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.import=",
                    "DB_URL=jdbc:postgresql://dev.invalid/dev", "DB_USERNAME=dev-role",
                    "DB_PASSWORD=dev-placeholder", "DB_POOL_SIZE=5");

    @Test void directConnectionIsSharedWithFlywayByDefault() {
        runner.run(context -> {
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://dev.invalid/dev");
            assertThat(env.getProperty("spring.flyway.url"))
                    .isEqualTo(env.getProperty("spring.datasource.url"));
            assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class)).isEqualTo(5);
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        });
    }

    @Test void flywayCanUseDirectConnectionWhileApplicationUsesPooler() {
        runner.withPropertyValues("DB_URL=jdbc:postgresql://dev-pooler.invalid/dev",
                "DB_MIGRATION_URL=jdbc:postgresql://dev-direct.invalid/dev").run(context -> {
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://dev-pooler.invalid/dev");
            assertThat(env.getProperty("spring.flyway.url"))
                    .isEqualTo("jdbc:postgresql://dev-direct.invalid/dev");
        });
    }

    @Test void integrationUsesOnlyTestDatabaseForBothConnections() {
        runner.withPropertyValues("spring.profiles.active=integration",
                "DB_MIGRATION_URL=jdbc:postgresql://dev-direct.invalid/dev",
                "TEST_DB_URL=jdbc:postgresql://test.invalid/test", "TEST_DB_USERNAME=test-role",
                "TEST_DB_PASSWORD=test-placeholder").run(context -> {
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://test.invalid/test");
            assertThat(env.getProperty("spring.flyway.url")).isEqualTo("jdbc:postgresql://test.invalid/test");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("test-role");
            assertThat(env.getProperty("spring.flyway.user")).isEqualTo("test-role");
            assertThat(env.getProperty("spring.datasource.password")).isEqualTo("test-placeholder");
            assertThat(env.getProperty("spring.flyway.password")).isEqualTo("test-placeholder");
        });
    }
}
