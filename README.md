# SyncNotes

A markdown notes app for Android that works fully offline and keeps notes in sync
across devices **without ever losing an edit**.

> **Status:** Phase 0 (Foundation) in progress. See the progress tracker in
> [`ARCHITECTURE.md`](ARCHITECTURE.md#11-progress-tracker).

## Why this project

Most notes apps either need the network or quietly lose text when two devices edit
the same note. SyncNotes is built around a per-field, version-based sync protocol
that merges concurrent edits and, when a true conflict is unavoidable, keeps *both*
versions instead of dropping one. The design is documented in full in
[`ARCHITECTURE.md`](ARCHITECTURE.md) — start there.

## Tech

- **App:** Kotlin, Jetpack Compose (Material 3), Navigation 3, Room 3, Hilt,
  WorkManager, Retrofit/OkHttp, kotlinx.serialization.
- **Backend** (Phase 2): Java 21, Spring Boot 4.1, PostgreSQL, deployed on Fly.io + Neon.

## Repository layout

```
app/       Android app (this is the Android Studio project — open the repo root)
server/    Spring Boot API (added in Phase 2)
docs/      Executable sync model, testing notes, runnable API requests
```

See [`ARCHITECTURE.md` §5](ARCHITECTURE.md#5-repository-and-code-structure) for the full map.

## Build

```bash
./gradlew assembleDebug        # build the debug APK
./gradlew testDebugUnitTest    # run JVM unit tests
./gradlew lintDebug            # lint
```

Requires JDK 21+ (built with JDK 25). Open the repository root in Android Studio.

## License

TBD.
