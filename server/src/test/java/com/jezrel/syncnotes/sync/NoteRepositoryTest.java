package com.jezrel.syncnotes.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jezrel.syncnotes.TestcontainersConfiguration;
import com.jezrel.syncnotes.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** NoteRepository against real PostgreSQL 17 (Testcontainers) with the Flyway schema. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NoteRepositoryTest {

    static final UUID ALICE = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    static final UUID BOB = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    static final UUID PHONE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    // PostgreSQL stores microseconds; truncate so round-trips compare equal.
    static final Instant T0 = Instant.parse("2026-10-05T14:03:11.123456Z");

    @Autowired NoteRepository notes;
    @Autowired UserRepository users;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("DELETE FROM refresh_tokens").update();
        jdbc.sql("DELETE FROM notes").update();
        jdbc.sql("DELETE FROM users").update();
        users.create(ALICE, "alice@example.com", "hash");
        users.create(BOB, "bob@example.com", "hash");
    }

    static NoteRow row(UUID id, String title, long version) {
        return new NoteRow(id, new NoteValues(title, "body", false, false),
                PerField.all(version), PerField.all(PHONE), version, T0, T0);
    }

    @Test
    void saveThenFind_roundTripsEveryColumn() {
        UUID id = UUID.randomUUID();
        NoteRow saved = new NoteRow(id, new NoteValues("Groceries", "- eggs", true, false),
                new PerField<>(41L, 57L, 44L, 12L),
                new PerField<>(PHONE, UUID.fromString("00000000-0000-0000-0000-0000000000d2"), PHONE, PHONE),
                57, T0, T0.plus(2, ChronoUnit.DAYS));
        notes.save(ALICE, saved);

        assertThat(notes.findByIds(ALICE, List.of(id))).containsExactly(Map.entry(id, saved));
    }

    @Test
    void save_updatesAnExistingRow_butKeepsCreatedAt() {
        UUID id = UUID.randomUUID();
        notes.save(ALICE, row(id, "v1", 1));
        Instant later = T0.plus(1, ChronoUnit.HOURS);
        NoteRow update = new NoteRow(id, new NoteValues("v2", "body", false, true),
                PerField.all(2L), PerField.all(PHONE), 2, later, later);
        notes.save(ALICE, update);

        NoteRow stored = notes.findByIds(ALICE, List.of(id)).get(id);
        assertThat(stored.values()).isEqualTo(new NoteValues("v2", "body", false, true));
        assertThat(stored.serverVersion()).isEqualTo(2);
        assertThat(stored.createdAt()).isEqualTo(T0);          // creation time is not overwritten
        assertThat(stored.updatedAt()).isEqualTo(later);
    }

    @Test
    void findByIds_isScopedToTheUser_evenForTheSameNoteId() {
        UUID shared = UUID.randomUUID();
        notes.save(ALICE, row(shared, "alice's", 1));
        notes.save(BOB, row(shared, "bob's", 1));

        assertThat(notes.findByIds(ALICE, List.of(shared)).get(shared).values().title()).isEqualTo("alice's");
        assertThat(notes.findByIds(BOB, List.of(shared)).get(shared).values().title()).isEqualTo("bob's");
    }

    @Test
    void findByIds_withNoIds_returnsNothing_andMissingIdsAreAbsent() {
        notes.save(ALICE, row(UUID.randomUUID(), "x", 1));
        assertThat(notes.findByIds(ALICE, List.of())).isEmpty();
        assertThat(notes.findByIds(ALICE, List.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void changedSince_returnsNewerRowsInVersionOrder_upToTheLimit() {
        for (long v = 1; v <= 5; v++) {
            notes.save(ALICE, row(UUID.randomUUID(), "n" + v, v));
        }
        notes.save(BOB, row(UUID.randomUUID(), "bob's", 9));      // another account's change

        assertThat(notes.changedSince(ALICE, 2, 10)).extracting(NoteRow::serverVersion).containsExactly(3L, 4L, 5L);
        assertThat(notes.changedSince(ALICE, 0, 2)).extracting(NoteRow::serverVersion).containsExactly(1L, 2L);
        assertThat(notes.changedSince(ALICE, 5, 10)).isEmpty();
    }

    @Test
    void serverVersion_isUniquePerUser_butNotAcrossUsers() {
        notes.save(ALICE, row(UUID.randomUUID(), "a", 7));
        notes.save(BOB, row(UUID.randomUUID(), "b", 7));            // fine: different account

        assertThatThrownBy(() -> notes.save(ALICE, row(UUID.randomUUID(), "dup", 7)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void titleLimit_countsCharacters_notUtf16Units() {
        String emoji = new String(Character.toChars(0x1F600));       // one character, two UTF-16 units
        String title300 = emoji.repeat(NoteLimits.TITLE_MAX);
        assertThat(NoteLimits.length(title300)).isEqualTo(300);
        assertThat(title300.length()).isEqualTo(600);

        notes.save(ALICE, row(UUID.randomUUID(), title300, 1));      // accepted: 300 characters
        assertThatThrownBy(() -> notes.save(ALICE, row(UUID.randomUUID(), title300 + emoji, 2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
