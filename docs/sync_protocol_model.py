#!/usr/bin/env python3
"""Executable model of the SyncNotes sync protocol (ARCHITECTURE.md section 9).

Rule R3 makes this the third leg of the merge contract, next to the server's MergeEngine (Java)
and the app's ChangeRules (Kotlin). It contains:

  * the server: per-user change counter, merge rules M1-M10 (section 9.4), push (9.5), pull (9.6)
  * the app:    apply-after-push (rule A) and apply-after-pull (rule B) from section 9.7,
                 with per-field base versions (ADR-018)
  * a scheduler that interleaves edits, syncs, and lost or late messages the way the real
    SyncEngine allows (section 9.9 / 9.10)
  * replays of the three worked examples in section 9.8
  * randomized runs that check invariants, with shrinking to a minimal failing trace

The pure rule functions (merge, apply_push_result, apply_pulled) take values in and return values
out, with no I/O, so they port directly to Java and Kotlin tests (section 12.4).

Usage:
  python docs/sync_protocol_model.py                     # examples + self-check + 1000 x 300 runs
  python docs/sync_protocol_model.py --rules v1.4 --runs 200   # the superseded rules (fail)
  python docs/sync_protocol_model.py --trace 42          # print one run's events
Exit status is non-zero if any example, self-check or invariant fails.
Standard library only.
"""

from __future__ import annotations

import argparse
import random
import sys
import time
from dataclasses import dataclass, field

FIELDS = ("title", "body", "pinned", "trashed")
TEXT = ("title", "body")

APPLIED, NOOP, CONFLICT_COPY, REJECTED = "APPLIED", "NOOP", "CONFLICT_COPY", "REJECTED"


# --------------------------------------------------------------------------------------------
# Rules: which variant to run. "contract" is ARCHITECTURE.md section 9 as specified.
# --------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Rules:
    name: str
    # server
    last_writer_check: bool = True       # concurrent(f) also requires "last writer != D"
    field_bases: bool = False            # client sends a base version per field (variant)
    # app
    push_apply: str = "doc"              # "doc" | "snapshot" | "fieldbase"
    pull_apply: str = "doc"              # "doc" | "snapshot" | "fieldbase"
    pull_dirty_advance_base: bool = False  # deliberate bug (v1.4 rules), self-check only
    advance_dirty_field_bases: bool = False  # deliberate bug (contract rules), self-check only


# The contract (ARCHITECTURE.md section 9, ADR-018): per-field base versions. NoteDto carries the
# four field versions, every change carries the device's base for all four fields, and
# concurrent(f) compares the server's field version against that field's base.
CONTRACT = Rules("contract", field_bases=True, push_apply="fieldbase", pull_apply="fieldbase")
# Superseded v1.4 rules (one note-level base). Kept so the two holes the model found stay
# reproducible: --rules v1.4 fails about 14% of runs (lost text and false conflicts).
V14 = Rules("v1.4")
# The alternative fix that was considered: same protocol as v1.4, stricter apply rules (keep the
# local note a consistent snapshot at its base). Passes too, with worse visibility; see 9.12.
SNAPSHOT = Rules("snapshot", push_apply="snapshot", pull_apply="snapshot")
RULES = {"contract": CONTRACT, "v1.4": V14, "snapshot": SNAPSHOT}


# --------------------------------------------------------------------------------------------
# Server
# --------------------------------------------------------------------------------------------

@dataclass
class ServerNote:
    id: str
    title: str
    body: str
    pinned: bool
    trashed: bool
    fv: dict            # field -> change number of its last write
    fd: dict            # field -> device that made that write
    version: int        # server_version: change number of the latest write to the row

    def values(self) -> dict:
        return {f: getattr(self, f) for f in FIELDS}

    def dto(self) -> dict:
        return {"id": self.id, **self.values(), "version": self.version, "fv": dict(self.fv)}


@dataclass(frozen=True)
class Change:
    id: str
    base: int                    # note-level base version (section 9.1)
    sent: dict                   # only the dirty fields: field -> value
    fbase: dict | None = None    # per-field bases (field_bases variant only)


@dataclass
class MergeResult:
    before: dict | None          # server values before (None if the note didn't exist)
    after: dict                  # server values after
    changed: set                 # fields whose value changed on the main note
    copy: dict | None            # {"title", "body"} of a conflicted copy, if M4 fired
    conflict_fields: set         # text fields that triggered M4
    status: str


def merge(current: ServerNote | None, change: Change, device: str, device_name: str,
          rules: Rules) -> MergeResult:
    """MergeEngine.merge: the pseudocode in section 9.4, line for line. Pure."""
    if current is None:                                                        # M1
        after = {"title": "", "body": "", "pinned": False, "trashed": False, **change.sent}
        return MergeResult(None, after, set(FIELDS), None, set(), APPLIED)

    def base(f):
        if rules.field_bases and change.fbase is not None:
            return change.fbase.get(f, change.base)
        return change.base

    def concurrent(f):
        newer = current.fv[f] > base(f)
        return newer and (current.fd[f] != device if rules.last_writer_check else True)

    def differs(f):
        return f in change.sent and change.sent[f] != getattr(current, f)

    nxt = current.values()
    copy = None
    conflict_fields = {f for f in TEXT if differs(f) and concurrent(f)}

    if conflict_fields:                                                        # M4
        copy = {
            "title": change.sent.get("title", current.title)
                     + f" (conflicted copy from {device_name})",
            "body": change.sent.get("body", current.body),
        }
        content_applied = False
    else:
        for f in TEXT:
            if differs(f):
                nxt[f] = change.sent[f]                                        # M3
        content_applied = differs("title") or differs("body")

    if differs("pinned") and not concurrent("pinned"):
        nxt["pinned"] = change.sent["pinned"]                                  # M5, M6

    if differs("trashed"):
        if change.sent["trashed"] is False:
            nxt["trashed"] = False                                             # M7
        elif not (concurrent("title") or concurrent("body") or concurrent("trashed")):
            nxt["trashed"] = True                                              # M8, M9
    if content_applied and current.trashed and concurrent("trashed"):
        nxt["trashed"] = False                                                 # M10

    changed = {f for f in FIELDS if nxt[f] != getattr(current, f)}
    status = CONFLICT_COPY if copy else (APPLIED if changed else NOOP)
    return MergeResult(current.values(), nxt, changed, copy, conflict_fields, status)


class Server:
    """One user's server state. Operations are atomic, which models the user-row lock (9.3)."""

    def __init__(self, rules: Rules, title_max: int | None = None, body_max: int | None = None):
        self.rules = rules
        self.counter = 0
        self.notes: dict[str, ServerNote] = {}
        self.copies = 0
        self.title_max, self.body_max = title_max, body_max
        self.on_conflict = None    # hook(device, change, current, merge_result) for invariants
        self.log = None

    def push(self, device: str, device_name: str, changes: list[Change]) -> list[dict]:
        results = []
        for ch in changes:
            too_long = (
                (self.title_max is not None and len(ch.sent.get("title", "")) > self.title_max)
                or (self.body_max is not None and len(ch.sent.get("body", "")) > self.body_max)
            )
            if too_long:
                results.append({"id": ch.id, "status": REJECTED, "note": None, "error": "too long"})
                continue
            cur = self.notes.get(ch.id)
            m = merge(cur, ch, device, device_name, self.rules)
            if m.copy is not None and self.on_conflict:
                self.on_conflict(device, ch, cur, m)
            if cur is None:
                self.counter += 1
                cur = ServerNote(ch.id, **m.after, fv={f: self.counter for f in FIELDS},
                                 fd={f: device for f in FIELDS}, version=self.counter)
                self.notes[ch.id] = cur
            elif m.changed:
                self.counter += 1
                for f in m.changed:
                    setattr(cur, f, m.after[f])
                    cur.fv[f], cur.fd[f] = self.counter, device
                cur.version = self.counter
            if m.copy is not None:
                self.counter += 1
                self.copies += 1
                cid = f"copy{self.copies}"
                self.notes[cid] = ServerNote(
                    cid, m.copy["title"], m.copy["body"], False, False,
                    fv={f: self.counter for f in FIELDS}, fd={f: device for f in FIELDS},
                    version=self.counter)
            if self.log is not None:
                self.log(f"  server: {ch.id} base={ch.base} sent={ch.sent} -> {m.status}"
                         f" v{cur.version}" + (f" + copy{self.copies}" if m.copy else ""))
            results.append({"id": ch.id, "status": m.status, "note": cur.dto(), "error": None})
        return results

    def pull(self, since: int, limit: int) -> dict:
        rows = sorted((n for n in self.notes.values() if n.version > since), key=lambda n: n.version)
        page = rows[:limit]
        return {"notes": [n.dto() for n in page],
                "next_cursor": page[-1].version if page else since,
                "has_more": len(rows) > limit}


# --------------------------------------------------------------------------------------------
# App (device)
# --------------------------------------------------------------------------------------------

@dataclass
class LocalNote:
    id: str
    title: str
    body: str
    pinned: bool
    trashed: bool
    base: int                          # NoteEntity.serverVersion: the base version
    dirty: set = field(default_factory=set)
    sync_error: str | None = None
    fbase: dict = field(default_factory=lambda: {f: 0 for f in FIELDS})  # fieldbase variant

    def values(self) -> dict:
        return {f: getattr(self, f) for f in FIELDS}

    def clone(self) -> "LocalNote":
        return LocalNote(self.id, self.title, self.body, self.pinned, self.trashed, self.base,
                         set(self.dirty), self.sync_error, dict(self.fbase))


def apply_push_result(local: LocalNote, sent: dict, result: dict, rules: Rules,
                      fork_id: str) -> tuple[LocalNote, LocalNote | None]:
    """ChangeRules rule A (section 9.7). Pure: returns (updated local, optional forked note)."""
    n = local.clone()
    if result["status"] == REJECTED:
        n.sync_error = result["error"]
        return n, None
    server = result["note"]
    dirty = {f for f in n.dirty if getattr(n, f) != sent[f]}   # clear bits confirmed by value
    fork = None
    if result["status"] == CONFLICT_COPY and dirty & set(TEXT):
        # the user kept typing while a conflicting push was in flight
        fork = LocalNote(fork_id, n.title, n.body, False, False, base=0, dirty=set(FIELDS))
        dirty -= set(TEXT)

    if rules.push_apply == "doc":
        for f in FIELDS:
            if f not in dirty:
                setattr(n, f, server[f])
        n.base = server["version"]                              # ALWAYS advance after a push
    elif rules.push_apply == "snapshot":
        # Keep the local note a consistent snapshot of the server at `base` plus dirty edits:
        # only take the server's note (and advance base) when nothing is left dirty.
        if not dirty:
            for f in FIELDS:
                setattr(n, f, server[f])
            n.base = server["version"]
    elif rules.push_apply == "fieldbase":
        for f in FIELDS:
            if f not in dirty:
                setattr(n, f, server[f])                       # clean: take value + its version
                n.fbase[f] = server["fv"][f]
            elif rules.advance_dirty_field_bases:              # deliberate bug (self-check)
                n.fbase[f] = server["fv"][f]
        n.base = server["version"]                             # note-level: stale-pull guard only
    else:
        raise ValueError(rules.push_apply)
    n.dirty, n.sync_error = dirty, None
    return n, fork


def apply_pulled(local: LocalNote | None, note: dict, rules: Rules) -> LocalNote | None:
    """ChangeRules rule B (section 9.7). Pure: returns the new local note (None = unchanged)."""
    if local is None:
        return LocalNote(note["id"], note["title"], note["body"], note["pinned"], note["trashed"],
                         base=note["version"], fbase=dict(note["fv"]))
    if note["version"] <= local.base:
        return None                                               # already have this or newer
    n = local.clone()
    if not n.dirty:
        for f in FIELDS:
            setattr(n, f, note[f])
        n.base, n.fbase = note["version"], dict(note["fv"])
        return n
    # local has unsynced edits
    if rules.pull_apply == "doc":
        for f in FIELDS:
            if f not in n.dirty:
                setattr(n, f, note[f])
        if rules.pull_dirty_advance_base:                         # deliberate bug (self-check)
            n.base = note["version"]
        return n                                                  # keep base unchanged
    if rules.pull_apply == "snapshot":
        return None        # skip: the next push's result brings the whole note in consistently
    if rules.pull_apply == "fieldbase":
        for f in FIELDS:
            if f not in n.dirty:
                setattr(n, f, note[f])
                n.fbase[f] = note["fv"][f]
        n.base = note["version"]
        return n
    raise ValueError(rules.pull_apply)


class Device:
    BATCH = 2            # PUSH_BATCH_SIZE (25 in the app; small here so multi-batch pushes happen)
    MAX_BATCHES = 20
    PAGE = 3             # PULL_PAGE_SIZE (200 in the app)

    def __init__(self, dev_id: str, name: str, server: Server, rules: Rules):
        self.id, self.name, self.server, self.rules = dev_id, name, server, rules
        self.notes: dict[str, LocalNote] = {}
        self.cursor = 0
        self.inflight = None      # the one message of the current sync, or None when idle
        self.batches = 0
        self.forks = 0
        self.held: dict[tuple, set] = {}    # (note, field) -> every value this device has held
        self.log = None

    # -- bookkeeping -----------------------------------------------------------------------
    def _put(self, n: LocalNote):
        self.notes[n.id] = n
        for f in TEXT:
            self.held.setdefault((n.id, f), set()).add(getattr(n, f))

    # -- local edits (NotesRepository; the UI forbids editing trashed notes) ---------------
    def create(self, note_id: str, title: str):
        self._put(LocalNote(note_id, title, "", False, False, base=0, dirty=set(FIELDS)))

    def edit(self, note_id: str, f: str, value):
        n = self.notes[note_id].clone()
        if getattr(n, f) == value:
            return
        setattr(n, f, value)
        n.dirty.add(f)
        n.sync_error = None
        self._put(n)

    # -- sync (SyncEngine: one at a time, push batches then pull pages) --------------------
    def start_sync(self) -> bool:
        if self.inflight is not None:
            return False
        self.batches = 0
        self._next_push()
        return True

    def _next_push(self):
        batch = [n for n in self.notes.values() if n.dirty and n.sync_error is None][: self.BATCH]
        if not batch or self.batches >= self.MAX_BATCHES:
            self._next_pull()
            return
        changes, sent = [], {}
        for n in batch:
            fb = dict(n.fbase) if self.rules.field_bases else None   # all four field bases
            changes.append(Change(n.id, n.base, {f: getattr(n, f) for f in n.dirty}, fb))
            sent[n.id] = n.values()
        self.inflight = ("push_req", changes, sent, len(batch))
        if self.log:
            self.log(f"{self.id} builds push: "
                     + ", ".join(f"{c.id} base={c.base} {sorted(c.sent)}" for c in changes))

    def _next_pull(self):
        self.inflight = ("pull_req", self.cursor)

    def deliver(self) -> bool:
        """Deliver the in-flight message one hop (request to server, or response to app)."""
        m = self.inflight
        if m is None:
            return False
        kind = m[0]
        if kind == "push_req":
            _, changes, sent, size = m
            if self.log:
                self.log(f"{self.id} push request reaches server: {[c.id for c in changes]}")
            results = self.server.push(self.id, self.name, changes)
            self.inflight = ("push_resp", results, sent, size)
        elif kind == "push_resp":
            _, results, sent, size = m
            for r in results:                                    # one write transaction
                self.forks += 1
                new, fork = apply_push_result(self.notes[r["id"]], sent[r["id"]], r, self.rules,
                                              f"{self.id}-fork{self.forks}")
                self._put(new)
                if fork:
                    self._put(fork)
                    if self.log:
                        self.log(f"{self.id} forked local note {fork.id}")
            if self.log:
                self.log(f"{self.id} applies push results: " + ", ".join(
                    f"{r['id']} {r['status']} -> base={self.notes[r['id']].base} "
                    f"dirty={sorted(self.notes[r['id']].dirty)}" for r in results))
            self.batches += 1
            if size == self.BATCH:
                self._next_push()
            else:
                self._next_pull()
        elif kind == "pull_req":
            page = self.server.pull(m[1], self.PAGE)
            self.inflight = ("pull_resp", page)
        elif kind == "pull_resp":
            page = m[1]
            for dto in page["notes"]:                            # same transaction as the cursor
                new = apply_pulled(self.notes.get(dto["id"]), dto, self.rules)
                if new is not None:
                    self._put(new)
            self.cursor = page["next_cursor"]
            if self.log:
                self.log(f"{self.id} pulled {[(d['id'], d['version']) for d in page['notes']]}"
                         f" cursor={self.cursor}")
            self.inflight = ("pull_req", self.cursor) if page["has_more"] else None
        return True

    def lose(self) -> bool:
        """Drop the in-flight message: a network error or a REPLACE cancel aborts the sync."""
        if self.inflight is None:
            return False
        if self.log:
            self.log(f"{self.id} LOST {self.inflight[0]} (sync aborted)")
        self.inflight = None
        return True

    def full_sync(self):
        if self.inflight is None:
            self.start_sync()
        while self.inflight is not None:
            self.deliver()


# --------------------------------------------------------------------------------------------
# Worked examples (section 9.8): exact replays with the versions from the tables
# --------------------------------------------------------------------------------------------

def _world_with_note_at_10(rules: Rules, title="Groceries", body="eggs"):
    s = Server(rules)
    s.counter = 10
    s.notes["N"] = ServerNote("N", title, body, False, False,
                              fv={f: 10 for f in FIELDS}, fd={f: "D0" for f in FIELDS}, version=10)
    d1, d2 = Device("D1", "Phone", s, rules), Device("D2", "Tablet", s, rules)
    for d in (d1, d2):
        d._put(LocalNote("N", title, body, False, False, base=10, fbase={f: 10 for f in FIELDS}))
        d.cursor = 10
    return s, d1, d2


def example_1(rules: Rules):
    s, d1, d2 = _world_with_note_at_10(rules)
    d1.edit("N", "title", "Shopping")
    d2.edit("N", "body", "eggs, rice")
    d2.full_sync()
    assert s.notes["N"].version == 11 and s.notes["N"].body == "eggs, rice", "step 2"
    d1.full_sync()
    assert s.notes["N"].version == 12 and s.notes["N"].title == "Shopping", "step 3"
    assert d1.notes["N"].values()["body"] == "eggs, rice", "step 4: body taken from server"
    d2.full_sync()
    for d in (d1, d2):
        n = d.notes["N"]
        assert (n.title, n.body) == ("Shopping", "eggs, rice") and not n.dirty, f"{d.id} result"
    assert list(s.notes) == ["N"], "no copy"


def example_2(rules: Rules):
    s, d1, d2 = _world_with_note_at_10(rules)
    d1.edit("N", "body", "eggs, milk")
    d2.edit("N", "body", "eggs, bread")
    d1.full_sync()
    assert s.notes["N"].version == 11 and s.notes["N"].fd["body"] == "D1", "step 2"
    d2.start_sync()
    d2.deliver()                                   # push reaches the server
    assert s.notes["N"].version == 11 and s.notes["N"].body == "eggs, milk", "step 3: N unchanged"
    assert s.notes["copy1"].version == 12 and s.notes["copy1"].body == "eggs, bread", "step 3: copy"
    resp = d2.inflight[1][0]
    assert resp["status"] == CONFLICT_COPY and resp["note"]["body"] == "eggs, milk", "step 4"
    d2.full_sync()
    d1.full_sync()
    for d in (d1, d2):
        assert d.notes["N"].body == "eggs, milk" and d.notes["copy1"].body == "eggs, bread", d.id


def example_3(rules: Rules):
    s, d1, _ = _world_with_note_at_10(rules, body="a")
    d1.edit("N", "body", "ab")
    d1.start_sync()
    d1.deliver()                                   # server applies
    assert s.notes["N"].version == 11 and s.notes["N"].fd["body"] == "D1", "step 1"
    d1.lose()                                      # response lost
    assert d1.notes["N"].base == 10 and "body" in d1.notes["N"].dirty, "step 2"
    d1.edit("N", "body", "abc")
    d1.start_sync()
    d1.deliver()
    r = d1.inflight[1][0]
    assert r["status"] == APPLIED and s.notes["N"].version == 12, "step 3: not concurrent"
    d1.full_sync()
    assert d1.notes["N"].body == "abc" and len(s.notes) == 1, "result: no conflicted copy"


EXAMPLES = [("Example 1: different fields merge cleanly", example_1),
            ("Example 2: same field, conflicted copy", example_2),
            ("Example 3: lost response, no false conflict", example_3)]


def run_examples(rules: Rules) -> list[str]:
    failures = []
    for name, fn in EXAMPLES:
        try:
            fn(rules)
        except AssertionError as e:
            failures.append(f"{name}: {e}")
    return failures


def rejected_unit_checks(rules: Rules) -> list[str]:
    """REJECTED never occurs in random runs, so check its handling directly (sections 8.4, 9.7)."""
    fails = []
    s = Server(rules, title_max=5)
    d = Device("D1", "Phone", s, rules)
    d.create("n1", "toolongtitle")
    d.full_sync()
    n = d.notes["n1"]
    if not (n.sync_error and n.dirty and "n1" not in s.notes):
        fails.append("REJECTED: note should keep its dirty bits and get a sync_error")
    pushed = []
    s.on_conflict = None
    orig = s.push
    s.push = lambda dev, name, ch: (pushed.extend(c.id for c in ch), orig(dev, name, ch))[1]
    d.full_sync()
    if "n1" in pushed:
        fails.append("REJECTED: a note with sync_error must be excluded from push batches")
    d.edit("n1", "title", "ok")
    d.full_sync()
    if d.notes["n1"].sync_error or "n1" not in s.notes:
        fails.append("REJECTED: editing the note should clear the error and let it sync")
    return fails


# --------------------------------------------------------------------------------------------
# Randomized runs
# --------------------------------------------------------------------------------------------

OP_KINDS = (["create"] * 2 + ["title"] * 3 + ["body"] * 5 + ["pin"] * 2 + ["trash"] + ["restore"]
            + ["sync"] * 4 + ["deliver"] * 9 + ["lose"])


def generate_ops(rng: random.Random, n_ops: int, n_devices: int) -> list[tuple]:
    return [(i, rng.choice(OP_KINDS), rng.randrange(n_devices), rng.randrange(1 << 30))
            for i in range(n_ops)]


@dataclass
class Outcome:
    violation: str | None      # kind: message
    kind: str | None
    copies: int
    redundant_copies: int
    false_conflicts: int


def simulate(ops: list[tuple], rules: Rules, n_devices: int = 3, log=None) -> Outcome:
    server = Server(rules)
    devices = [Device(f"D{i + 1}", f"Device{i + 1}", server, rules) for i in range(n_devices)]
    by_id = {d.id: d for d in devices}
    typed: list[tuple[str, str]] = []           # (token, "title"|"body")
    stats = {"false": [], "redundant": 0}

    def on_conflict(dev_id, change, cur, m):
        held = by_id[dev_id].held
        for f in m.conflict_fields:
            server_value = getattr(cur, f)
            if server_value in held.get((cur.id, f), ()):
                stats["false"].append(f"{dev_id} got a conflicted copy for {cur.id}.{f} although "
                                      f"it had already held the server's value {server_value!r}")
            if change.sent[f] in server_value:
                stats["redundant"] += 1
    server.on_conflict = on_conflict
    if log:
        server.log = log
        for d in devices:
            d.log = log

    last_counter = 0
    for seq, kind, di, k in ops:
        d = devices[di]
        token = f"<{d.id}#{seq}>"
        active = [n for n in d.notes.values() if not n.trashed]
        trashed = [n for n in d.notes.values() if n.trashed]
        if kind == "create":
            d.create(f"{d.id}-n{seq}", token)
            typed.append((token, "title"))
            if log:
                log(f"{d.id} creates {d.id}-n{seq} title={token}")
        elif kind in ("title", "body") and active:
            n = active[k % len(active)]
            d.edit(n.id, kind, (getattr(n, kind) + " " + token).strip())
            typed.append((token, kind))
            if log:
                log(f"{d.id} edits {n.id}.{kind} += {token}")
        elif kind == "pin" and active:
            n = active[k % len(active)]
            d.edit(n.id, "pinned", not n.pinned)
            if log:
                log(f"{d.id} sets {n.id}.pinned = {not n.pinned}")
        elif kind == "trash" and active:
            n = active[k % len(active)]
            d.edit(n.id, "trashed", True)
            if log:
                log(f"{d.id} trashes {n.id}")
        elif kind == "restore" and trashed:
            n = trashed[k % len(trashed)]
            d.edit(n.id, "trashed", False)
            if log:
                log(f"{d.id} restores {n.id}")
        elif kind == "sync":
            d.start_sync()
        elif kind == "deliver":
            d.deliver()
        elif kind == "lose":
            d.lose()
        if server.counter < last_counter:
            return Outcome("server: counter went backwards", "server", 0, 0, 0)
        last_counter = server.counter

    # Drain: finish in-flight syncs, then sync every device until a whole round changes nothing.
    if log:
        log("--- drain ---")
    for d in devices:
        while d.inflight is not None:
            d.deliver()
    settled = False
    for _ in range(12):
        before = server.counter
        for d in devices:
            d.full_sync()
        if server.counter == before and not any(n.dirty for d in devices for n in d.notes.values()):
            settled = True
            break

    def result(violation, vkind):
        return Outcome(violation, vkind, server.copies, stats["redundant"], len(stats["false"]))

    if stats["false"]:
        return result("false conflict: " + stats["false"][0], "false_conflict")
    versions = [n.version for n in server.notes.values()]
    if len(versions) != len(set(versions)):
        return result("server: two rows share a server_version", "server")
    if not settled:
        return result("did not settle: devices kept pushing changes", "settle")
    expected = {nid: n.values() for nid, n in server.notes.items()}
    for d in devices:
        got = {nid: n.values() for nid, n in d.notes.items()}
        if got != expected:
            diff = sorted(set(got) ^ set(expected)) or [
                nid for nid in expected if got.get(nid) != expected[nid]]
            return result(f"no convergence: {d.id} differs from the server on {diff[:3]}",
                          "converge")
        if any(n.dirty or n.sync_error for n in d.notes.values()):
            return result(f"no convergence: {d.id} still has dirty notes", "converge")
    finals = {f: " ".join(getattr(n, f) for n in server.notes.values()) for f in TEXT}
    for token, f in typed:
        if token not in finals[f]:
            return result(f"lost text: {token} typed into a {f} is gone from every note", "lost_text")
    return result(None, None)


def shrink(ops: list[tuple], rules: Rules, kind: str, n_devices: int) -> list[tuple]:
    """Remove operations while the same kind of violation still happens."""
    def fails(candidate):
        return simulate(candidate, rules, n_devices).kind == kind
    chunk = max(1, len(ops) // 2)
    while chunk >= 1:
        i, progress = 0, False
        while i < len(ops):
            candidate = ops[:i] + ops[i + chunk:]
            if candidate and fails(candidate):
                ops, progress = candidate, True
            else:
                i += chunk
        if not progress:
            chunk //= 2
    return ops


def randomized(rules: Rules, runs: int, n_ops: int, seed: int, n_devices: int = 3,
               quiet: bool = False) -> dict:
    t0 = time.perf_counter()
    first = None
    first_by_kind: dict[str, tuple] = {}
    counts: dict[str, int] = {}
    copies = redundant = 0
    for r in range(runs):
        run_seed = seed + r
        ops = generate_ops(random.Random(run_seed), n_ops, n_devices)
        out = simulate(ops, rules, n_devices)
        copies += out.copies
        redundant += out.redundant_copies
        if out.kind:
            counts[out.kind] = counts.get(out.kind, 0) + 1
            if first is None:
                first = (run_seed, ops, out)
            first_by_kind.setdefault(out.kind, (run_seed, ops, out))
    elapsed = time.perf_counter() - t0
    report = {"rules": rules.name, "runs": runs, "ops": n_ops, "failed_runs": sum(counts.values()),
              "by_kind": counts, "copies": copies, "redundant_copies": redundant,
              "seconds": round(elapsed, 1), "first": first, "first_by_kind": first_by_kind}
    if not quiet:
        print(f"[{rules.name}] {runs} runs x {n_ops} ops, {n_devices} devices: "
              f"{report['failed_runs']} failed {counts or ''} | conflicted copies {copies}, "
              f"redundant {redundant} | {elapsed:.1f}s")
    return report


def print_trace(ops: list[tuple], rules: Rules, n_devices: int = 3):
    lines = []
    out = simulate(ops, rules, n_devices, log=lines.append)
    for line in lines:
        print("   " + line)
    print(f"   => {out.violation or 'ok'}")


# --------------------------------------------------------------------------------------------
# Self-check: the harness must catch deliberately broken rules, or a green run means nothing
# --------------------------------------------------------------------------------------------

def self_check(n_runs: int = 150) -> list[str]:
    """Plant bugs into the contract rules; each must be caught."""
    from dataclasses import replace
    fails = []
    no_writer = replace(CONTRACT, name="mutant: no last-writer check", last_writer_check=False)
    if not any("Example 3" in f for f in run_examples(no_writer)):
        fails.append("self-check: Example 3 passed without the last-writer check")
    rep = randomized(no_writer, n_runs, 200, seed=10_000, quiet=True)
    if not rep["by_kind"].get("false_conflict"):
        fails.append("self-check: removing the last-writer check was not detected as false conflicts")
    advance = replace(CONTRACT, name="mutant: dirty field bases advance", advance_dirty_field_bases=True)
    rep = randomized(advance, n_runs, 200, seed=20_000, quiet=True)
    if not rep["by_kind"].get("lost_text"):
        fails.append("self-check: advancing a dirty field's base was not detected as lost text")
    return fails


# --------------------------------------------------------------------------------------------

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--rules", default="contract", choices=sorted(RULES))
    ap.add_argument("--runs", type=int, default=1000)
    ap.add_argument("--ops", type=int, default=300)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--devices", type=int, default=3)
    ap.add_argument("--trace", type=int, metavar="SEED", help="print the events of one run")
    ap.add_argument("--no-self-check", action="store_true")
    a = ap.parse_args(argv)
    rules = RULES[a.rules]

    if a.trace is not None:
        print_trace(generate_ops(random.Random(a.trace), a.ops, a.devices), rules, a.devices)
        return 0

    ok = True
    print(f"Worked examples (section 9.8) with rules '{rules.name}':")
    ex = run_examples(rules)
    for name, _ in EXAMPLES:
        bad = [f for f in ex if f.startswith(name)]
        print(f"  {'FAIL' if bad else 'ok  '} {name}" + (f" -- {bad[0]}" if bad else ""))
    ok &= not ex
    rej = rejected_unit_checks(rules)
    print(f"  {'FAIL' if rej else 'ok  '} REJECTED handling" + (f" -- {rej}" if rej else ""))
    ok &= not rej

    if not a.no_self_check:
        sc = self_check()
        print(f"Self-check (harness catches planted bugs): {'FAIL ' + str(sc) if sc else 'ok'}")
        ok &= not sc

    rep = randomized(rules, a.runs, a.ops, a.seed, a.devices)
    if rep["failed_runs"]:
        ok = False
        for kind, (run_seed, ops, out) in rep["first_by_kind"].items():
            small = shrink(ops, rules, kind, a.devices)
            print()
            print(f"First '{kind}' failure: seed {run_seed}: {out.violation}")
            print(f"Shrunk from {len(ops)} to {len(small)} operations:")
            print_trace(small, rules, a.devices)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
