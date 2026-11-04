# TODO

Scratch space for the next change and open questions
(see [`ARCHITECTURE.md` §1.2](ARCHITECTURE.md#12-routine-for-every-work-session)).

## Now (Phase 2)

- [x] **Docker verified.** 2.2 (compose PostgreSQL + local profile on 8090) and 2.3 (Flyway
  applies `V1__init.sql`) pass; 2.5 repository and row-lock tests run against PostgreSQL 17.
  Run the server suite with `cd server && ./mvnw test` (JAVA_HOME = JDK 25, Docker running).
- [x] **2.6 obligations to MergeEngine.** Done: `NoteLimits.truncate`, code-point checks, and
  `SyncServiceTest` covers both.
- [ ] **Decide before Phase 3: a lost refresh response signs the user out.** Rotation revokes the
  old token before the response reaches the phone. If that response is lost (network drop), the
  phone only holds a revoked token and the next refresh is a 401: session expired, sign in again.
  No data is lost (notes stay and sync afterwards), but it could surprise on flaky networks. The
  usual fix is a short grace period for the just-replaced token. Not in the blueprint; see section 10.2.
- [ ] **Decide: the local dev signing key is committed** in `application-local.yml` (throwaway, but
  section 10.5 says nothing secret in git). Keep it, or move it to the gitignored `.env`.
- [ ] **2.10 deploy needs `JWT_SECRET`** set as a Fly.io secret (`openssl rand -base64 32`).
- [ ] **2.10: choose the machine size.** 512 MB is measured to handle everything tested (section
  14.4). 256 MB needs the lighter JVM settings and can't serve the worst-case pull. Also set
  `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5` in `fly.toml` (section 14.4).
- [ ] **Bound pull pages by size, not only by count.** One pull holds its whole page in memory, so
  the worst case grows with `limit` (200 huge notes: 80 MB response, 424 MB peak). `limit=500` of
  huge notes wasn't tested and would likely exhaust a 512 MB machine, and with exit-on-OOM that
  restarts it. It needs an account with hundreds of near-limit notes. Fix: end a page early once it
  passes a byte budget; `hasMore` already tells the app to continue, so the API doesn't change.
- [ ] **2.10: check `Fly-Client-IP` can't be faked.** Fly's docs don't say the proxy overwrites a
  client-supplied value. On the live server, send 11 logins with a different made-up `Fly-Client-IP`
  each: the 11th must be a 429. If not, the limit can be bypassed: switch `ClientIp` to the
  second-from-right `X-Forwarded-For` entry, which the proxy itself appended.
- [ ] **3.5/3.8: a failed refresh isn't always an expired session.** Only a 401 from `/auth/refresh`
  ends the session; 429, 5xx and network errors must keep it (section 10.4). Check what the sync does
  when the authenticator returns null (section 9.10 `e.code() == 401 -> AuthFailed`). Add a
  MockWebServer test for a 429 on refresh.
- [ ] **2.11: confirm the first `server` CI run is green**, then tick the step. `server.yml` is
  written and linted, but only GitHub can run it, and the repo is private so it has to be read on
  the Actions page. It also enforces R3 (the model and `--check-vectors`).

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
- [x] **§9 sync model.** Built; it found two holes in the v1.4 rules (lost text, false conflicts).
  Fix B adopted: per-field base versions (ADR-018); the blueprint was updated and the model passes.
  Phase 4 adds the base columns to the app (schema v2, step 4.1).
- [ ] Decide the Fly.io app name (currently the `syncnotes-api` placeholder in the doc).
- [ ] License for the repo (README says TBD).
