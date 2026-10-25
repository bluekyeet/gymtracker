# AGENTS.md

Instructions for coding agents working on https://github.com/bluekyeet/gymtracker.

Read `DESIGN.md` first. It is the source of truth for what to build. This file says how to work, and what the owner actually wants the product to feel like. If the two disagree or `DESIGN.md` is unclear on something that affects correctness, stop and ask. Do not invent requirements.

## What this product is

**PowerTrack** — a personal gym tracker for one person (the owner). Android phone app, Wear OS app, website, Kotlin backend. Offline first, synced through one API. Sideloaded only. No Apple platforms.

Names that are already fixed — do not "correct" them:

| Thing | Value |
| --- | --- |
| Product name in the UI | PowerTrack |
| Phone application id | `com.bluekyeet.powertrack` |
| Watch application id | `com.bluekyeet.powertrack.wear` |
| Gradle/Kotlin package roots | `com.gymtracker.*` (`shared`, `app`, `wear`, `data`, `server`) |
| Repo | `github.com/bluekyeet/gymtracker` |

The package roots say `gymtracker`, the app says `PowerTrack`. That mismatch is intentional. Do not rename either side.

## Repository layout

```text
gymtracker/
├── AGENTS.md                # how to work, and what the UI should feel like (this file)
├── DESIGN.md                # product + engineering design, source of truth for behavior
├── README.md
├── .env.example
│
├── shared/                  # Pure Kotlin/JVM. No Android dependencies.
│   └── src/{main,test}/kotlin/com/gymtracker/shared/
│       ├── domain/          # models, validation, calculations
│       ├── api/             # request and response classes
│       └── sync/            # operation types, conflict rules
│
├── android/                 # One Gradle project
│   ├── settings.gradle.kts  # includes :app, :wear, :data; includeBuild("../shared")
│   ├── app/                 # :app, phone UI
│   ├── wear/                # :wear, watch UI, own application id
│   ├── data/                # :data, Room schema, outbox, sync worker, API client
│   └── build-logic/         # optional convention plugins
│
├── backend/                 # Kotlin/Ktor, includeBuild("../shared")
│   └── src/{main,test}/kotlin/com/gymtracker/server/
│
├── web/                     # SvelteKit, TypeScript
│
├── infra/                   # compose.yaml, compose.*.yaml, scripts/
│
└── docs/
    ├── openapi.yaml         # the API contract
    ├── BUILD_AND_TEST.md    # exact commands, incl. emulator verification
    ├── DEPLOYMENT.md        # Compose deployment, networking, backups
    ├── AUTHENTICATION.md    # enrollment key, device credentials, web auth
    ├── API.md               # API endpoint details
    ├── SYNC_PROTOCOL.md     # server_id, commit-safe cursor, conflict rules
    ├── WEBSERVER.md         # server architecture specification
    ├── HOME_SCREEN.md       # Today screen spec
    └── *.md                 # short pointers into DESIGN.md
```

Do not create empty placeholder files. Create a component's files when its milestone begins.

## Architecture rules

- **`shared/` stays pure Kotlin/JVM.** Domain models, validation, calculations, API classes and sync operation types live here. Both the Android build and the backend use it. It must not import Android, Ktor server or database code.
- **`:data` owns local persistence and sync for both Android apps.** The Room schema, outbox, sync worker and API client exist once, in `:data`. `:app` and `:wear` depend on it. Do not copy this code into either app module.
- **UI modules contain UI only.** Composables display state and forward actions. No SQL, no sync logic, no HTTP calls in composables.
- **The backend never depends on Android code.** It shares only `shared/`.
- **The API contract is `docs/openapi.yaml`.** Backend responses and the shared Kotlin classes must match it, and contract tests must verify that. The website's TypeScript API types are generated from it.
- **Only the backend talks to PostgreSQL.** Clients never do.
- **The cursor is opaque to clients.** Clients store and return it unchanged.
- **Keep the wear module standalone.** It has its own application id, manifest and APK, with `com.google.android.wearable.standalone` set to `true`. It must log and sync with no phone running.

## How the app should look and behave

This is the owner's taste. Follow it on every screen you touch; do not invent a different style.

**Structure**

- Material 3, dark mode, edge-to-edge. Bottom `NavigationBar`, five tabs: **Today, Splits, Exercises, History, Progress**.
- Top-level tabs never show a Back button. Sub-screens (split, day, workout, exercise detail) do.
- Screens scroll. Nothing important is cut off at the bottom of the display.
- Every list has a real empty state in plain language ("No workouts yet. Start one from Today."), never a blank screen.

**Type and layout**

- The one important fact per screen gets big type: today's day name, the workout name, the weight being logged.
- Primary actions are big buttons (`START WORKOUT` is 64dp tall and spans the width). Nothing important is a small grey icon.
- Cards with 12–16dp padding. Generous spacing; no wall of text.
- Numbers are rounded for display: `79.2 kg`, never `79.16666666666666 kg`.

**Grouping and scale**

- Group lists by category instead of one long flat list: the exercise library is sectioned by muscle group (CHEST, BACK, …) with filter chips, and so is any "pick an exercise" menu.
- When a list will grow past ~8 entries, do not render one chip/button per entry. Use a searchable dropdown (`ExposedDropdownMenuBox` + filter field) so it stays usable with hundreds of items.
- Drill in rather than cram: an exercise opens its own screen (stats, history chart, table of every workout), a day opens its own screen.

**Charts**

- Use Vico (`com.patrykandpatrick.vico:compose-m3`). One chart per screen unless asked.
- Never let the line sit flat against a zero-based Y axis — pad min/max by ~20% of the range with `AxisValueOverrider.fixed`.
- X axis shows short dates (`4 Oct`), not raw indices.
- Charts have a selectable time period: **1M / 3M / 6M / 1Y / All**, default All, and the period filters the stats above the chart too.
- A chart with fewer than 2 points shows a sentence explaining why, not an empty box.

**Editing and safety**

- Destructive actions (delete split, delete day, delete exercise, remove from day) always ask first, with the confirm button in `MaterialTheme.colorScheme.error`.
- Reordering uses explicit up/down arrow buttons, not drag-only. Arrows are disabled at the ends, and the sort keys are renumbered densely on every move.
- Anything a user typed is kept: never discard input to make a screen simpler. Validate instead (see `shared/.../Validation.kt`) and show error styling inline.
- One workout at a time. Starting another while one is in progress shows a "Finish X first?" dialog offering Finish & start / Resume / Cancel — never a silent discard of the in-progress workout.
- Days carry a name, a set of weekdays (a day can repeat across the week), and per-exercise recommended sets × reps. Starting a workout pre-fills those sets so logging starts ready.

**Naming in the UI**

- Sentence case for labels ("Best estimated 1RM"), uppercase for small section headers ("EVERY WORKOUT").
- Days and splits are named by the user ("Push Day", "Legs"). Never show internal ids or UUIDs anywhere in the UI.

## Non-negotiable behavior

These come from `DESIGN.md`. Follow them exactly, and add tests for each.

1. **Local write first.** Every change is committed to the local database and the outbox in one transaction before any network call.
2. **Idempotent sync.** Every push operation has an `op_id`. The server stores results by `op_id`. A retried push never duplicates data.
3. **Commit-safe cursor.** Each push transaction increments `users.last_seq` on the user's row, which serializes that user's transactions. Never use a bare PostgreSQL sequence as a pull cursor.
4. **Cursor advances after apply.** A client stores a new cursor only after the whole page of changes is applied locally.
5. **Delete wins.** An edit to a deleted record is rejected. A stale device can never revive a deleted record.
6. **History is a snapshot.** Starting a workout copies the day's exercises, with names and order, into `workout_exercises`. Plan edits never change past workouts.
7. **Ownership everywhere.** The user comes from the token, never from the request body. Check ownership of every record and relation a request names.
8. **Kilograms in storage.** Convert only at the display layer.
9. **No public registration.** Account creation and password reset are server commands.
10. **Secrets stay out of the repo.** `.env.example` lists names only. Never commit keystores, tokens or passwords.

## Correctness traps already hit in this repo

Learned the hard way. Do not repeat them.

- **Never write a row with `OnConflictStrategy.REPLACE`.** The tables have `ON DELETE CASCADE` foreign keys, and SQLite's REPLACE deletes-then-inserts the parent row, which cascades and silently wipes every child. This destroyed all `split_day_exercises` rows once. Use `@Upsert` (or `@Insert(IGNORE)` + `@Update`). This applies to splits → days → day exercises → workout exercises → sets.
- **Never add `fallbackToDestructiveMigration()`.** It drops user data on schema change. Migrations must be real, and idempotent where a column may already exist.
- **Room uses WAL.** Reading only `gymtracker.db` gives stale results. Pull `gymtracker.db`, `-wal` and `-shm` together, or `PRAGMA wal_checkpoint(TRUNCATE)` first.
- **Rebuild ⇒ reinstall.** After every build, `adb install -r` the APK before looking at the emulator. Verifying new code against a stale APK wastes a session.
- **`Start-Workout.userId` is currently a random UUID** — it must become the real owner's id when auth lands. Do not copy that pattern.
- **The outbox is drained but never filled.** `SyncManager` pushes what is in the outbox, but repositories do not write to it yet, so offline changes do not sync. This is the top functional gap; fix it before claiming sync works.

## Technology choices (fixed)

| Area | Choice |
| --- | --- |
| Android UI | Kotlin, Jetpack Compose, Compose for Wear OS |
| Local DB | Room (2.7.x), `@Upsert` for writes |
| Background work | WorkManager |
| HTTP client | Ktor client |
| Charts (Android) | Vico `2.0.0-alpha.28` |
| Backend | Kotlin, Ktor, PostgreSQL, versioned migrations |
| Server Proxy / Deployment | Direct Docker Compose with exposed website and API ports (no mandatory Caddy) |
| Website | SvelteKit, TypeScript, Vitest |
| Passwords | Argon2id |
| IDs | UUIDv7, generated by the creating client |
| Toolchain | JDK 21 |

Pin dependency versions. Record the minimum supported Android and Wear OS versions in `docs/BUILD_AND_TEST.md`. Do not swap a fixed choice without asking.

## Workflow

Work one milestone at a time. Do not generate the whole application in one pass.

Milestones, in order (details in `DESIGN.md`, section 10): scaffold, Android local logger, backend foundation, sync, Android progress, website, watch, hardening.

Before substantial changes in any session:

1. Inspect the repository. Report existing files, modules, build configuration and tests.
2. State which milestone you are working on and which requirements it covers.
3. Name any unresolved decision that affects correctness, and ask before choosing.
4. Implement only that milestone.

Then verify on the emulator (this is not optional for UI work):

```text
./gradlew -p android :app:assembleDebug
adb -s emulator-5556 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 shell am force-stop com.bluekyeet.powertrack
adb -s emulator-5556 shell am start -n com.bluekyeet.powertrack/com.gymtracker.app.MainActivity
```

Inspect the real screen — do not assume a build means it works:

```text
adb -s emulator-5556 shell uiautomator dump /sdcard/ui.xml
adb -s emulator-5556 pull /sdcard/ui.xml %TEMP%\opencode\ui.xml
python %TEMP%\opencode\uinodes.py %TEMP%\opencode\ui.xml          # exact bounds of every node
adb -s emulator-5556 exec-out screencap -p > %TEMP%\opencode\shot.png   # must use cmd /c for binary
```

Emulators: `emulator-5556` phone, `emulator-5554` watch. ADB lives in `%LOCALAPPDATA%\Android\Sdk\platform-tools\`.

A milestone is done only when:

- Everything compiles from a clean checkout.
- The listed tests pass.
- You ran the commands and report the real output.
- UI work was actually exercised on the emulator, with what you observed.
- Documentation changed with the code.
- You list the files created or changed, the exact commands you ran, known failures and open questions.

Do not start the next milestone until the current one is reported and approved.

## Build and test commands

Filled in — see `docs/BUILD_AND_TEST.md`. Summary:

```text
./gradlew -p shared test
docker compose -f infra/compose.yaml up -d postgres && ./gradlew -p backend test
./gradlew -p android :data:testDebugUnitTest :app:assembleDebug :wear:assembleDebug
npm --prefix web ci && npm --prefix web run check && npm --prefix web run lint && npm --prefix web test && npm --prefix web run build
```

Each project must build and test independently.

## Test status — be honest about this

The suite is currently thin. Do not describe "tests pass" as if the spec's coverage is met.

| Area | What exists today | What `DESIGN.md` requires |
| --- | --- | --- |
| shared | `SharedTests` (validation, conversions, e1RM, volume, grouping) | same, plus PRs |
| Android `:data` | `RoomPersistenceTest` | migrations, outbox atomicity, restart survival, fake-server push/pull, retry/backoff |
| Backend | `AuthTest` | migrations on real PostgreSQL, token rotation reuse detection, pairing expiry/single use, ownership, rate limits, all of `DESIGN.md` 7.5 |
| Website | none (`vitest` finds no test files) | component tests + e2e for login, logging a workout, editing a split, device revocation |
| Whole system | none | offline → sync, duplicates, conflicting edits, history after plan edits, backup restore |

Adding a test that the spec requires counts as milestone work. Never weaken or delete a test to make a build pass.

## Rules for working

- Never report a build or test as passing unless you ran it and it passed. Do not fabricate results.
- Never say a feature was tested on a phone or watch unless it was run on one. If hardware was not available, say so.
- Do not change unrelated modules or rewrite working code without a stated reason.
- Do not weaken a test to make it pass. If a test is wrong, say why and fix it openly.
- Do not add dependencies without a reason. Say what each one is for.
- Do not silently discard user data in a migration — or in a write, ever.
- When blocked or when two requirements contradict, stop and report. Do not implement an unsafe or ambiguous behavior to keep moving.
- Update the matching file in `docs/` whenever a contract changes.
- Commit in small focused groups with messages that say what changed and why. Push to `origin/master` when the owner asks, and say exactly what went up.

## Conventions

- Kotlin: official style, `ktlint` or equivalent configured in the scaffold milestone.
- TypeScript: strict mode on, ESLint and Prettier.
- Commits: small and focused, with a message saying what changed and why.
- Package roots: `com.gymtracker.shared`, `com.gymtracker.app`, `com.gymtracker.wear`, `com.gymtracker.data`, `com.gymtracker.server`.
- Placeholder domain: `gym.example.com` and `api.gym.example.com`. Keep hostnames in config, never hard-coded in source.

## Out of scope

iOS and Apple Watch. Play Store distribution. Website offline mode. Multiple users and public sign-up. LTE-specific watch behavior. Import from other trackers. Do not add these without being asked.
