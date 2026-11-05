package com.asl.backend.dictionary.adapter.out.persistence;

import com.asl.backend.dictionary.application.port.out.DictionaryRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDictionaryRepository implements DictionaryRepository {
    private final JdbcTemplate jdbc;
    public JdbcDictionaryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public boolean removedSign(UUID id, Instant at) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from asl.signs where id=? and published_at<=? and retired_at is not null)", Boolean.class, id, Timestamp.from(at)));
    }
    private static final String SIGNS = "select s.id,'SIGN'::text as type,s.name,s.search_name,s.video_asset_id as asset_id,''::text as description,false as premium,0::int as sign_count,0::int as ordering from asl.signs s where s.published_at<=? and s.retired_at is null";
    private static final String COLLECTIONS = "select c.id,'COLLECTION'::text as type,c.name,c.search_name,c.cover_asset_id as asset_id,c.description,c.is_premium as premium,(select count(*)::int from asl.collection_signs cs join asl.signs s on s.id=cs.sign_id where cs.collection_id=c.id and s.published_at<=? and s.retired_at is null) as sign_count,0::int as ordering from asl.collections c where c.published_at<=? and c.retired_at is null";
    @Override public Optional<Entry> find(UUID id, String type, Instant at) {
        var stamp = Timestamp.from(at);
        return query("select * from (" + (type.equals("SIGN") ? SIGNS : COLLECTIONS) + ") e where id=?",
                type.equals("SIGN") ? List.of(stamp, id) : List.of(stamp, stamp, id)).stream().findFirst();
    }
    @Override public List<Entry> page(String scope, UUID collectionId, String q, String type, Position after, int count, Instant at) {
        List<Object> args = new ArrayList<>(); var stamp = Timestamp.from(at); String source;
        if (scope.equals("membership")) {
            source = SIGNS.replace("0::int as ordering", "cs.position as ordering")
                .replace("from asl.signs s", "from asl.signs s join asl.collection_signs cs on cs.sign_id=s.id") + " and cs.collection_id=?";
            args.add(stamp); args.add(collectionId);
        } else if (scope.equals("signs") || (scope.equals("search") && type.equals("SIGN"))) { source = SIGNS; args.add(stamp); }
        else if (scope.equals("collections") || (scope.equals("search") && type.equals("COLLECTION"))) { source = COLLECTIONS; args.add(stamp); args.add(stamp); }
        else { source = SIGNS + " union all " + COLLECTIONS; args.add(stamp); args.add(stamp); args.add(stamp); }
        String sql = "select * from (" + source + ") e where true";
        if (scope.equals("search")) { sql += " and strpos(search_name,lower(normalize(btrim(?), NFC)))>0"; args.add(q); }
        if (after != null) {
            if (scope.equals("membership")) { sql += " and (ordering,id)>(?,?)"; args.add(after.order()); args.add(after.id()); }
            else { sql += " and (search_name collate \"C\",type collate \"C\",id)>(? collate \"C\",? collate \"C\",?)"; args.add(after.name()); args.add(after.type()); args.add(after.id()); }
        }
        sql += scope.equals("membership") ? " order by ordering,id limit ?" : " order by search_name collate \"C\",type collate \"C\",id limit ?";
        args.add(count); return query(sql, args);
    }
    private List<Entry> query(String sql, List<?> args) {
        return jdbc.query(sql, (r,n) -> new Entry(r.getObject("id",UUID.class),r.getString("type"),r.getString("name"),r.getString("search_name"),
            r.getObject("asset_id",UUID.class),r.getString("description"),r.getBoolean("premium"),r.getInt("sign_count"),r.getInt("ordering")), args.toArray());
    }
}
