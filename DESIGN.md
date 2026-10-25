# Gym Tracker: Design

Repository: https://github.com/bluekyeet/gymtracker

This document is the product and engineering design. `AGENTS.md` tells a coding agent how to build it. Where the two disagree, stop and ask.

## 1. Summary

A personal gym tracker with four parts: an Android app, a Wear OS watch app, a website, and one backend that keeps them in sync. Every client works offline and syncs when a connection returns.

Decisions already made:

| Topic | Decision |
| --- | --- |
| Users | One user (the owner). No public registration. |
| Distribution | Sideload only. No Play Store. |
| Languages | Kotlin (Android, Wear OS, backend), TypeScript (website) |
| Platforms | Android and Wear OS only. No Apple platforms. |
| Watch sync | Directly with the API, Wi-Fi preferred. No phone needed. LTE optional later. |
| Website offline | Out of scope for v1 |

Principles:

1. **Offline first.** Every device writes to its local database first and never waits on the network to save a set.
2. **Independent devices.** Phone and watch each sync directly with the server. Neither depends on the other.
3. **One API, three clients.** `/v1` serves the phone, watch and website.
4. **Sync is its own subsystem.** It is specified in section 7 and tested adversarially before anything is built on it.
5. **Boring technology.** PostgreSQL, Ktor, Caddy, Room, Compose, SvelteKit.

## 2. Architecture

```text
 Wear OS watch ──┐
 Android phone ──┼── HTTPS ──> Caddy ──> Ktor API ──> PostgreSQL (private network only)
 Website ────────┘                                         │
                                                           └──> encrypted off-site backups
```

- Each Android client keeps a Room (SQLite) database and an outbox of pending changes.
- The website is a normal online app that talks to the same API.
- PostgreSQL is reachable only from the API container.

### Domain and API address

A subdomain is free: it is one extra DNS record on a domain you already own.

- `gym.example.com` serves the website.
- `api.gym.example.com` serves the API.
- Both point at the same server. Caddy routes by hostname and gets and renews a certificate for each automatically. No wildcard certificate is needed.
- The two hostnames share a parent domain, so they are the same site for cookie purposes.
- The API base URL lives in build config on every client.
- All routes are under `/v1`.

Replace `example.com` with the real domain when it is chosen.

## 3. Repository layout

See `AGENTS.md` for the full tree. In short:

- `shared/`: pure Kotlin/JVM module (domain models, validation, calculations, API contract classes, sync operation types). Used by both the Android build and the backend. It must not depend on Android.
- `android/`: Gradle project with `:app` (phone), `:wear` (watch) and `:data` (Room schema, outbox, sync worker, API client, shared by both apps).
- `backend/`: Ktor API and migrations.
- `web/`: SvelteKit site.
- `infra/`: Compose, Caddyfile, backup scripts.
- `docs/`: `openapi.yaml` and the detailed documents.

## 4. Clients

### 4.1 Android phone app (`:app`)

Kotlin, Jetpack Compose.

Features:

- **Today screen.** Suggests the next day in the active split's rotation. The user can pick any day or mark a rest day. Shows that day's exercises and the last session's numbers for each.
- **Set logging.** One tap to add a set. Weight and reps steppers default to the previous set. Optional RPE and a rest timer.
- **Split editor.** Create, rename and reorder days. Add or remove exercises per day. Changes affect future workouts only.
- **Exercise library.** Built-in and custom exercises with muscle group and equipment tags.
- **Progress.** Weight per exercise, weekly volume, estimated 1-rep max, personal records, body weight.
- **History.** Browse and edit past sessions. Editing a past session changes that session explicitly.

Technology:

| Concern | Choice |
| --- | --- |
| UI | Jetpack Compose |
| Local database | Room |
| Background sync | WorkManager |
| HTTP | Ktor client |
| Charts | Vico |
| Credential storage | Android Keystore with EncryptedSharedPreferences |

Composables only display state and forward user actions. No SQL or sync logic in UI code.

### 4.2 Wear OS app (`:wear`)

Compose for Wear OS. Its own application ID and its own APK. The manifest declares it standalone (`com.google.android.wearable.standalone` set to `true`).

The watch is a fast input device. Every screen should work in two or three taps.

Scope:

- Pick or switch today's split day.
- Step through the day's exercises and log sets with large steppers. Use the rotating crown or bezel for weight when present.
- Add, remove or swap an exercise in the current day.
- Rest timer with haptic buzz.
- Sync status icon and a manual sync button.

Left out: graphs, history browsing, exercise library management.

Behavior:

- Logs with no phone, no network and no pairing. Pairing is only needed to sync.
- Syncs directly with the API, preferably on Wi-Fi and ideally while charging, via WorkManager with a network constraint. LTE can be allowed later without design changes.
- Sign-in is by pairing code (section 8.3), not by typing a password.
- Installed over Wi-Fi with `adb` wireless debugging.

### 4.3 Website (`web/`)

SvelteKit with TypeScript. Mostly static, calling the API.

- Everything the phone does, laid out for a wide screen: drag-and-drop split editor, filterable history table.
- Richer graphs: several exercises on one chart, date range zoom, weekly volume by muscle group.
- CSV and JSON export. Import only after the data model is stable.
- Device list and revocation, including pairing a watch.
- Session in an HttpOnly cookie (section 8.2).
- A typed API client generated from `docs/openapi.yaml`.
- No offline mode in v1.

## 5. Data model

PostgreSQL on the server, Room on Android. They implement the same domain model but need not be identical schemas.

Every synced table has: `id` (UUIDv7, generated by the client that creates it), `user_id`, `revision` (integer, increased by the server on every change), `created_at`, `updated_at`, `deleted_at` (null unless soft-deleted).

| Table | Purpose | Key columns |
| --- | --- | --- |
| users | The account | email, password hash, last_seq, unit preference, timezone |
| devices | One row per signed-in phone, watch or browser | user_id, name, kind, last_seen, revoked_at |
| exercises | Built-in and custom exercises | name, muscle group, equipment, is_custom |
| splits | A training plan | name, is_active, rotation position |
| split_days | A day in a split, such as Push | split_id, name, sort_key |
| split_day_exercises | An exercise assigned to a day | split_day_id, exercise_id, sort_key, target sets and reps |
| workout_sessions | One workout | split_day_id (nullable), day_name (snapshot), started_at, ended_at, notes |
| workout_exercises | An exercise inside one workout, copied when the workout starts | session_id, exercise_id (nullable), exercise_name (snapshot), sort_key |
| sets | One logged set | workout_exercise_id, set_number, weight_kg, reps, rpe, set_type |
| body_weights | Body weight entries | date, weight_kg |

Server-only tables:

- `changes`: the sync log (`user_id`, `seq`, `entity`, `entity_id`, `revision`, `deleted`).
- `applied_ops`: processed push operations, keyed by `op_id`, with the stored result (idempotency).
- `refresh_tokens`: hashed refresh tokens per device, with a rotation family.
- `pairing_codes`: hashed one-time pairing codes with expiry.

### Rules

- **History is immutable to plan edits.** When a workout starts, copy the day's exercises into `workout_exercises` with their names and order at that moment. Sets point at those copies. Renaming, reordering or removing an exercise in a split or in the library never changes a past workout.
- **Editing a past session** is an explicit edit of that session's own records.
- **Units.** Weights are stored in kilograms. The UI converts to kg or lb per the user's setting.
- **Ranges.** Weight 0 to 1000 kg with two decimals (`numeric(7,2)`). Reps are whole numbers from 0 to 1000. RPE is 1 to 10 in steps of 0.5, optional.
- **Set types.** `working`, `warmup`, `drop`, `failure`.
- **Set order.** `set_number` is unique per workout exercise among non-deleted sets.
- **Built-in exercises** are seeded by a migration and are read-only to the user. User-created exercises can be edited or soft-deleted. A deleted exercise stays in history through the name snapshot.
- **Deletion** is soft (tombstone). A tombstone is never removed while any device might still need it.
- **Constraints.** Foreign keys everywhere. Unique constraints on natural keys where they exist. Every foreign key must point to a record with the same `user_id`.

### Calculations

Defined once in `shared/` and tested there.

- **Estimated 1-rep max** (Epley): `weight × (1 + reps / 30)`, for working sets with reps from 1 to 12.
- **Volume:** sum of `weight × reps` over non-warmup sets.
- **Personal record:** the best weight for a given rep count per exercise, and the best estimated 1-rep max per exercise.
- **Weekly aggregation:** weeks start Monday in the user's time zone.

## 6. Backend

Kotlin with Ktor, PostgreSQL, Flyway-style versioned migrations, run as a container behind Caddy.

Structure: one folder per feature (`auth`, `devices`, `exercises`, `splits`, `workouts`, `progress`, `sync`). Keep routes thin, put rules in services, and keep SQL in repositories. Split a feature into subfolders only when it grows.

Requirements:

- Every query is scoped to the authenticated user. The user comes from the token, never from the request body.
- Every request that names a record is checked for ownership of that record and its relations.
- Validate every field. Enforce request size limits.
- Errors use one documented format with stable error codes.
- List endpoints are paginated.
- The contract is `docs/openapi.yaml`, written before client work begins.
- Migrations are tested against a real PostgreSQL instance.

Performance:

- Index sync queries on `(user_id, seq)` and history queries on `(user_id, exercise_id, date)`.
- Batch pushes, applied in a single transaction.
- Connection pool, response compression, paginated pulls.
- No caching layer until measurements justify one.

## 7. Sync protocol

Gym data is mostly append-only, so conflicts are rare. The protocol still has to be exact.

### 7.1 Client side

1. Every local change is written, in one database transaction, to both the data table and the outbox. Each outbox entry has a client-generated `op_id`.
2. The UI reads only from the local database. Pending changes show in a sync indicator.
3. A sync run is: push the outbox, then pull until caught up.
4. Outbox entries are removed only after the server reports them applied or permanently rejected.
5. The pull cursor is stored only after the whole page of changes has been applied locally in one transaction.
6. Triggers: app open, after a save when online, when connectivity returns, and periodically via WorkManager. Retries use exponential backoff.

### 7.2 Push

`POST /v1/sync/push` carries `device_id`, `schema_version` and a list of operations. Each operation has `op_id`, `entity`, `entity_id`, `type` (`upsert` or `delete`), `base_revision` and `data`.

- The server handles the whole request in one transaction and returns a result per operation: `applied` (with the new revision), `rejected` (with a reason code, not retryable) or `retry` (transient).
- Results are stored in `applied_ops` by `op_id`. A repeated `op_id` returns the stored result and changes nothing, so a retry after a dropped connection is safe.
- An unsupported `schema_version` is rejected with a clear error asking the user to update the app.

### 7.3 Pull cursor (commit-safe)

A plain PostgreSQL sequence is not safe as a cursor: a transaction can take number 10, another can take 11 and commit first, and a client can move past 10 before it commits.

Required design:

- `users.last_seq` holds the user's latest change number.
- Each push transaction starts by running `UPDATE users SET last_seq = last_seq + n WHERE id = :user RETURNING last_seq`. This locks the user's row until commit, so one user's push transactions run one at a time, and their numbers are assigned in commit order.
- Each applied change gets a `seq` from that block and is written to `changes`.
- `GET /v1/sync/pull?cursor=<seq>&limit=<n>` returns changes with `seq > cursor` in order, plus `next_cursor` and `has_more`. A first sync starts at cursor 0 and pages through the full history.
- The cursor is an opaque string to clients.
- Because numbering is per user and serialized, a change can never appear behind a cursor a client has already passed.

### 7.4 Conflicts

- Every record has a server-managed `revision`.
- **Edit vs edit:** last write wins per record in server order. The client is told the resulting revision and replaces its local copy on the next pull.
- **Delete vs edit:** delete always wins. An edit to a deleted record is rejected with reason `deleted`, and the client drops the edit. A stale device can never bring a deleted record back.
- **Set logs:** appending sets from two devices never conflicts. Each set has its own ID.
- **Split reorders:** records have fractional `sort_key` values. Equal keys are ordered by record ID, so two simultaneous reorders still give one stable order.
- **Own changes** come back in pull. Applying a change whose revision is not newer than the local one is a no-op.
- **Pending local edits** stay in the outbox when a pull arrives. The UI shows the local value until the edit is applied or rejected.

### 7.5 Required tests

Two devices pushing at once. The same push retried after a dropped connection. An interrupted push. An interrupted pull. An edit against a delete. Simultaneous reorders. The app being killed and restarted in the middle of a sync. A first sync of a large history. Another user's credentials trying to read or write the data.

## 8. Authentication and security

### 8.1 General

- Passwords are hashed with Argon2id.
- HTTPS only, with HSTS. TLS ends at Caddy.
- Rate limits on login, pairing and sync.
- Secrets live in environment variables or a secrets file outside the repository. `.env.example` lists names only.
- PostgreSQL has no public port.
- Server hardening: SSH keys only, firewall allowing only 80, 443 and SSH, automatic security updates, fail2ban.

### 8.2 Sessions

- **Native clients:** a short-lived access token (about 15 minutes) and a rotating refresh token, stored per device in Keystore-backed storage. If a used refresh token is presented again, the whole token family is revoked.
- **Website:** the session cookie is HttpOnly, Secure and SameSite=Lax. CORS allows only the site's own origin, with credentials. Every state-changing browser request needs a CSRF token.
- **Logout and revocation:** a device can be revoked from the website. Revoking it invalidates its refresh tokens immediately.
- **Account setup and recovery:** registration is disabled. A server command creates the account and resets the password over SSH.

### 8.3 Watch pairing

1. The unpaired watch asks the server for a pairing code and shows it.
2. The user, signed in on the website or phone, enters the code.
3. The watch polls for the result and receives its own revocable device credentials.

The code is short, valid for 10 minutes, works once, is stored hashed, and wrong guesses are rate limited. If pairing is interrupted, the code expires and the watch starts again. Unpaired watches keep logging locally.

## 9. Hosting and operations

One small VPS (2 vCPU, 2 to 4 GB RAM) runs Caddy, the API and PostgreSQL under Docker Compose. This costs roughly $5 to $10 per month, plus a few dollars for off-site backup storage. These figures are approximate.

- Nightly encrypted backups copied off the server.
- A restore test before launch and again every few months.
- Health check endpoint and container logs.
- Development and production configurations are separate. Local development needs only Docker, not a VPS.
- Scaling later: move PostgreSQL to its own server first. The API keeps no per-user state in memory.

### Android release

- Sign release builds with a keystore. Back it up somewhere safe: updates must be signed with the same key or the phone will refuse them.
- Phone: install the APK directly.
- Watch: `adb connect` over Wi-Fi and install the watch APK.

## 10. Roadmap

Each step compiles, passes its tests, and is run on a real device before the next step starts.

1. **Scaffold and docs.** Repository skeleton, build files that compile, local PostgreSQL via Compose, documentation stubs.
2. **Android local logger.** Room, Today screen, set logging, split editor, history. Fully usable in airplane mode. Tests for restart persistence.
3. **Backend foundation.** Schema and migrations, auth, device management, `docs/openapi.yaml`.
4. **Sync.** Outbox, push, pull, conflicts, WorkManager. Passes every test in section 7.5.
5. **Android progress.** Graphs and personal records with the documented calculations.
6. **Website.** Login, history, split editor, graphs, export, device management.
7. **Watch.** Offline logging, pairing, direct sync, device management.
8. **Hardening.** Backups and a restore test, rate limits, release packaging, end-to-end checks.

## 11. Out of scope for v1

iOS and Apple Watch. Play Store distribution. Website offline mode. Multiple users and public sign-up. LTE-specific watch behavior. Import from other trackers.
