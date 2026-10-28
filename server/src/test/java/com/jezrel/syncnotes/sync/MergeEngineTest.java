package com.jezrel.syncnotes.sync;

import static com.jezrel.syncnotes.sync.NoteField.BODY;
import static com.jezrel.syncnotes.sync.NoteField.PINNED;
import static com.jezrel.syncnotes.sync.NoteField.TITLE;
import static com.jezrel.syncnotes.sync.NoteField.TRASHED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Rule-by-rule tests for ARCHITECTURE.md section 9.4, plus the worked examples of section 9.8. */
class MergeEngineTest {

    static final UUID D1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID D2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID NOTE = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final String TABLET = "Tablet";

    /** A server note whose every field was last written at version 10 by D1. */
    static ServerNote note(String title, String body, boolean pinned, boolean trashed) {
        return new ServerNote(new NoteValues(title, body, pinned, trashed), PerField.all(10L), PerField.all(D1), 10);
    }

    static ServerNote note() {
        return note("Groceries", "eggs", false, false);
    }

    /** The same note after {@code field} was written at {@code version} by {@code device}. */
    static ServerNote written(ServerNote n, NoteField field, long version, UUID device) {
        return new ServerNote(n.values(), n.fieldVersions().with(field, version), n.fieldDevices().with(field, device),
                Math.max(n.serverVersion(), version));
    }

    static IncomingChange change(long base, String title, String body, Boolean pinned, Boolean trashed) {
        return new IncomingChange(NOTE, PerField.all(base), title, body, pinned, trashed);
    }

    static MergeResult merge(ServerNote current, IncomingChange change, UUID device) {
        return MergeEngine.merge(current, change, device, TABLET);
    }

    @Nested
    class Rules {

        @Test
        void m1_missingNote_isCreatedFromSentFields_withDefaultsForUnsent() {
            MergeResult r = merge(null, change(0, "Trip ideas", null, null, null), D2);
            assertThat(r.status()).isEqualTo(PushStatus.APPLIED);
            assertThat(r.before()).isNull();
            assertThat(r.after()).isEqualTo(new NoteValues("Trip ideas", "", false, false));
            assertThat(r.changedFields()).containsExactlyInAnyOrder(NoteField.values());
            assertThat(r.rules()).containsExactly(MergeRule.M1);
        }

        @Test
        void m2_resendingTheServersValue_isANoOp() {
            ServerNote cur = written(note("Groceries", "eggs, rice", false, false), BODY, 11, D2);
            MergeResult r = merge(cur, change(10, null, "eggs, rice", null, null), D2);
            assertThat(r.status()).isEqualTo(PushStatus.NOOP);
            assertThat(r.mainChanged()).isFalse();
            assertThat(r.rules()).contains(MergeRule.M2);
        }

        @Test
        void m3_nonConcurrentContent_isApplied() {
            MergeResult r = merge(note(), change(10, "Shopping", null, null, null), D2);
            assertThat(r.status()).isEqualTo(PushStatus.APPLIED);
            assertThat(r.after().title()).isEqualTo("Shopping");
            assertThat(r.changedFields()).containsExactly(TITLE);
            assertThat(r.rules()).containsExactly(MergeRule.M3);
        }

        @Test
        void m4_trueConflict_keepsServerText_andCopiesTheIncomingText() {
            ServerNote cur = written(note("Groceries", "eggs, milk", false, false), BODY, 11, D1);
            MergeResult r = merge(cur, change(10, null, "eggs, bread", null, null), D2);
            assertThat(r.status()).isEqualTo(PushStatus.CONFLICT_COPY);
            assertThat(r.mainChanged()).isFalse();
            assertThat(r.after().body()).isEqualTo("eggs, milk");
            // The unsent title is filled in from the server.
            assertThat(r.conflictCopy()).isEqualTo(
                    new ConflictCopy("Groceries (conflicted copy from Tablet)", "eggs, bread"));
            assertThat(r.rules()).contains(MergeRule.M4);
        }

        @Test
        void m4_aSentEmptyString_isAValue_notAbsent() {
            ServerNote cur = written(note("Server title", "eggs", false, false), TITLE, 11, D1);
            MergeResult r = merge(cur, change(10, "", null, null, null), D2);
            assertThat(r.conflictCopy().title()).isEqualTo(" (conflicted copy from Tablet)");
            assertThat(r.conflictCopy().body()).isEqualTo("eggs");
        }

        @Test
        void m4_copyTitle_isShortenedToFitTheTitleLimit() {
            ServerNote cur = written(note(), TITLE, 11, D1);
            String longName = "N".repeat(NoteLimits.DEVICE_NAME_MAX);
            MergeResult r = MergeEngine.merge(cur, change(10, "x".repeat(NoteLimits.TITLE_MAX), null, null, null), D2, longName);
            String title = r.conflictCopy().title();
            assertThat(NoteLimits.length(title)).isEqualTo(NoteLimits.TITLE_MAX);
            assertThat(title).endsWith(" (conflicted copy from " + longName + ")");
        }

        @Test
        void m4_shorteningNeverSplitsACharacter() {
            ServerNote cur = written(note(), TITLE, 11, D1);
            String emoji = new String(Character.toChars(0x1F600));            // two UTF-16 units
            String incoming = "a".repeat(200) + emoji.repeat(100);
            MergeResult r = MergeEngine.merge(cur, change(10, incoming, null, null, null), D2, "N".repeat(60));
            String title = r.conflictCopy().title();
            assertThat(NoteLimits.length(title)).isEqualTo(NoteLimits.TITLE_MAX);
            assertThat(title.chars().filter(c -> Character.isSurrogate((char) c)).count() % 2).isZero();
            String kept = title.substring(0, title.indexOf(" (conflicted copy from"));
            assertThat(Character.isHighSurrogate(kept.charAt(kept.length() - 1))).isFalse();
        }

        @Test
        void lastWriterCheck_ownEarlierWrite_isNotAConflict() {
            ServerNote cur = written(note("Groceries", "ab", false, false), BODY, 11, D2);
            MergeResult r = merge(cur, change(10, null, "abc", null, null), D2);
            assertThat(r.status()).isEqualTo(PushStatus.APPLIED);
            assertThat(r.conflictCopy()).isNull();
            assertThat(r.after().body()).isEqualTo("abc");
        }

        @Test
        void perFieldBase_onlyAFieldChangedSinceItsOwnBase_isConcurrent() {
            ServerNote cur = written(written(note(), TITLE, 12, D1), BODY, 11, D1);
            // The device saw the body at 11 but the title only at 10; it sends just the body.
            IncomingChange c = new IncomingChange(NOTE, new PerField<>(10L, 11L, 10L, 10L), null, "eggs, rice", null, null);
            MergeResult r = merge(cur, c, D2);
            assertThat(r.status()).isEqualTo(PushStatus.APPLIED);
            assertThat(r.after().body()).isEqualTo("eggs, rice");
        }

        @Test
        void m5_nonConcurrentPin_isApplied() {
            MergeResult r = merge(note(), change(10, null, null, true, null), D2);
            assertThat(r.after().pinned()).isTrue();
            assertThat(r.rules()).containsExactly(MergeRule.M5);
        }

        @Test
        void m6_concurrentPin_keepsTheServersValue() {
            ServerNote cur = written(note("Groceries", "eggs", true, false), PINNED, 11, D1);
            MergeResult r = merge(cur, change(10, null, null, false, null), D2);
            assertThat(r.status()).isEqualTo(PushStatus.NOOP);
            assertThat(r.after().pinned()).isTrue();
            assertThat(r.rules()).containsExactly(MergeRule.M6);
        }

        @Test
        void m7_restore_alwaysApplies_evenWhenConcurrent() {
            ServerNote cur = written(note("Groceries", "eggs", false, true), TRASHED, 11, D1);
            MergeResult r = merge(cur, change(10, null, null, null, false), D2);
            assertThat(r.after().trashed()).isFalse();
            assertThat(r.rules()).containsExactly(MergeRule.M7);
        }

        @Test
        void m8_trash_isIgnored_whenContentWasEditedElsewhere() {
            ServerNote cur = written(note(), BODY, 11, D1);
            MergeResult r = merge(cur, change(10, null, null, null, true), D2);
            assertThat(r.after().trashed()).isFalse();
            assertThat(r.status()).isEqualTo(PushStatus.NOOP);
            assertThat(r.rules()).containsExactly(MergeRule.M8);
        }

        @Test
        void m8_trash_isIgnored_whenTrashedWasChangedElsewhere() {
            ServerNote cur = written(note(), TRASHED, 11, D1);  // e.g. restored on another device
            MergeResult r = merge(cur, change(10, null, null, null, true), D2);
            assertThat(r.after().trashed()).isFalse();
            assertThat(r.rules()).containsExactly(MergeRule.M8);
        }

        @Test
        void m9_trash_isApplied_otherwise() {
            MergeResult r = merge(note(), change(10, null, null, null, true), D2);
            assertThat(r.after().trashed()).isTrue();
            assertThat(r.rules()).containsExactly(MergeRule.M9);
        }

        @Test
        void m10_anEdit_restoresANoteTrashedElsewhere() {
            ServerNote cur = written(note("Groceries", "eggs", false, true), TRASHED, 11, D1);
            MergeResult r = merge(cur, change(10, null, "eggs, rice", null, null), D2);
            assertThat(r.after()).isEqualTo(new NoteValues("Groceries", "eggs, rice", false, false));
            assertThat(r.rules()).containsExactlyInAnyOrder(MergeRule.M3, MergeRule.M10);
        }

        @Test
        void status_conflictCopyWins_evenWhenOtherFieldsWereApplied() {
            ServerNote cur = written(note(), BODY, 11, D1);
            MergeResult r = merge(cur, change(10, null, "mine", true, null), D2);
            assertThat(r.status()).isEqualTo(PushStatus.CONFLICT_COPY);
            assertThat(r.mainChanged()).isTrue();                     // the pin still applied (M5)
            assertThat(r.changedFields()).isEqualTo(EnumSet.of(PINNED));
        }
    }

    /** Section 9.8, step by step. The helper below stamps writes the way the sync service will. */
    @Nested
    class WorkedExamples {

        ServerNote stamp(ServerNote before, MergeResult r, long counter, UUID device) {
            if (before == null) {
                return new ServerNote(r.after(), PerField.all(counter), PerField.all(device), counter);
            }
            PerField<Long> v = before.fieldVersions();
            PerField<UUID> d = before.fieldDevices();
            for (NoteField f : r.changedFields()) {
                v = v.with(f, counter);
                d = d.with(f, device);
            }
            return new ServerNote(r.after(), v, d, counter);
        }

        @Test
        void example1_differentFieldsMergeCleanly() {
            ServerNote n10 = note("Groceries", "eggs", false, false);
            MergeResult tablet = merge(n10, change(10, null, "eggs, rice", null, null), D2);
            assertThat(tablet.status()).isEqualTo(PushStatus.APPLIED);
            ServerNote n11 = stamp(n10, tablet, 11, D2);

            MergeResult phone = MergeEngine.merge(n11, change(10, "Shopping", null, null, null), D1, "Phone");
            assertThat(phone.status()).isEqualTo(PushStatus.APPLIED);
            ServerNote n12 = stamp(n11, phone, 12, D1);

            assertThat(n12.values()).isEqualTo(new NoteValues("Shopping", "eggs, rice", false, false));
            assertThat(n12.serverVersion()).isEqualTo(12);
            assertThat(phone.conflictCopy()).isNull();
        }

        @Test
        void example2_sameFieldMakesAConflictedCopy() {
            ServerNote n10 = note("Groceries", "eggs", false, false);
            MergeResult phone = MergeEngine.merge(n10, change(10, null, "eggs, milk", null, null), D1, "Phone");
            ServerNote n11 = stamp(n10, phone, 11, D1);

            MergeResult tablet = merge(n11, change(10, null, "eggs, bread", null, null), D2);
            assertThat(tablet.status()).isEqualTo(PushStatus.CONFLICT_COPY);
            assertThat(tablet.mainChanged()).isFalse();               // N stays at 11
            assertThat(tablet.after().body()).isEqualTo("eggs, milk");
            assertThat(tablet.conflictCopy().body()).isEqualTo("eggs, bread");
        }

        @Test
        void example3_lostResponse_isNotAFalseConflict() {
            ServerNote n10 = note("Groceries", "a", false, false);
            MergeResult first = MergeEngine.merge(n10, change(10, null, "ab", null, null), D1, "Phone");
            ServerNote n11 = stamp(n10, first, 11, D1);
            // The response is lost: the phone's base is still 10 and it keeps typing.
            MergeResult retry = MergeEngine.merge(n11, change(10, null, "abc", null, null), D1, "Phone");
            assertThat(retry.status()).isEqualTo(PushStatus.APPLIED);
            assertThat(retry.conflictCopy()).isNull();
            assertThat(stamp(n11, retry, 12, D1).values().body()).isEqualTo("abc");
        }
    }
}
