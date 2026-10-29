package com.jezrel.syncnotes.sync;

import com.jezrel.syncnotes.common.InvalidRequestException;
import com.jezrel.syncnotes.sync.dto.ChangeDto;
import com.jezrel.syncnotes.sync.dto.NoteDto;
import com.jezrel.syncnotes.sync.dto.PullResponse;
import com.jezrel.syncnotes.sync.dto.PushRequest;
import com.jezrel.syncnotes.sync.dto.PushResponse;
import com.jezrel.syncnotes.sync.dto.PushResultDto;
import com.jezrel.syncnotes.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Push and pull (ARCHITECTURE.md sections 9.5, 9.6). The merge decisions are {@link MergeEngine}'s;
 * this class owns the transaction, the per-user change counter and stamping, which must match the
 * executable model's {@code Server.push} exactly.
 */
@Service
public class SyncService {

    static final int PULL_DEFAULT = 200;
    static final int PULL_MAX = 500;

    private final UserRepository users;
    private final NoteRepository notes;
    private final Clock clock;

    public SyncService(UserRepository users, NoteRepository notes, Clock clock) {
        this.users = users;
        this.notes = notes;
        this.clock = clock;
    }

    /**
     * Applies a push in one transaction. Request-level shape (1-50 changes, UUIDs, bases) is
     * validated by the caller; this method rejects duplicate ids and over-long notes.
     */
    @Transactional
    public PushResponse push(UUID userId, PushRequest request) {
        rejectDuplicateIds(request.changes());
        UUID deviceId = request.deviceId();
        // Cut by code points so the M4 copy title stays within its limit (section 8.4).
        String deviceName = NoteLimits.truncate(request.deviceName(), NoteLimits.DEVICE_NAME_MAX);

        // Lock first: a second push for this user waits here, so change numbers become visible
        // in order (9.3). The note SELECT below then sees every earlier push's committed rows.
        long counter = users.lockAndGetSyncVersion(userId);
        Map<UUID, NoteRow> current = new HashMap<>(
                notes.findByIds(userId, request.changes().stream().map(ChangeDto::id).toList()));
        Instant now = now();
        List<PushResultDto> results = new ArrayList<>(request.changes().size());

        for (ChangeDto change : request.changes()) {
            Optional<String> invalid = tooLong(change);
            if (invalid.isPresent()) {
                results.add(PushResultDto.rejected(change.id(), invalid.get()));
                continue;
            }
            NoteRow before = current.get(change.id());
            MergeResult merge = MergeEngine.merge(before == null ? null : before.toServerNote(),
                    toIncoming(change), deviceId, deviceName);

            NoteRow saved = before;
            if (merge.mainChanged()) {
                counter++;
                saved = stamp(before, change, merge, counter, deviceId, now);
                notes.save(userId, saved);
                current.put(saved.id(), saved);
            }
            if (merge.conflictCopy() != null) {
                counter++;                                   // the copy gets its own number
                notes.save(userId, new NoteRow(UUID.randomUUID(),
                        new NoteValues(merge.conflictCopy().title(), merge.conflictCopy().body(), false, false),
                        PerField.all(counter), PerField.all(deviceId), counter, now, now));
            }
            results.add(PushResultDto.of(change.id(), merge.status(), NoteDto.from(saved)));
        }
        users.setSyncVersion(userId, counter);
        return new PushResponse(results);
    }

    /** Notes changed after {@code since}, oldest first; {@code limit} is clamped to 1-500. */
    @Transactional(readOnly = true)
    public PullResponse pull(UUID userId, long since, int limit) {
        if (since < 0) {
            throw new InvalidRequestException("since must be 0 or more");
        }
        int pageSize = Math.clamp(limit, 1, PULL_MAX);
        List<NoteRow> rows = notes.changedSince(userId, since, pageSize + 1); // one extra = hasMore
        boolean hasMore = rows.size() > pageSize;
        List<NoteRow> page = hasMore ? rows.subList(0, pageSize) : rows;
        long nextCursor = page.isEmpty() ? since : page.getLast().serverVersion();
        return new PullResponse(page.stream().map(NoteDto::from).toList(), nextCursor, hasMore);
    }

    /** The row after a merge changed it: changed fields get the new number and this device (9.4). */
    private static NoteRow stamp(NoteRow before, ChangeDto change, MergeResult merge, long counter,
                                 UUID deviceId, Instant now) {
        if (before == null) {                                // M1: every field is new
            return new NoteRow(change.id(), merge.after(), PerField.all(counter), PerField.all(deviceId),
                    counter, change.createdAt().truncatedTo(ChronoUnit.MICROS), now);
        }
        PerField<Long> versions = before.fieldVersions();
        PerField<UUID> devices = before.fieldDevices();
        for (NoteField f : merge.changedFields()) {
            versions = versions.with(f, counter);
            devices = devices.with(f, deviceId);
        }
        return new NoteRow(before.id(), merge.after(), versions, devices, counter, before.createdAt(), now);
    }

    private static IncomingChange toIncoming(ChangeDto c) {
        return new IncomingChange(c.id(), c.baseVersions().toPerField(), c.title(), c.body(), c.pinned(), c.trashed());
    }

    /** Lengths in code points, as PostgreSQL's char_length counts them (section 8.4). */
    private static Optional<String> tooLong(ChangeDto c) {
        if (c.title() != null && NoteLimits.length(c.title()) > NoteLimits.TITLE_MAX) {
            return Optional.of("Title is longer than " + NoteLimits.TITLE_MAX + " characters");
        }
        if (c.body() != null && NoteLimits.length(c.body()) > NoteLimits.BODY_MAX) {
            return Optional.of("Body is longer than " + NoteLimits.BODY_MAX + " characters");
        }
        return Optional.empty();
    }

    private static void rejectDuplicateIds(List<ChangeDto> changes) {
        Set<UUID> seen = new HashSet<>();
        for (ChangeDto c : changes) {
            if (!seen.add(c.id())) {
                throw new InvalidRequestException("Duplicate note id in one push: " + c.id());
            }
        }
    }

    // PostgreSQL keeps microseconds; truncating here makes push results equal what a pull returns.
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
