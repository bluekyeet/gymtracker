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

## Setup

Requires: JDK 21, Docker (for PostgreSQL and the deployed stack), Node 20+ (for the website), and Android Studio / the Android SDK (for the phone and watch apps). This is a personal, sideloaded app — there is no Play Store listing and no public sign-up.

### 1. Server (backend + website)

The backend and website run together via the root `compose.yaml`. PostgreSQL is never exposed publicly; only the API and website ports are.

```text
cp .env.example .env
# edit .env: set POSTGRES_PASSWORD, JWT_SECRET, and anything else you don't want left at the example value

docker compose up -d --build
```

This starts three services: `postgres` (private, no host port), `api` (`API_PORT`, default `8081`), and `web` (`WEBSITE_PORT`, default `8080`). After an API code change, rebuild just that service:

```text
docker compose up -d --build api
```

Check it's alive:

```text
curl http://localhost:8081/v1/health
```

Create the owner account and an enrollment key using the server's account-creation command (there is no public registration — see `docs/AUTHENTICATION.md`). Devices (phone, watch) use that enrollment key once, from the app's Settings screen or by scanning a pairing QR code, to receive their own long-lived device credentials.

For a real deployment (not local), see `docs/DEPLOYMENT.md` for the `compose.prod.yaml` overlay and `infra/scripts/init-secrets.sh`.

### 2. Android apps (phone + watch)

Both apps live in the single `android/` Gradle project (`:app` phone, `:wear` watch, `:data` shared local storage/sync) and depend on `shared/` via `includeBuild`.

```text
./gradlew -p android :app:assembleDebug :wear:assembleDebug
```

Install on a device or emulator (phone app id `com.bluekyeet.powertrack`, watch app id `com.bluekyeet.powertrack.wear`):

```text
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/wear/build/outputs/apk/debug/wear-debug.apk
```

On first launch, open **Settings** and enter the server URL (e.g. `http://10.0.2.2:8081` from the Android emulator talking to a host-machine server, or your server's LAN/public address on real hardware) plus the enrollment key. Pairing a watch after the phone is already enrolled can be done by scanning the QR code shown in the phone's Settings screen. Full emulator verification workflow (uiautomator dumps, screenshots, reading the Room database) is in `docs/BUILD_AND_TEST.md`.

### 3. Website

```text
cd web
npm ci
npm run dev        # local development
npm run build       # production build, served by the web service in compose.yaml
```

Set `PUBLIC_API_BASE_URL` (see `compose.yaml`) to point the website at your API.
