package com.asl.backend.catalog.application.port.out;
import com.asl.backend.catalog.domain.UnitPosition;
public interface CatalogCursor {
    String encode(UnitPosition position, boolean prerequisites);
    UnitPosition decode(String cursor, boolean prerequisites);
}
