# PowerTrack

![PowerTrack Fitness Banner](docs/powertrack-banner.png)

A personal gym tracker for one person: an Android phone app, a Wear OS watch app, a website, and a Kotlin backend that keeps them in sync. Offline first — every device writes to its own database and syncs when a connection returns. Sideloaded only, no Play Store, no Apple platforms.

- **`DESIGN.md`** — what the product is and how it behaves. Source of truth for behavior.
- **`AGENTS.md`** — how to work in this repo, including the UI/UX rules the app follows.
- **`docs/`** — build commands, the Today screen spec, and pointers into `DESIGN.md`.

## Layout

| Path | What it is |
| --- | --- |
| `shared/` | Pure Kotlin/JVM: domain models, validation, calculations, API classes |
| `android/` | One Gradle project: `:app` (phone), `:wear` (watch), `:data` (Room, outbox, sync) |
| `backend/` | Ktor API on PostgreSQL, versioned migrations |
| `web/` | SvelteKit site |
| `infra/` | Docker Compose (PostgreSQL), Caddy, backup scripts |
| `docs/` | `openapi.yaml`, build/test commands, design pointers |

## Quick start

```text
./gradlew -p shared test
docker compose -f infra/compose.yaml up -d postgres
./gradlew -p backend test
./gradlew -p android :data:testDebugUnitTest :app:assembleDebug :wear:assembleDebug
npm --prefix web ci && npm --prefix web run check && npm --prefix web run lint && npm --prefix web run build
```

Full commands, minimum versions and the emulator verification workflow are in `docs/BUILD_AND_TEST.md`.
