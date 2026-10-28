# TODO

Scratch space for the next change and open questions
(see [`ARCHITECTURE.md` §1.2](ARCHITECTURE.md#12-routine-for-every-work-session)).

## Now (Phase 0)

- [ ] Confirm the app builds **and runs on a device/emulator** with Hilt (exit criterion).
- [ ] Push to GitHub and confirm the `android` CI workflow goes green (step 0.6).

## Deferred from Phase 0 → Phase 1

- [ ] **Markdown renderer.** `com.mikepenz:multiplatform-markdown-renderer-m3:0.45.0` pulls
  `kotlin-stdlib:2.4.10`, which the Kotlin 2.2.10 compiler cannot read. Left out of the build
  (catalog entry kept; commented in `app/build.gradle.kts`). When wiring the editor preview
  (§6.5, step 1.6): bump the project Kotlin — with matching KSP + compose plugin — or pick a
  renderer version built against Kotlin ≤2.2.
- [x] **Room 3 vs 2.8.** `androidx.room3` is unpublished, so the build uses Room 2.8.5
  (ADR-014 fallback). Done in step 1.1: §7.5 reconciled, data layer on Room 2.8
  (`withTransaction`, no `setDriver`). Revisit if Room 3 ships.

## Open questions from the §7 / §9 read-through (step 0.7)

Read §7 (local data model) fully; read §9.1–§9.4 (vocabulary + merge rules M1–M10)
closely. **Still to read closely: §9.5–§9.11** (push/pull service, ChangeRules apply
rules, worked examples, the editor race). Questions so far:

- **§7.1 indices.** The `notes` table indexes `("trashed","pinned","updated_at")` and
  `("dirty_fields")`. Confirm these actually serve `observeActive` (which also filters
  on `title/body LIKE`) and `dirtyBatch` once real data exists — revisit at 2,000 notes (§2.3).
- **§7.2 dirty-bit clearing.** A bit is cleared only when "the local value still equals
  what was sent." Nail down exactly how the push-apply step compares (field-by-field)
  when an edit landed *while the push was in flight* — this is the fork case (§9.7/§12.2).
- **§9.3 row lock.** `SELECT ... FOR UPDATE` on the user row serializes pushes per user.
  Confirm the `JdbcClient` transaction boundary + isolation level actually holds the lock
  for the whole merge, and measure push latency under it.
- **§9.4 M4 conflicted copy.** The copy's title is `"<title> (conflicted copy from <device>)"`.
  What happens on a *second* conflict for the same note — does it stack another suffix?
  Decide before Phase 4.
- **§9 sync model — DECIDE A FIX.** `docs/sync_protocol_model.py` is built and found two holes
  in the §9.7 app rules (lost text, false conflicts; 141/1,000 runs fail). Choose Fix A
  (snapshot, no protocol change) or Fix B (per-field bases, protocol change) — see §9.12 —
  then update §7.1/§8/§9.7 to match and make the model's default rules pass, before Phase 2.
- [ ] Decide the Fly.io app name (currently the `syncnotes-api` placeholder in the doc).
- [ ] License for the repo (README says TBD).
