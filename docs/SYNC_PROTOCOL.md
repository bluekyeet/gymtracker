# Sync protocol

See DESIGN.md section 7 and `docs/WEBSERVER.md`.

## Server Identity (`server_id`)
- A permanent `server_id` is stored in the database and returned via `GET /v1/health`.
- Clients remember the `server_id` of the server they last synchronized with.
- Connecting to a different server (including a rebuilt server with an empty database) resets the pull cursor, marks local records as needing upload, and prompts the user before uploading local data.
- Disconnecting or changing servers never automatically deletes local workouts.

Exercise changes preserve both the built-in `muscleGroup` enum and the optional free-form `customGroup` label so user-created muscle groups survive push/pull sync.
