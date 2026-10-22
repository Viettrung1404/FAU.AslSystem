package com.asl.backend.catalog;

import com.asl.backend.catalog.application.api.CatalogApi;
import com.asl.backend.learning.application.api.LessonSummaryReader;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "ASL_SUPPORT_URL=https://support.example.org/asl", "ASL_CATALOG_CURSOR_SECRET=" + CatalogCursorTest.KEY})
class CatalogIT {
    @Autowired CatalogApi api;
    @Autowired LessonSummaryReader learning;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired javax.sql.DataSource dataSource;
    @LocalServerPort int port;
    CatalogFixtures fixture;
    @BeforeAll void seed() { fixture = new CatalogFixtures(jdbc); }

    @Test void configReadsMigratedVersionAndScoringFromDatabase() throws Exception {
        var response = get("/api/v1/config");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"contractVersion\":\"0.3.0\"", "\"dailyLessonLimit\":null", "\"practiceMinimumDistinctLessons\":3");
        capture("catalog_config-200-integration", response);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("update asl.app_settings set setting_value=jsonb_set(setting_value,'{contractVersion}','\"0.2.0\"') where setting_key='mobile_policy'");
            CatalogServiceTest.assertCode("SERVICE_UNAVAILABLE", api::config);
            status.setRollbackOnly();
        });
    }

    @Test void defaultModeRetainsPrerequisitesAndEveryLessonTypeComesFromCurrentVersion() throws Exception {
        var response = get("/api/v1/units/" + fixture.units.get(1));
        assertThat(response.statusCode()).isEqualTo(200);
        var detail = api.unit(fixture.units.get(1));
        assertThat(detail.lessons()).hasSize(2);
        assertThat(detail.lessons().getFirst().prerequisiteLessonIds()).containsExactly(fixture.lessons.get(1));
        for (int i = 0; i < 2; i++) for (var lesson : api.unit(fixture.units.get(i)).lessons()) {
            assertThat(lesson.type()).isEqualTo(fixture.types.get(lesson.id()));
            assertThat(lesson.title()).isEqualTo("Published " + lesson.type());
            assertThat(lesson.currentVersionId()).isEqualTo(fixture.versions.get(fixture.lessons.indexOf(lesson.id())));
        }
        capture("catalog_unit-200-integration", response);
    }

    @Test void missingMalformedAndCoercedPolicyFieldsFailClosed() {
        for (String mutation : List.of(
                "delete from asl.app_settings where setting_key='mobile_policy'",
                "update asl.app_settings set setting_value=setting_value-'dailyLessonLimit' where setting_key='mobile_policy'",
                "update asl.app_settings set setting_value=jsonb_set(setting_value,'{practiceMinimumDistinctLessons}','\"3\"') where setting_key='mobile_policy'",
                "update asl.app_settings set setting_value=setting_value-'version' where setting_key='scoring_policy'")) {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.update(mutation);
                CatalogServiceTest.assertCode("SERVICE_UNAVAILABLE", api::config);
                status.setRollbackOnly();
            });
        }
    }

    @Test void publishedUnitPaginationIsCompleteOrderedAndSharedWithHome() throws Exception {
        List<UUID> seen = new ArrayList<>(); String cursor = null;
        do {
            var page = api.units(cursor, 1); assertThat(page.items()).hasSize(1);
            seen.add(page.items().getFirst().id()); cursor = page.nextCursor();
            assertThat(seen.size()).isLessThan(100);
        } while (cursor != null);
        assertThat(seen).containsExactlyElementsOf(jdbc.queryForList(
                "select id from asl.units where published_at<=now() and retired_at is null order by position,id", UUID.class));
        assertThat(seen).doesNotHaveDuplicates().doesNotContainAnyElementsOf(fixture.hiddenUnits);
        var units = get("/api/v1/units?limit=1"); var home = get("/api/v1/home?limit=1");
        assertThat(units.statusCode()).isEqualTo(200); assertThat(home.statusCode()).isEqualTo(200);
        var mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(home.body()).get("units")).isEqualTo(mapper.readTree(units.body()));
        assertThat(units.headers().firstValue("Cache-Control")).contains("no-store");
        capture("catalog_units-200-integration", units); capture("catalog_home-200-integration", home);
    }

    @Test void hiddenUnitsAndInvalidCursorNeverLeakContent() throws Exception {
        for (UUID hidden : fixture.hiddenUnits) {
            var response = get("/api/v1/units/" + hidden); assertThat(response.statusCode()).isEqualTo(404);
            capture("catalog_unit-404-integration", response);
        }
        var bad = get("/api/v1/units?cursor=invalid");
        assertThat(bad.statusCode()).isEqualTo(400); capture("catalog_units-400-integration", bad);
    }

    @Test void learningSummarySwitchesAtomicallyWithCurrentVersionWithoutFallingBack() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            UUID lesson = fixture.lessons.getFirst();
            jdbc.update("update asl.lesson_versions set is_current=false where lesson_id=?", lesson);
            UUID next = fixture.version(lesson, 2, "ALPHABET", "New current title", "published");
            var summaries = learning.current(List.of(lesson), Instant.now());
            assertThat(summaries).containsKey(lesson);
            assertThat(summaries.get(lesson).versionId()).isEqualTo(next);
            assertThat(summaries.get(lesson).type()).isEqualTo("ALPHABET");
            assertThat(summaries.get(lesson).title()).isEqualTo("New current title");
            jdbc.update("update asl.lesson_versions set is_current=false,revoked_at=now() where id=?", next);
            assertThat(learning.current(List.of(lesson), Instant.now())).doesNotContainKey(lesson);
            status.setRollbackOnly();
        });
    }

    @Test @Order(Integer.MAX_VALUE) void databaseOutageReturnsSanitized503ForCatalogOperations() throws Exception {
        dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).close();
        for (var entry : Map.of("catalog_config", "/api/v1/config", "catalog_home", "/api/v1/home", "catalog_units", "/api/v1/units",
                "catalog_unit", "/api/v1/units/" + fixture.units.getFirst()).entrySet()) {
            var response = get(entry.getValue());
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("SERVICE_UNAVAILABLE", response.headers().firstValue("X-Request-Id").orElseThrow())
                    .doesNotContain("jdbc:", "password");
            capture(entry.getKey() + "-503-integration", response);
        }
    }
    HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    static void capture(String name, HttpResponse<String> response) throws Exception {
        Path path = Path.of("target/catalog-http"); Files.createDirectories(path); Files.writeString(path.resolve(name + ".json"), response.body());
    }
}
