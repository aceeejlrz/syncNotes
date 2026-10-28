package com.jezrel.syncnotes.sync;

import static com.jezrel.syncnotes.sync.NoteField.BODY;
import static com.jezrel.syncnotes.sync.NoteField.PINNED;
import static com.jezrel.syncnotes.sync.NoteField.TITLE;
import static com.jezrel.syncnotes.sync.NoteField.TRASHED;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The merge rules of ARCHITECTURE.md section 9.4 (with per-field bases, ADR-018). Pure: no Spring,
 * no SQL, no clock. Verified against the executable model through golden vectors
 * (MergeEngineVectorTest); keep this in step with docs/sync_protocol_model.py (rule R3).
 */
public final class MergeEngine {

    private MergeEngine() {
    }

    /**
     * Merges one change from {@code deviceId} into {@code current} (null if the note doesn't exist
     * on the server). {@code deviceName} must already be validated (at most 60 characters).
     */
    public static MergeResult merge(ServerNote current, IncomingChange change, UUID deviceId, String deviceName) {
        if (current == null) {                                                         // M1
            NoteValues created = new NoteValues("", "", false, false);
            for (NoteField f : NoteField.values()) {
                if (change.sends(f)) {
                    created = created.with(f, change.sentValue(f));
                }
            }
            return new MergeResult(null, created, EnumSet.allOf(NoteField.class), null,
                    PushStatus.APPLIED, EnumSet.of(MergeRule.M1));
        }

        Rule rule = new Rule(current, change, deviceId);
        NoteValues before = current.values();
        NoteValues next = before;
        ConflictCopy copy = null;
        Set<MergeRule> fired = EnumSet.noneOf(MergeRule.class);

        for (NoteField f : NoteField.values()) {
            if (change.sends(f) && !rule.differs(f)) {
                fired.add(MergeRule.M2);                                               // M2
            }
        }

        // content: title and body
        boolean contentApplied;
        if ((rule.differs(TITLE) && rule.concurrent(TITLE)) || (rule.differs(BODY) && rule.concurrent(BODY))) {
            String title = change.sends(TITLE) ? change.title() : before.title();
            String body = change.sends(BODY) ? change.body() : before.body();
            copy = new ConflictCopy(ConflictCopy.titleFor(title, deviceName), body);   // M4
            fired.add(MergeRule.M4);
            contentApplied = false;
        } else {
            for (NoteField f : NoteField.CONTENT) {
                if (rule.differs(f)) {
                    next = next.with(f, change.sentValue(f));                          // M3
                    fired.add(MergeRule.M3);
                }
            }
            contentApplied = rule.differs(TITLE) || rule.differs(BODY);
        }

        // pinned: first write wins
        if (rule.differs(PINNED)) {
            if (!rule.concurrent(PINNED)) {
                next = next.with(PINNED, change.pinned());                             // M5
                fired.add(MergeRule.M5);
            } else {
                fired.add(MergeRule.M6);                                               // M6: keep server
            }
        }

        // trashed: edits beat deletes
        if (rule.differs(TRASHED)) {
            if (!change.trashed()) {
                next = next.with(TRASHED, false);                                      // M7
                fired.add(MergeRule.M7);
            } else if (rule.concurrent(TITLE) || rule.concurrent(BODY) || rule.concurrent(TRASHED)) {
                fired.add(MergeRule.M8);                                               // M8: ignore trash
            } else {
                next = next.with(TRASHED, true);                                       // M9
                fired.add(MergeRule.M9);
            }
        }
        if (contentApplied && before.trashed() && rule.concurrent(TRASHED)) {
            next = next.with(TRASHED, false);                                          // M10
            fired.add(MergeRule.M10);
        }

        Set<NoteField> changed = EnumSet.noneOf(NoteField.class);
        for (NoteField f : NoteField.values()) {
            if (!Objects.equals(next.get(f), before.get(f))) {
                changed.add(f);
            }
        }
        PushStatus status = copy != null ? PushStatus.CONFLICT_COPY
                : changed.isEmpty() ? PushStatus.NOOP : PushStatus.APPLIED;
        return new MergeResult(before, next, changed, copy, status, fired);
    }

    /** The two predicates of section 9.4 for one change against one server note. */
    private record Rule(ServerNote current, IncomingChange change, UUID deviceId) {

        /** The sent value differs from the server's current value. */
        boolean differs(NoteField f) {
            return change.sends(f) && !Objects.equals(change.sentValue(f), current.values().get(f));
        }

        /** Another device changed this field after this device last saw that field. */
        boolean concurrent(NoteField f) {
            return current.fieldVersions().get(f) > change.baseVersions().get(f)
                    && !current.fieldDevices().get(f).equals(deviceId);
        }
    }
}
