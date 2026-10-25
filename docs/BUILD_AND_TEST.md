# Build and Test

Minimum supported versions: Android API 26 (phone), Wear OS API 30 (watch). JDK 21 for Gradle builds. Node 20+ for the website. Docker for PostgreSQL.

Application ids: phone `com.bluekyeet.powertrack`, watch `com.bluekyeet.powertrack.wear`.

Each project builds and tests independently.

## shared

```text
./gradlew -p shared test
```

## backend (needs PostgreSQL from infra/compose.yaml)

```text
docker compose -f infra/compose.yaml up -d postgres
./gradlew -p backend test
```

## android

```text
./gradlew -p android :data:testDebugUnitTest :app:assembleDebug :wear:assembleDebug
```

## web

```text
npm --prefix web ci
npm --prefix web run check
npm --prefix web run lint
npm --prefix web test
npm --prefix web run build
```

`npm --prefix web test` currently finds no test files and exits 0. That is not coverage — see `AGENTS.md`, "Test status".

## Run the app on an emulator

ADB is at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` on this machine.
Emulators in use: `emulator-5556` (phone), `emulator-5554` (watch).

```text
./gradlew -p android :app:assembleDebug
adb -s emulator-5556 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 shell am force-stop com.bluekyeet.powertrack
adb -s emulator-5556 shell am start -n com.bluekyeet.powertrack/com.gymtracker.app.MainActivity
```

**Always `install -r` after a build.** Verifying new code against a stale APK produces false results.

### Inspect what is really on screen

```text
adb -s emulator-5556 shell uiautomator dump /sdcard/ui.xml
adb -s emulator-5556 pull /sdcard/ui.xml %TEMP%\opencode\ui.xml
python %TEMP%\opencode\uinodes.py %TEMP%\opencode\ui.xml
```

`uinodes.py` prints every node with its exact bounds, so taps can be aimed precisely instead of guessed.

Screenshot (binary output must go through `cmd /c`, plain `>` corrupts it):

```text
cmd /c "adb -s emulator-5556 exec-out screencap -p > %TEMP%\opencode\shot.png"
```

Bottom navigation tap coordinates on `emulator-5556` (1280×2856): Today x=118, Splits x=384, Exercises x=639, History x=900, Progress x=1160, all at y=2712.

### Reading the app database

Room uses WAL, so pulling only `gymtracker.db` gives stale data. Stop the app first, then take all three files:

```text
adb -s emulator-5556 shell am force-stop com.bluekyeet.powertrack
adb -s emulator-5556 exec-out run-as com.bluekyeet.powertrack cat databases/gymtracker.db
adb -s emulator-5556 exec-out run-as com.bluekyeet.powertrack cat databases/gymtracker.db-wal
adb -s emulator-5556 exec-out run-as com.bluekyeet.powertrack cat databases/gymtracker.db-shm
```

To write data back: push to `/data/local/tmp/`, delete the old `-wal`/`-shm`, `run-as … cp` it into `databases/`.
