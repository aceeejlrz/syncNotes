# Learnings

A running log of anything surprising or hard-won. This becomes README material and
interview stories (see [`ARCHITECTURE.md` §1.2](ARCHITECTURE.md#12-routine-for-every-work-session)).

Format: newest first. Each entry is a short heading, what happened, and why it matters.

---

## Phase 2 — Backend

### Proving the row lock: a lock timeout, not a sleep
To show `SELECT ... FOR UPDATE` really serializes pushes per user, one transaction holds Alice's
row while a second sets `SET LOCAL lock_timeout = '300ms'` and tries to lock it: PostgreSQL cancels
it with SQLSTATE `55P03` (lock_not_available). Spring leaves that as `UncategorizedSQLException`
rather than `PessimisticLockingFailureException`, so assert the SQL state. Locking Bob's row at
the same time succeeds. Removing `FOR UPDATE` makes the test fail. `lockAndGetSyncVersion` also
refuses to run outside a transaction, where the lock would release immediately.

### Local port 8090, and 300 emoji is 300 characters
8080 was already taken on the dev machine (Jenkins), so the `local` profile serves on 8090
(section 14.1). A repository test confirms PostgreSQL's `char_length` counts code points: a title
of 300 emoji (600 UTF-16 units) is accepted and 301 is rejected, matching `NoteLimits.length`.

### Golden vectors keep the Java merge engine and the model in lockstep
`docs/sync_protocol_model.py --export-vectors` dumps 1,017 real merge inputs/outputs (random runs
plus hand-picked edge cases, expected outputs always from the model) to
`server/src/test/resources/merge-vectors.json`; `MergeEngineVectorTest` replays them all. Planting
the "no last-writer check" bug in Java fails 90 of them, so the test has teeth. `--check-vectors`
catches a model change that wasn't re-exported. Gotcha: the first export wasn't reproducible —
field order inside `sent` came from iterating a Python set (hash-randomized per process); sorted
keys fixed it.

### A conflicted copy's title can overflow the column
M4 appends `" (conflicted copy from <device>)"` (up to ~84 characters) to a title that may already
be 300. The insert would violate `notes_title_len`, roll back the whole push, and the app would
retry it forever. The copy's title is now shortened by whole code points (PostgreSQL's
`char_length` counts code points, Java's `String.length` counts UTF-16 units) — in the engine,
the model and §9.4. The randomized model never checked lengths, so it couldn't have found this.

### Spring Boot 4.1 generates Jackson 3, and Flyway needs its PostgreSQL module
Initializr's Boot 4.1.2 project uses Jackson 3 (`tools.jackson.databind`, `asString()` instead of
`asText()`) and already adds `flyway-database-postgresql`, which recent Flyway versions need
separately. Built with JDK 25 against `<java.version>21</java.version>` (`--release 21`).

## Before Phase 2 — sync protocol model

### The executable model found two holes the design review missed
Built `docs/sync_protocol_model.py` from §9 as written: the server merge rules, the app's apply
rules, and a scheduler that interleaves edits, syncs, lost or stale messages. The worked examples
all passed, which looked reassuring, but 14% of randomized runs failed. Rule A (always advance
the base after a push) silently overwrites another device's text when a field was edited during
the push, and rule B (partial take on a dirty pull) creates false conflicts. Shrinking each
failure to about 20–30 operations made the cause readable. Lesson: hand-traced examples check
the paths you thought of; randomized interleavings find the ones you didn't.
Fixed with per-field base versions (ADR-018): each field remembers the version it was last seen
at, so a field edited during a push keeps its old base (a real conflict stays detectable), and a
freshly received field moves its base up (no false conflict). Cheap to change now because
neither the server (Phase 2) nor the sync engine (Phase 4) exists yet.

### Prove the harness can fail before trusting it
A green randomized run means nothing unless the checks can fire. The model's self-check removes
the last-writer check (it must report false conflicts and fail Example 3) and makes a dirty pull
advance the base (it must report lost text). The same mutation was applied to each candidate fix.

## Phase 1 — Offline notes app

### `advanceUntilIdle()` ignores work left only in `backgroundScope`
To test the editor's flush-on-close, the VM gets `backgroundScope` as its app scope and lives in
a real `ViewModelStore`; `store.clear()` runs `onCleared` exactly as a nav pop does. The flush
then never ran: `advanceUntilIdle()` stops when only background tasks remain. Follow it with
`runCurrent()`. That also turned a "nothing was saved" assertion from vacuous into real — a quick
mutation (deleting the trashed guard) confirmed the test now fails when it should.

### ViewModel tests need a repository interface, and a deliberately dumb fake
`NotesRepository` became an interface (`RoomNotesRepository` is bound via `@Binds`) so VM tests
can use `FakeNotesRepository`. The fake records calls and filters only on `trashed`; it doesn't
copy ordering, escaping or dirty bits, which are tested against real Room. List and trash VM
tests run on the plain JVM; the editor needs Robolectric for `TextFieldState`.

### Material 3 icon buttons are 40 dp drawn, 48 dp touchable
`assertWidthIsAtLeast(48.dp)` on an `IconButton` fails at 40 dp: M3 draws the button smaller
and extends the *touch* area to 48 dp via `minimumInteractiveComponentSize`. The guideline is
about touch targets, so check `fetchSemanticsNode().touchBoundsInRoot` instead (find the button
via the merged tree's content description, not the 24 dp icon).

### "Outlined" legacy icons aren't always hollow
`Icons.Outlined.Star` is the same solid star as `Icons.Filled.Star` (class sizes 6189 vs 6151
bytes; a genuinely hollow icon like `FavoriteBorder` adds ~560). Core has no hollow star, so pin
state uses `IconToggleButton` (TalkBack announces on/off) plus a visible "Pinned" label rather
than color alone.

### Dark mode needs the window theme and a Surface, not just the Compose theme
`SyncNotesTheme` follows the system setting, but the window theme was `Theme.Material.Light`,
which shows as a white flash in dark mode. Added `values-night/themes.xml`. Text drawn outside a
Scaffold or Surface uses M3's default content color (black), so the nav host sits inside a
themed `Surface`.

### Edge-to-edge apps must pad for the keyboard themselves
With targetSdk 35+, `adjustResize` no longer shrinks the window, so the editor uses
`.padding(padding).consumeWindowInsets(padding).imePadding()`. A bare `imePadding()` after the
Scaffold padding counts the navigation-bar inset twice.

### Markdown renderer: newest compile-compatible is 0.41.0
`multiplatform-markdown-renderer` tracks Kotlin closely: 0.42+ pull kotlin-stdlib 2.4.x
(metadata the 2.2.10 compiler rejects), 0.41.0 pulls 2.3.21 (metadata 2.3.0, at the readable
boundary). Picked 0.41.0 — newest that compiles, so closest to the Compose 1.10 runtime. The
compile check isn't enough: a `createComposeRule` test renders `Markdown("# hi")` to catch a
runtime `NoSuchMethodError`. Use `assertExists`, not `assertIsDisplayed`, for headless renders.

### Assisted-inject ViewModels via Navigation 3
The editor VM takes its `NoteEditor` key through Hilt assisted injection:
`@HiltViewModel(assistedFactory = Factory::class)` + `@AssistedInject` + `@AssistedFactory`,
resolved in the nav entry with `hiltViewModel<VM, VMF>(creationCallback = { it.create(key) })`
from `androidx.hilt.lifecycle.viewmodel.compose`. Capture the `@Assisted` param into plain
`private val`s instead of annotating a `val` parameter, or K2 warns the annotation will later
apply to the property too.

### Testing autosave (snapshotFlow + Room) under runTest
To assert the 400 ms debounce persists: `Dispatchers.setMain(StandardTestDispatcher)`, bind
Room's query AND transaction executors to that dispatcher (plus `allowMainThreadQueries`, since
the test dispatcher runs on Robolectric's main thread) so `advanceUntilIdle` awaits the DB write,
then edit the `TextFieldState`, call `Snapshot.sendApplyNotifications()` so `snapshotFlow` emits,
and `advanceTimeBy(401)`. Without binding Room's executors the write runs on a real background
thread that virtual time never awaits, and the assertion sees null.

### Robolectric 4.17 runs on JDK 25 — with one JVM flag
Room DAO/repository tests must run on the JVM (CI has no emulator; the CI task is
`testDebugUnitTest`), so they use Robolectric. On JDK 25 the first run failed:
`Failed to interact with raw FileDescriptor internals` →
`IllegalAccessException: ... cannot access class jdk.internal.access.SharedSecrets ...
module java.base does not export jdk.internal.access`. Fix: launch the unit-test JVM with
`--add-exports=java.base/jdk.internal.access=ALL-UNNAMED` (set via `tasks.withType<Test>()`
in `app/build.gradle.kts`). After that, `@RunWith(AndroidJUnit4::class)` +
`@Config(application = Application::class)` + an in-memory Room DB works on the JVM.

### Compose material-icons is frozen at 1.7.8 and not in the 2026 BOM
`androidx.compose.material.icons.*` is no longer a transitive dep of material3, and
`material-icons-core` stopped at 1.7.8 (the BOM doesn't manage it). Pin it explicitly;
its `ImageVector` constants still work on the Compose 1.10 runtime. Avoided the heavy
`material-icons-extended` — used core icons (Add/Search/MoreVert/Star), with a tinted
Star standing in for pin/unpin.

### Navigation 3: `entry {}` is a scope member, not a top-level import
In `entryProvider { entry<Key> { ... } }`, `entry` resolves on the `EntryProviderScope`
receiver, so importing `androidx.navigation3.runtime.entry` fails to compile. The
importable top-level symbols are `entryProvider`, `rememberNavBackStack`,
`rememberSaveableStateHolderNavEntryDecorator`, and `NavDisplay`;
`rememberViewModelStoreNavEntryDecorator` lives in `androidx.lifecycle.viewmodel.navigation3`.

### Room 2.8 data layer differs from the doc's Room 3 snippets
Built on the Room 2.8.5 fallback (ADR-014): `Room.databaseBuilder(ctx, Klass::class.java, name)`
with **no** `setDriver` (keeps the framework SQLite path Robolectric shadows), and
`withTransaction` from `room-ktx` instead of Room 3's `withWriteTransaction`. The Room Gradle
plugin (`androidx.room` 2.8.5) exports the schema to `app/schemas/` and is config-cache compatible.

## Phase 0 — Foundation

### AGP 9 has built-in Kotlin — no `kotlin-android` plugin needed
The project compiles Kotlin + Compose with only `com.android.application` and
`org.jetbrains.kotlin.plugin.compose` applied. Android Gradle Plugin 9.x compiles
Kotlin itself, so the classic `org.jetbrains.kotlin.android` plugin is not applied.
This matters when adding annotation processors (Hilt, Room): the KSP plugin has to
attach to AGP's built-in Kotlin compilation, so watch the first KSP sync closely.

**Resolution (confirmed by build):** adding KSP + Hilt failed at configuration with
`Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin`
— KSP registers its generated sources (`build/generated/ksp/...`) through
`kotlin.sourceSets`, which AGP 9 forbids by default. Fix: set
`android.disallowKotlinSourceSets=false` in `gradle.properties`. After that,
`:app:kspDebugKotlin` and the Hilt tasks run and `assembleDebug` succeeds.
Versions that work together: AGP 9.4.1, Kotlin 2.2.10, KSP `2.2.10-2.0.2`, Hilt 2.60.1.
(Harmless warning: "Kotlin does not yet support 25 JDK target, falling back to JVM_24".)

### A Kotlin-2.4 library won't fit a Kotlin-2.2.10 project
Adding `com.mikepenz:multiplatform-markdown-renderer-m3:0.45.0` broke the build:
`Class 'kotlin.Unit' was compiled with an incompatible version of Kotlin` — the renderer
constrains `kotlin-stdlib` to 2.4.10, and the 2.2.10 compiler refuses metadata newer than 2.3.0.
`gradlew :app:dependencyInsight --dependency kotlin-stdlib` named the single culprit. Fix options:
match the compiler (bump Kotlin + KSP + compose plugin together) or drop/downgrade the library.
For Phase 0 the renderer was deferred, keeping the app on a coherent Kotlin 2.2.10 stack.

### Room 3 isn't on Google Maven yet
`androidx.room3:*` all 404; `androidx.room` tops out at 2.8.5. Using the Room 2.8.x fallback
(ADR-014). Watch for Room 3 publishing before the data layer is built in Phase 1.
