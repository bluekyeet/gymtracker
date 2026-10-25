# Build and Test

Minimum supported versions: Android API 26 (phone), Wear OS API 30 (watch). JDK 21 for Gradle builds.

## shared
./gradlew -p shared test

## backend (needs PostgreSQL from infra/compose.yaml)
docker compose -f infra/compose.yaml up -d postgres
./gradlew -p backend test

## android
./gradlew -p android :data:testDebugUnitTest :app:assembleDebug :wear:assembleDebug

## web
npm --prefix web ci
npm --prefix web run check
npm --prefix web run lint
npm --prefix web test
npm --prefix web run build

