package com.asl.backend.catalog;
import com.asl.backend.catalog.application.api.CatalogApi;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles({"integration", "dev"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "ASL_SUPPORT_URL=https://support.example.org/asl", "ASL_CATALOG_CURSOR_SECRET=" + CatalogCursorTest.KEY})
class CatalogDevelopmentIT {
    @Autowired CatalogApi api;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @Test void devProfileOpensAllFourTypesAcrossUnitsWithoutDeletingPrerequisites() throws Exception {
        var fixture = new CatalogFixtures(jdbc);
        for (int i = 0; i < 2; i++) {
            var detail = api.unit(fixture.units.get(i)); assertThat(detail.lessons()).hasSize(2);
            assertThat(detail.lessons()).allSatisfy(lesson -> assertThat(lesson.prerequisiteLessonIds()).isEmpty());
            var response = get("/api/v1/units/" + fixture.units.get(i)); assertThat(response.statusCode()).isEqualTo(200);
            CatalogIT.capture("catalog_unit-200-dev", response);
        }
        assertThat(jdbc.queryForObject("select count(*) from asl.lesson_prerequisites where lesson_id=?", Integer.class, fixture.lessons.getLast())).isEqualTo(1);
        for (String endpoint : new String[]{"/api/v1/home", "/api/v1/units"}) {
            var response = get(endpoint); assertThat(response.statusCode()).isEqualTo(200);
            var json = JsonMapper.builder().build().readTree(response.body());
            var items = endpoint.endsWith("home") ? json.get("units").get("items") : json.get("items");
            for (var unit : items) for (var lesson : unit.get("lessons")) assertThat(lesson.get("prerequisiteLessonIds").size()).isZero();
            CatalogIT.capture(endpoint.endsWith("home") ? "catalog_home-200-dev" : "catalog_units-200-dev", response);
        }
    }
    HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
