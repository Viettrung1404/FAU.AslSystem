package com.asl.backend.catalog.application.api;
import java.util.UUID;
public interface CatalogApi {
    CatalogData.Config config();
    CatalogData.Page units(String cursor, int limit);
    CatalogData.Unit unit(UUID id);
}
