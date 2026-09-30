# Learnings

A running log of anything surprising or hard-won. This becomes README material and
interview stories (see [`ARCHITECTURE.md` §1.2](ARCHITECTURE.md#12-routine-for-every-work-session)).

Format: newest first. Each entry is a short heading, what happened, and why it matters.

---

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
