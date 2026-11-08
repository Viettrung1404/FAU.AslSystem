package com.asl.backend.reporting.adapter.out.persistence;
import com.asl.backend.reporting.application.api.ReportingApi.*;
import com.asl.backend.reporting.application.port.out.ReportRepository;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
@Repository
public class JdbcReportRepository implements ReportRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    public JdbcReportRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<Stored> find(UUID clientReportId) {
        return jdbc.query("select id,user_id,request_hash,received_at from asl.user_reports where client_report_id=?",
            (r,n)->new Stored(r.getBytes("request_hash"),r.getObject("user_id",UUID.class),new Receipt(r.getObject("id",UUID.class),"RECEIVED",r.getTimestamp("received_at").toInstant())),clientReportId).stream().findFirst();
    }
    public Stored insert(Command c,UUID owner,byte[] fingerprint,Receipt receipt) {
        // ON CONFLICT waits for the competing transaction; a subsequent statement sees its committed receipt.
        jdbc.update("insert into asl.user_reports(id,client_report_id,user_id,target_type,target_id,lesson_version_id,reason,message,request_hash,received_at,practice_context) values(?,?,?,?,?,?,?,?,?,?,cast(? as jsonb)) on conflict(client_report_id) do nothing",
            receipt.id(),c.clientReportId(),owner,c.targetType(),c.targetId(),c.lessonVersionId(),c.reason(),c.message(),fingerprint,Timestamp.from(receipt.receivedAt()),c.practiceContext()==null?null:json.writeValueAsString(c.practiceContext()));
        return find(c.clientReportId()).orElseThrow();
    }
}
