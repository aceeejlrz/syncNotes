package com.jezrel.syncnotes.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jezrel.syncnotes.TestcontainersConfiguration;
import com.jezrel.syncnotes.common.InvalidRequestException;
import com.jezrel.syncnotes.sync.dto.ChangeDto;
import com.jezrel.syncnotes.sync.dto.FieldVersionsDto;
import com.jezrel.syncnotes.sync.dto.NoteDto;
import com.jezrel.syncnotes.sync.dto.PullResponse;
import com.jezrel.syncnotes.sync.dto.PushRequest;
import com.jezrel.syncnotes.sync.dto.PushResponse;
import com.jezrel.syncnotes.sync.dto.PushResultDto;
import com.jezrel.syncnotes.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** SyncService against real PostgreSQL 17: transactions, change numbers and stamping (9.5, 9.6). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SyncServiceTest {

    static final UUID ALICE = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    static final UUID BOB = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    static final UUID PHONE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    static final UUID TABLET = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    static final UUID SEED_DEVICE = UUID.fromString("00000000-0000-0000-0000-0000000000d0");
    static final UUID N = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    static final Instant CREATED = Instant.parse("2026-10-05T14:03:11.123456789Z"); // nanoseconds

    @Autowired SyncService sync;
    @Autowired NoteRepository notes;
    @Autowired UserRepository users;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("DELETE FROM refresh_tokens").update();
        jdbc.sql("DELETE FROM notes").update();
        jdbc.sql("DELETE FROM users").update();
        users.create(ALICE, "alice@example.com", "hash");
        users.create(BOB, "bob@example.com", "hash");
    }

    // ---- helpers ------------------------------------------------------------------------------

    static ChangeDto change(UUID id, long base, String title, String body, Boolean pinned, Boolean trashed) {
        return new ChangeDto(id, new FieldVersionsDto(base, base, base, base), CREATED, title, body, pinned, trashed);
    }

    static ChangeDto newNote(UUID id, String title) {
        return change(id, 0, title, "", false, false);
    }

    PushResponse push(UUID user, UUID device, String name, ChangeDto... changes) {
        return sync.push(user, new PushRequest(device, name, List.of(changes)));
    }

    /** Section 9.8's starting point: note N at version 10, every field written at 10. */
    void seedNoteAt10(UUID user, String title, String body) {
        tx.executeWithoutResult(s -> users.setSyncVersion(user, 10));
        notes.save(user, new NoteRow(N, new NoteValues(title, body, false, false), PerField.all(10L),
                PerField.all(SEED_DEVICE), 10, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z")));
    }

    long counter(UUID user) {
        return jdbc.sql("SELECT sync_version FROM users WHERE id = :id").param("id", user).query(Long.class).single();
    }

    List<NoteRow> allNotes(UUID user) {
        return notes.changedSince(user, 0, 1000);
    }

    static PushResultDto only(PushResponse r) {
        assertThat(r.results()).hasSize(1);
        return r.results().getFirst();
    }

    // ---- the worked examples, through the service, with the tables' version numbers -----------

    @Nested
    class WorkedExamples {

        @Test
        void example1_differentFieldsMergeCleanly() {
            seedNoteAt10(ALICE, "Groceries", "eggs");

            PushResultDto tablet = only(push(ALICE, TABLET, "Tablet", change(N, 10, null, "eggs, rice", null, null)));
            assertThat(tablet.status()).isEqualTo(PushStatus.APPLIED);
            assertThat(tablet.note().version()).isEqualTo(11);

            PushResultDto phone = only(push(ALICE, PHONE, "Phone", change(N, 10, "Shopping", null, null, null)));
            assertThat(phone.status()).isEqualTo(PushStatus.APPLIED);
            assertThat(phone.note().version()).isEqualTo(12);
            // Step 4: the response carries the body the tablet wrote.
            assertThat(phone.note().title()).isEqualTo("Shopping");
            assertThat(phone.note().body()).isEqualTo("eggs, rice");
            assertThat(phone.note().fieldVersions()).isEqualTo(new FieldVersionsDto(12L, 11L, 10L, 10L));

            assertThat(allNotes(ALICE)).hasSize(1);                          // no copy
            assertThat(counter(ALICE)).isEqualTo(12);
        }

        @Test
        void example2_sameFieldMakesAConflictedCopy_atItsOwnNumber() {
            seedNoteAt10(ALICE, "Groceries", "eggs");
            push(ALICE, PHONE, "Phone", change(N, 10, null, "eggs, milk", null, null));

            PushResultDto tablet = only(push(ALICE, TABLET, "Tablet", change(N, 10, null, "eggs, bread", null, null)));
            assertThat(tablet.status()).isEqualTo(PushStatus.CONFLICT_COPY);
            assertThat(tablet.note().version()).isEqualTo(11);                // N unchanged
            assertThat(tablet.note().body()).isEqualTo("eggs, milk");

            PullResponse pull = sync.pull(ALICE, 11, 200);                   // the copy arrives on pull
            assertThat(pull.notes()).singleElement().satisfies(copy -> {
                assertThat(copy.version()).isEqualTo(12);
                assertThat(copy.title()).isEqualTo("Groceries (conflicted copy from Tablet)");
                assertThat(copy.body()).isEqualTo("eggs, bread");
                assertThat(copy.fieldVersions()).isEqualTo(new FieldVersionsDto(12L, 12L, 12L, 12L));
            });
            NoteRow copyRow = allNotes(ALICE).stream().filter(n -> !n.id().equals(N)).findFirst().orElseThrow();
            assertThat(copyRow.fieldDevices()).isEqualTo(PerField.all(TABLET));
            assertThat(counter(ALICE)).isEqualTo(12);
        }

        @Test
        void example3_lostResponse_isNotAFalseConflict() {
            seedNoteAt10(ALICE, "Groceries", "a");
            push(ALICE, PHONE, "Phone", change(N, 10, null, "ab", null, null));       // response "lost"

            PushResultDto retry = only(push(ALICE, PHONE, "Phone", change(N, 10, null, "abc", null, null)));
            assertThat(retry.status()).isEqualTo(PushStatus.APPLIED);
            assertThat(retry.note().version()).isEqualTo(12);
            assertThat(retry.note().body()).isEqualTo("abc");
            assertThat(allNotes(ALICE)).hasSize(1);                          // no conflicted copy
        }
    }

    // ---- stamping and change numbers ----------------------------------------------------------

    @Test
    void m1_stampsEveryFieldWithThePushingDevice_andKeepsTheClientsCreatedAt() {
        PushResultDto r = only(push(ALICE, PHONE, "Phone", newNote(N, "Trip ideas")));
        assertThat(r.status()).isEqualTo(PushStatus.APPLIED);
        assertThat(r.note().version()).isEqualTo(1);
        assertThat(r.note().fieldVersions()).isEqualTo(new FieldVersionsDto(1L, 1L, 1L, 1L));
        NoteRow stored = notes.findByIds(ALICE, List.of(N)).get(N);
        assertThat(stored.fieldDevices()).isEqualTo(PerField.all(PHONE));
        assertThat(stored.createdAt()).isEqualTo(Instant.parse("2026-10-05T14:03:11.123456Z")); // micros
    }

    @Test
    void m1_withNoFieldsSent_createsTheNoteWithDefaults() {
        ChangeDto bare = new ChangeDto(N, new FieldVersionsDto(0L, 0L, 0L, 0L), CREATED, null, null, null, null);
        PushResultDto r = only(push(ALICE, PHONE, "Phone", bare));
        assertThat(r.status()).isEqualTo(PushStatus.APPLIED);
        assertThat(r.note().version()).isEqualTo(1);
        assertThat(r.note()).extracting(NoteDto::title, NoteDto::body, NoteDto::pinned, NoteDto::trashed)
                .containsExactly("", "", false, false);
    }

    @Test
    void aNoop_consumesNoChangeNumber() {
        push(ALICE, PHONE, "Phone", newNote(N, "Trip ideas"));
        PushResultDto retry = only(push(ALICE, PHONE, "Phone", newNote(N, "Trip ideas")));   // same values
        assertThat(retry.status()).isEqualTo(PushStatus.NOOP);
        assertThat(retry.note().version()).isEqualTo(1);
        assertThat(counter(ALICE)).isEqualTo(1);
    }

    @Test
    void numbersFollowRequestOrder_andTheMainNoteIsStampedBeforeItsCopy() {
        seedNoteAt10(ALICE, "Groceries", "eggs");
        push(ALICE, PHONE, "Phone", change(N, 10, null, "eggs, milk", null, null));            // N at 11 by phone
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        PushResponse r = push(ALICE, TABLET, "Tablet",
                newNote(a, "A"),                                                         // 12
                change(N, 10, null, "eggs, bread", true, null),                          // pin 13, copy 14
                newNote(b, "B"));                                                        // 15

        assertThat(r.results()).extracting(PushResultDto::status)
                .containsExactly(PushStatus.APPLIED, PushStatus.CONFLICT_COPY, PushStatus.APPLIED);
        assertThat(r.results()).extracting(x -> x.note().version()).containsExactly(12L, 13L, 15L);
        assertThat(r.results().get(1).note().pinned()).isTrue();                          // M5 still applied
        assertThat(allNotes(ALICE)).extracting(NoteRow::serverVersion).containsExactly(12L, 13L, 14L, 15L);
        assertThat(counter(ALICE)).isEqualTo(15);
    }

    @Test
    void aRejectedNote_doesNotRollBackTheRestOfThePush() {
        String emoji = new String(Character.toChars(0x1F600));
        UUID tooLong = UUID.randomUUID();
        UUID fine = UUID.randomUUID();

        PushResponse r = push(ALICE, PHONE, "Phone",
                newNote(tooLong, emoji.repeat(NoteLimits.TITLE_MAX + 1)),   // 301 characters, 602 UTF-16 units
                newNote(fine, "fine"));

        assertThat(r.results().get(0).status()).isEqualTo(PushStatus.REJECTED);
        assertThat(r.results().get(0).note()).isNull();
        assertThat(r.results().get(0).error()).contains("300");
        assertThat(r.results().get(1).status()).isEqualTo(PushStatus.APPLIED);
        assertThat(allNotes(ALICE)).extracting(NoteRow::id).containsExactly(fine);
        assertThat(counter(ALICE)).isEqualTo(1);
    }

    @Test
    void aTitleOfExactly300Characters_isAccepted() {
        String emoji = new String(Character.toChars(0x1F600));
        PushResultDto r = only(push(ALICE, PHONE, "Phone", newNote(N, emoji.repeat(NoteLimits.TITLE_MAX))));
        assertThat(r.status()).isEqualTo(PushStatus.APPLIED);
    }

    /** The 2.6 obligation: a long emoji device name and a 300-character title still save a copy. */
    @Test
    void longDeviceName_isCutByCharacters_andTheCopyStillFitsTheTitleColumn() {
        String emoji = new String(Character.toChars(0x1F600));
        String title300 = emoji.repeat(NoteLimits.TITLE_MAX);
        String name70 = emoji.repeat(70);                                     // > 60 characters
        seedNoteAt10(ALICE, "Groceries", "eggs");
        push(ALICE, PHONE, "Phone", change(N, 10, "Server title", null, null, null));   // title at 11 by phone

        PushResultDto r = only(push(ALICE, TABLET, name70, change(N, 10, title300, null, null, null)));

        assertThat(r.status()).isEqualTo(PushStatus.CONFLICT_COPY);
        NoteRow copy = allNotes(ALICE).stream().filter(n -> !n.id().equals(N)).findFirst().orElseThrow();
        assertThat(NoteLimits.length(copy.values().title())).isEqualTo(NoteLimits.TITLE_MAX);
        assertThat(copy.values().title()).endsWith(" (conflicted copy from " + emoji.repeat(60) + ")");
    }

    /** PostgreSQL text can't hold U+0000. One such note must not fail its whole push on every retry. */
    @Test
    void aNulCharacterInANote_rejectsThatNote_andTheRestOfThePushApplies() {
        UUID badTitle = UUID.randomUUID();
        UUID badBody = UUID.randomUUID();
        UUID fine = UUID.randomUUID();

        PushResponse r = push(ALICE, PHONE, "Phone",
                newNote(badTitle, "a\u0000b"),
                change(badBody, 0, "t", "x\u0000y", false, false),
                newNote(fine, "fine"));

        assertThat(r.results()).extracting(PushResultDto::status)
                .containsExactly(PushStatus.REJECTED, PushStatus.REJECTED, PushStatus.APPLIED);
        assertThat(allNotes(ALICE)).extracting(NoteRow::id).containsExactly(fine);
    }

    @Test
    void aNulCharacterInTheDeviceName_isRemoved_soAConflictedCopyStillSaves() {
        seedNoteAt10(ALICE, "Groceries", "eggs");
        push(ALICE, PHONE, "Phone", change(N, 10, null, "eggs, milk", null, null));

        PushResultDto r = only(push(ALICE, TABLET, "Tab\u0000let", change(N, 10, null, "eggs, bread", null, null)));

        assertThat(r.status()).isEqualTo(PushStatus.CONFLICT_COPY);
        NoteRow copy = allNotes(ALICE).stream().filter(n -> !n.id().equals(N)).findFirst().orElseThrow();
        assertThat(copy.values().title()).isEqualTo("Groceries (conflicted copy from Tablet)");
    }

    /** JSON can carry half of an emoji ("\ud83d"). It must not break the push either. */
    @Test
    void anUnpairedSurrogate_doesNotFailThePush() {
        PushResultDto r = only(push(ALICE, PHONE, "Phone", newNote(N, "half \uD83D emoji")));
        assertThat(r.status()).isIn(PushStatus.APPLIED, PushStatus.REJECTED);
    }

    @Test
    void duplicateIdsInOnePush_areRejectedAsAWhole_andWriteNothing() {
        assertThatThrownBy(() -> push(ALICE, PHONE, "Phone", newNote(N, "one"), newNote(N, "two")))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(allNotes(ALICE)).isEmpty();
        assertThat(counter(ALICE)).isZero();
    }

    @Test
    void accounts_areIsolated_evenWithTheSameNoteId() {
        push(ALICE, PHONE, "Phone", newNote(N, "alice's"));
        push(BOB, PHONE, "Phone", newNote(N, "bob's"));
        push(BOB, PHONE, "Phone", change(N, 1, "bob's v2", null, null, null));

        assertThat(sync.pull(ALICE, 0, 200).notes()).extracting(NoteDto::title).containsExactly("alice's");
        assertThat(sync.pull(BOB, 0, 200).notes()).extracting(NoteDto::title).containsExactly("bob's v2");
        assertThat(counter(ALICE)).isEqualTo(1);
        assertThat(counter(BOB)).isEqualTo(2);
    }

    // ---- pull ---------------------------------------------------------------------------------

    @Test
    void pull_pagesThroughChanges_andReturnsSinceAsTheCursorWhenEmpty() {
        for (int i = 0; i < 5; i++) {
            push(ALICE, PHONE, "Phone", newNote(UUID.randomUUID(), "n" + i));
        }
        PullResponse p1 = sync.pull(ALICE, 0, 2);
        assertThat(p1.notes()).extracting(NoteDto::version).containsExactly(1L, 2L);
        assertThat(p1.nextCursor()).isEqualTo(2);
        assertThat(p1.hasMore()).isTrue();

        PullResponse p3 = sync.pull(ALICE, 4, 2);
        assertThat(p3.notes()).extracting(NoteDto::version).containsExactly(5L);
        assertThat(p3.hasMore()).isFalse();

        PullResponse empty = sync.pull(ALICE, 5, 2);
        assertThat(empty.notes()).isEmpty();
        assertThat(empty.nextCursor()).isEqualTo(5);
    }

    @Test
    void pull_clampsTheLimit_andRejectsANegativeCursor() {
        for (int i = 0; i < 3; i++) {
            push(ALICE, PHONE, "Phone", newNote(UUID.randomUUID(), "n" + i));
        }
        assertThat(sync.pull(ALICE, 0, 0).notes()).hasSize(1);               // 0 is raised to 1
        assertThat(sync.pull(ALICE, 0, 1_000_000).notes()).hasSize(3);       // capped at 500
        assertThatThrownBy(() -> sync.pull(ALICE, -1, 200)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aPushResult_isExactlyWhatALaterPullReturns() {
        PushResultDto pushed = only(push(ALICE, PHONE, "Phone", newNote(N, "Trip ideas")));
        NoteDto pulled = sync.pull(ALICE, 0, 200).notes().getFirst();
        assertThat(pulled).isEqualTo(pushed.note());                          // incl. microsecond timestamps
    }
}
