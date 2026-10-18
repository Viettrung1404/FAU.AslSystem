package com.asl.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationIT {
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @LocalServerPort int port;

    @Test void migrationsAreRepeatableAndSchemaExists() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.schemata where schema_name='asl'", Integer.class))
                .isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test void healthAndReadinessAreUp() throws Exception {
        for (String path : new String[]{"/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness"}) {
            var response = get(path);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("jdbc:");
            assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        }
    }

    @Test void businessEndpointsAreNotStubbed() throws Exception {
        var response = get("/api/v1/config");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"error\"", "\"requestId\"");
    }

    @Test @Order(Integer.MAX_VALUE)
    void readinessFailsWhenDatabaseBecomesUnavailableButLivenessSurvives() throws Exception {
        dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).close();
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(503);
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
