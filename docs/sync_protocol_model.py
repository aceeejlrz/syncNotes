#!/usr/bin/env python3
"""Executable model of the SyncNotes sync protocol (ARCHITECTURE.md section 9).

STATUS: STUB (Phase 0). This file establishes the shape of the model and the rule
scaffold. It is NOT yet the validated reference implementation described in
ARCHITECTURE.md section 9 ("passed 1,000 runs of 300 operations").

Per rule R3, this model is the third leg of the merge contract, alongside the
server's MergeEngine (Java) and the app's ChangeRules (Kotlin). Its job is to:

  1. Replay the worked examples in section 9.8 exactly.
  2. Run randomized tests with >=3 devices, lost push responses, and edits made
     while a push is in flight, asserting convergence and no lost text.

The validated implementation is due just before Phase 2 (the backend phase), which
is where the doc first consumes it (section 12.4: "port its device logic to Java").
It is intentionally deferred here so it can be built against a full, close reading
of sections 9.5-9.11 rather than half-built during Foundation.

Run:  python docs/sync_protocol_model.py
"""

from __future__ import annotations

import sys
from dataclasses import dataclass, field

# --- Vocabulary (section 9.1) -------------------------------------------------
# change counter : users.sync_version; +1 for every note row written for a user.
# server_version : the counter value when a note row last changed. Unique per user.
# field version  : per field, the counter value of its last write + the writer device.
# base version   : the note's server_version the last time a device fully knew it.
# concurrent(f)  : server field version > base AND the field's last writer != device.

FIELDS = ("title", "body", "pinned", "trashed")


@dataclass
class ServerNote:
    """A note row as the server stores it (section 8.1)."""
    id: str
    title: str = ""
    body: str = ""
    pinned: bool = False
    trashed: bool = False
    # per-field (version, device) of the last write
    field_version: dict[str, int] = field(default_factory=lambda: {f: 0 for f in FIELDS})
    field_device: dict[str, str] = field(default_factory=lambda: {f: "" for f in FIELDS})
    server_version: int = 0


@dataclass
class Change:
    """One incoming change from a device. A field set to None means 'not sent'."""
    id: str
    base_version: int
    device: str
    title: str | None = None
    body: str | None = None
    pinned: bool | None = None
    trashed: bool | None = None

    def sent_fields(self) -> dict[str, object]:
        return {f: getattr(self, f) for f in FIELDS if getattr(self, f) is not None}


@dataclass
class MergeResult:
    status: str                       # APPLIED | NOOP | CONFLICT_COPY | REJECTED
    note: ServerNote | None = None
    conflict_copy: ServerNote | None = None
    error: str | None = None


# --- Merge rules M1-M10 (section 9.4) -----------------------------------------
# M1  note absent           -> create from sent fields
# M2  field doesn't differ  -> no-op for that field (makes retries harmless)
# M3  title/body differs, not concurrent      -> apply
# M4  title/body differs AND concurrent        -> keep server; create conflicted copy
# M5  pinned differs, not concurrent           -> apply
# M6  pinned differs AND concurrent            -> keep server (first write wins)
# M7  restore (trashed -> false)               -> always apply
# M8  trash (trashed -> true) w/ later edit    -> ignore trash (edits beat deletes)
# M9  trash otherwise                          -> apply
# M10 title/body applied while server-trashed  -> also restore (edits beat deletes)

def merge(server: ServerNote | None, change: Change, counter: int) -> MergeResult:
    """Apply one change to the server's note. Returns the canonical result.

    TODO(pre-Phase-2): implement M1-M10 against a close reading of sections
    9.4-9.8, then assert this replays every worked example in section 9.8.
    """
    raise NotImplementedError("sync model is a Phase 0 stub; see module docstring")


def replay_worked_examples() -> None:
    """TODO: replay the three worked examples from section 9.8 and assert results."""
    raise NotImplementedError


def randomized_convergence(seed: int, devices: int, ops: int) -> None:
    """TODO: N devices, random ops, lost responses; assert convergence + no lost text."""
    raise NotImplementedError


def main() -> int:
    print(__doc__.splitlines()[0])
    print("STATUS: STUB (Phase 0) - validated model deferred to pre-Phase-2.")
    print(f"Scaffold ready: {len(FIELDS)} fields, rules M1-M10 documented, dataclasses defined.")
    print("Next: implement merge()/replay_worked_examples()/randomized_convergence().")
    return 0


if __name__ == "__main__":
    sys.exit(main())
