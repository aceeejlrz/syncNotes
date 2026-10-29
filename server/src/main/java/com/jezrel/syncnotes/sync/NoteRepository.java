package com.jezrel.syncnotes.sync;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for the {@code notes} table (section 8.1). Every query is scoped by user id: the primary
 * key is (user_id, id), so one account can never read or write another's note, even with its id.
 */
@Repository
public class NoteRepository {

    private final JdbcClient jdbc;

    public NoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The user's notes with the given ids, keyed by id. Missing ids are simply absent. */
    public Map<UUID, NoteRow> findByIds(UUID userId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();                      // "IN ()" is not valid SQL
        }
        return jdbc.sql("SELECT * FROM notes WHERE user_id = :userId AND id IN (:ids)")
                .param("userId", userId)
                .param("ids", List.copyOf(ids))
                .query(NoteRepository::map)
                .stream()
                .collect(Collectors.toMap(NoteRow::id, Function.identity()));
    }

    /** Inserts the row, or updates everything except {@code created_at} if it already exists. */
    public void save(UUID userId, NoteRow row) {
        jdbc.sql("""
                INSERT INTO notes (user_id, id, title, body, pinned, trashed,
                                   title_version, title_device, body_version, body_device,
                                   pinned_version, pinned_device, trashed_version, trashed_device,
                                   server_version, created_at, updated_at)
                VALUES (:userId, :id, :title, :body, :pinned, :trashed,
                        :titleVersion, :titleDevice, :bodyVersion, :bodyDevice,
                        :pinnedVersion, :pinnedDevice, :trashedVersion, :trashedDevice,
                        :serverVersion, :createdAt, :updatedAt)
                ON CONFLICT (user_id, id) DO UPDATE SET
                    title = EXCLUDED.title, body = EXCLUDED.body,
                    pinned = EXCLUDED.pinned, trashed = EXCLUDED.trashed,
                    title_version = EXCLUDED.title_version, title_device = EXCLUDED.title_device,
                    body_version = EXCLUDED.body_version, body_device = EXCLUDED.body_device,
                    pinned_version = EXCLUDED.pinned_version, pinned_device = EXCLUDED.pinned_device,
                    trashed_version = EXCLUDED.trashed_version, trashed_device = EXCLUDED.trashed_device,
                    server_version = EXCLUDED.server_version, updated_at = EXCLUDED.updated_at
                """)
                .param("userId", userId)
                .param("id", row.id())
                .param("title", row.values().title())
                .param("body", row.values().body())
                .param("pinned", row.values().pinned())
                .param("trashed", row.values().trashed())
                .param("titleVersion", row.fieldVersions().title())
                .param("titleDevice", row.fieldDevices().title())
                .param("bodyVersion", row.fieldVersions().body())
                .param("bodyDevice", row.fieldDevices().body())
                .param("pinnedVersion", row.fieldVersions().pinned())
                .param("pinnedDevice", row.fieldDevices().pinned())
                .param("trashedVersion", row.fieldVersions().trashed())
                .param("trashedDevice", row.fieldDevices().trashed())
                .param("serverVersion", row.serverVersion())
                .param("createdAt", utc(row.createdAt()))
                .param("updatedAt", utc(row.updatedAt()))
                .update();
    }

    /** The user's notes changed after {@code since}, oldest first, at most {@code limit} (section 9.6). */
    public List<NoteRow> changedSince(UUID userId, long since, int limit) {
        return jdbc.sql("""
                SELECT * FROM notes
                WHERE user_id = :userId AND server_version > :since
                ORDER BY server_version
                LIMIT :limit
                """)
                .param("userId", userId)
                .param("since", since)
                .param("limit", limit)
                .query(NoteRepository::map)
                .list();
    }

    // The PostgreSQL driver binds OffsetDateTime to timestamptz, but not Instant.
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static NoteRow map(ResultSet rs, int rowNum) throws SQLException {
        return new NoteRow(
                rs.getObject("id", UUID.class),
                new NoteValues(rs.getString("title"), rs.getString("body"),
                        rs.getBoolean("pinned"), rs.getBoolean("trashed")),
                new PerField<>(rs.getLong("title_version"), rs.getLong("body_version"),
                        rs.getLong("pinned_version"), rs.getLong("trashed_version")),
                new PerField<>(rs.getObject("title_device", UUID.class), rs.getObject("body_device", UUID.class),
                        rs.getObject("pinned_device", UUID.class), rs.getObject("trashed_device", UUID.class)),
                rs.getLong("server_version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
