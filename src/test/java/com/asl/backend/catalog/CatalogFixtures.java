package com.asl.backend.catalog;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Synthetic content for catalog metadata tests only; never seeded by application startup. */
final class CatalogFixtures {
    final JdbcTemplate jdbc;
    final List<UUID> units = new ArrayList<>();
    final List<UUID> lessons = new ArrayList<>();
    final List<UUID> versions = new ArrayList<>();
    final Map<UUID, String> types = new LinkedHashMap<>();
    final List<UUID> hiddenUnits = new ArrayList<>();
    CatalogFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        int start = jdbc.queryForObject("select coalesce(max(position), -1) + 100 from asl.units", Integer.class);
        units.add(unit(start, "published")); units.add(unit(start + 1, "published")); units.add(unit(start + 2, "published"));
        String[] kinds = {"LESSON", "ALPHABET", "REVIEW", "CONVERSATION"};
        for (int i = 0; i < kinds.length; i++) {
            UUID lesson = lesson(units.get(i / 2), i % 2);
            UUID version = version(lesson, 1, kinds[i], "Published " + kinds[i], "published");
            lessons.add(lesson); versions.add(version); types.put(lesson, kinds[i]);
            if (i > 0) jdbc.update("insert into asl.lesson_prerequisites values (?, ?)", lesson, lessons.get(i - 1));
        }
        for (String state : List.of("draft", "retired", "future")) hiddenUnits.add(unit(start + 3 + hiddenUnits.size(), state));
        UUID draft = lesson(units.getFirst(), 20); version(draft, 1, "LESSON", "Hidden draft", "draft");
        UUID future = lesson(units.getFirst(), 21); version(future, 1, "LESSON", "Hidden future", "future");
        UUID revoked = lesson(units.getFirst(), 22); UUID removed = version(revoked, 1, "LESSON", "Hidden revoked", "published");
        jdbc.update("update asl.lesson_versions set is_current=false, revoked_at=now() where id=?", removed);
        UUID retired = lesson(units.getFirst(), 23); version(retired, 1, "LESSON", "Hidden retired lesson", "published");
        jdbc.update("update asl.lessons set retired_at=now() where id=?", retired);
    }
    UUID unit(int position, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into asl.units(id,title,position) values (?, ?, ?)", id, "Synthetic catalog unit", position);
        if (!state.equals("draft")) jdbc.update("update asl.units set published_at=now() - interval '1 second' where id=?", id);
        if (state.equals("future")) jdbc.update("update asl.units set published_at=now() + interval '1 day' where id=?", id);
        if (state.equals("retired")) jdbc.update("update asl.units set retired_at=now() where id=?", id);
        return id;
    }
    UUID lesson(UUID unit, int position) {
        UUID id = UUID.randomUUID(); jdbc.update("insert into asl.lessons(id,unit_id,position) values (?,?,?)", id, unit, position); return id;
    }
    UUID version(UUID lesson, int version, String type, String title, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into asl.lesson_versions(id,lesson_id,version_no,title,lesson_type) values (?,?,?,?,?)", id, lesson, version, title, type);
        if (type.equals("CONVERSATION")) {
            UUID media = UUID.randomUUID();
            jdbc.update("""
                    insert into asl.media_assets(id,storage_key,mime_type,duration_ms,sha256_checksum,rights_reference,published_at)
                    values (?,?,'video/mp4',1,?,'Synthetic catalog test; no corresponding media bytes',now()-interval '1 second')
                    """, media, "catalog-test/" + media + ".mp4", "0".repeat(64));
            jdbc.update("""
                    insert into asl.conversation_turns(id,lesson_version_id,position,speaker_side,gloss_caption,english_translation,video_asset_id)
                    values (?,?,0,'PARTNER','HELLO','Synthetic catalog test',?)
                    """, UUID.randomUUID(), id, media);
        } else {
            jdbc.update("""
                    insert into asl.lesson_exercises(id,lesson_version_id,position,exercise_type,payload,points_per_correct)
                    values (?,?,0,'ENCOURAGEMENT','{"message":"Synthetic catalog test"}'::jsonb,0)
                    """, UUID.randomUUID(), id);
        }
        if (state.equals("future")) jdbc.update("update asl.lesson_versions set published_at=now()+interval '1 day',is_current=true where id=?", id);
        else if (!state.equals("draft")) jdbc.update("update asl.lesson_versions set published_at=now()-interval '1 second',is_current=true where id=?", id);
        return id;
    }
}
