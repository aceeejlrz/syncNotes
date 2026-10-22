# Learnings

A running log of anything surprising or hard-won. This becomes README material and
interview stories (see [`ARCHITECTURE.md` §1.2](ARCHITECTURE.md#12-routine-for-every-work-session)).

Format: newest first. Each entry is a short heading, what happened, and why it matters.

---

## Phase 1 — Offline notes app

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
