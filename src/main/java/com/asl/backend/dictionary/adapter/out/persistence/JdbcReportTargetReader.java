package com.asl.backend.dictionary.adapter.out.persistence;
import com.asl.backend.dictionary.application.api.ReportTargetReader;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class JdbcReportTargetReader implements ReportTargetReader {
    private final JdbcTemplate jdbc;
    public JdbcReportTargetReader(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public boolean signExists(UUID id) { return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from asl.signs where id=?)",Boolean.class,id)); }
    public boolean collectionExists(UUID id) { return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from asl.collections where id=?)",Boolean.class,id)); }
}
