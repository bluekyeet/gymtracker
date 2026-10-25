# AGENTS.md

Instructions for coding agents working in https://github.com/bluekyeet/gymtracker.

Read `DESIGN.md` first. It is the source of truth for what to build. This file says how to work. If the two disagree or `DESIGN.md` is unclear on something that affects correctness, stop and ask. Do not invent requirements.

## Project

A personal gym tracker for one user: Android phone app, Wear OS app, website, and a Kotlin backend. Offline first, synced through one API. Sideloaded only. No Apple platforms.

## Repository layout

```text
gymtracker/
├── AGENTS.md
├── DESIGN.md
├── README.md
├── .gitignore
├── .editorconfig
├── .env.example
│
├── shared/                  # Pure Kotlin/JVM. No Android dependencies.
│   ├── build.gradle.kts
│   └── src/{main,test}/kotlin/com/gymtracker/shared/
│       ├── domain/          # models, validation, calculations
│       ├── api/             # request and response classes
│       └── sync/            # operation types, conflict rules
│
├── android/                 # One Gradle project
│   ├── settings.gradle.kts  # includes :app, :wear, :data; includeBuild("../shared")
│   ├── app/                 # :app, phone UI
│   ├── wear/                # :wear, watch UI, own application ID
│   ├── data/                # :data, Room schema, outbox, sync worker, API client
│   └── build-logic/         # optional convention plugins
│
├── backend/                 # Kotlin/Ktor, includeBuild("../shared")
│   └── src/{main,test}/kotlin/com/gymtracker/server/
│       ├── Application.kt
│       ├── config/  plugins/  database/  common/
│       └── auth/ devices/ exercises/ splits/ workouts/ progress/ sync/
│
├── web/                     # SvelteKit, TypeScript
│   └── src/{lib,routes}/
│
├── infra/
│   ├── compose.yaml
│   ├── Caddyfile
│   └── scripts/             # backup.sh, restore.sh, healthcheck.sh
│
└── docs/
    ├── openapi.yaml         # the API contract
    ├── ARCHITECTURE.md
    ├── DATA_MODEL.md
    ├── SYNC_PROTOCOL.md
    ├── AUTHENTICATION.md
    ├── BUILD_AND_TEST.md
    └── DEPLOYMENT.md
```

Do not create empty placeholder files. Create a component's files when its milestone begins.

## Architecture rules

- **`shared/` stays pure Kotlin/JVM.** Domain models, validation, calculations, API classes and sync operation types live here. Both the Android build and the backend use it. It must not import Android, Ktor server or database code.
- **`:data` owns local persistence and sync for both Android apps.** The Room schema, outbox, sync worker and API client exist once, in `:data`. `:app` and `:wear` depend on it. Do not copy this code into either app module.
- **UI modules contain UI only.** Composables display state and forward actions. No SQL, no sync logic, no HTTP calls in composables.
- **The backend never depends on Android code.** It shares only `shared/`.
- **The API contract is `docs/openapi.yaml`.** Write it before client integration. Backend responses and the shared Kotlin classes must match it, and contract tests must verify that. The website's TypeScript API types are generated from it.
- **Only the backend talks to PostgreSQL.** Clients never do.
- **The cursor is opaque to clients.** Clients store and return it unchanged.
- **Keep the wear module standalone.** It has its own application ID, manifest and APK, with `com.google.android.wearable.standalone` set to `true`. It must log and sync with no phone running.

## Non-negotiable behavior

These come from `DESIGN.md`. Follow them exactly, and add tests for each.

1. **Local write first.** Every change is committed to the local database and the outbox in one transaction before any network call.
2. **Idempotent sync.** Every push operation has an `op_id`. The server stores results by `op_id`. A retried push never duplicates data.
3. **Commit-safe cursor.** Each push transaction increments `users.last_seq` on the user's row, which serializes that user's transactions. Never use a bare PostgreSQL sequence as a pull cursor.
4. **Cursor advances after apply.** A client stores a new cursor only after the whole page of changes is applied locally.
5. **Delete wins.** An edit to a deleted record is rejected. A stale device can never revive a deleted record.
6. **History is a snapshot.** Starting a workout copies the day's exercises, with names and order, into `workout_exercises`. Plan edits never change past workouts.
7. **Ownership everywhere.** The user comes from the token, never from the request body. Check ownership of every record and relation a request names. Add tests where one user's credentials try to reach another user's data.
8. **Kilograms in storage.** Convert only at the display layer.
9. **No public registration.** Account creation and password reset are server commands.
10. **Secrets stay out of the repo.** `.env.example` lists names only. Never commit keystores, tokens or passwords.

## Technology choices (fixed)

| Area | Choice |
| --- | --- |
| Android UI | Kotlin, Jetpack Compose, Compose for Wear OS |
| Local DB | Room |
| Background work | WorkManager |
| HTTP client | Ktor client |
| Charts (Android) | Vico |
| Backend | Kotlin, Ktor, PostgreSQL, versioned migrations |
| Proxy | Caddy |
| Website | SvelteKit, TypeScript, one chart library chosen in the website milestone |
| Passwords | Argon2id |
| IDs | UUIDv7, generated by the creating client |

Pin dependency versions. Record the minimum supported Android and Wear OS versions in `docs/BUILD_AND_TEST.md`. Do not swap a fixed choice without asking.

## Workflow

Work one milestone at a time. Do not generate the whole application in one pass.

Milestones, in order (details in `DESIGN.md`, section 10):

1. Scaffold and docs
2. Android local logger
3. Backend foundation
4. Sync
5. Android progress
6. Website
7. Watch
8. Hardening

Before substantial changes in any session:

1. Inspect the repository. Report existing files, modules, build configuration and tests.
2. State which milestone you are working on and which requirements it covers.
3. Name any unresolved decision that affects correctness, and ask before choosing.
4. Implement only that milestone.

A milestone is done only when:

- Everything compiles from a clean checkout.
- The listed tests pass.
- You ran the commands and report the real output.
- Documentation changed with the code.
- You list the files created or changed, the exact commands you ran, known failures and open questions.

Do not start the next milestone until the current one is reported and approved. Do not build the watch until sync passes every test in `DESIGN.md` section 7.5.

## Build and test commands

Fill the exact commands into `docs/BUILD_AND_TEST.md` during the scaffold milestone. Expected shape:

```text
# shared
./gradlew -p shared test

# backend (needs PostgreSQL from infra/compose.yaml)
docker compose -f infra/compose.yaml up -d postgres
./gradlew -p backend test

# android
./gradlew -p android :data:testDebugUnitTest :app:assembleDebug :wear:assembleDebug

# web
npm --prefix web ci
npm --prefix web run check
npm --prefix web run lint
npm --prefix web test
npm --prefix web run build
```

Each project must build and test independently.

## Required tests (by area)

- **shared:** weight and rep validation, unit conversion, estimated 1-rep max, volume, personal records, weekly grouping.
- **Android `:data`:** Room migrations, data and outbox written atomically, data survives a process restart, push and pull against a fake server, retry and backoff.
- **Backend:** migrations on a real PostgreSQL, auth and token rotation with reuse detection, pairing code expiry and single use, ownership checks, rate limits, all of `DESIGN.md` section 7.5.
- **Website:** type check, lint, component tests, production build, end-to-end tests for login, logging a workout, editing a split, and device revocation.
- **Whole system:** offline logging then later sync, duplicate requests, conflicting edits from two devices, history preserved after plan edits, backup restore.

## Rules for working

- Never report a build or test as passing unless you ran it and it passed. Do not fabricate results.
- Never say a feature was tested on a phone or watch unless it was run on one. If hardware was not available, say so.
- Do not change unrelated modules or rewrite working code without a stated reason.
- Do not weaken a test to make it pass. If a test is wrong, say why and fix it openly.
- Do not add dependencies without a reason. Say what each one is for.
- Do not silently discard user data in a migration.
- When blocked or when two requirements contradict, stop and report. Do not implement an unsafe or ambiguous behavior to keep moving.
- Update the matching file in `docs/` whenever a contract changes.

## Conventions

- Kotlin: official style, `ktlint` or equivalent configured in the scaffold milestone.
- TypeScript: strict mode on, ESLint and Prettier.
- Commits: small and focused, with a message saying what changed and why.
- Package roots: `com.gymtracker.shared`, `com.gymtracker.app`, `com.gymtracker.wear`, `com.gymtracker.data`, `com.gymtracker.server`.
- Placeholder domain: `gym.example.com` and `api.gym.example.com`. Keep hostnames in config, never hard-coded in source.

## Out of scope

iOS and Apple Watch. Play Store distribution. Website offline mode. Multiple users and public sign-up. LTE-specific watch behavior. Import from other trackers. Do not add these without being asked.
