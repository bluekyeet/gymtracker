# Authentication

See DESIGN.md section 8 and the Gym Tracker Web Server Specification (`docs/WEBSERVER.md`).

## Endpoints
- `GET /v1/health` (Public): Returns server version, minimum client version, and stable `server_id`.
- `POST /v1/devices/enroll` (Enrollment key): Enrolls a device (`phone` or `watch`) and issues credentials.
- `POST /v1/auth/refresh` (Refresh token): Rotates device credentials.
- `POST /v1/auth/login` (Email and password): Creates website session.
- `POST /v1/auth/logout`: Ends session or signs out device.
- `GET /v1/devices` (Website session): Lists enrolled devices.
- `DELETE /v1/devices/{id}` (Website session): Revokes a device.

## Security & Enrollment
- Normalized enrollment keys (24 characters, 32-char alphabet, ignoring dashes/spaces/case) are compared in constant time.
- Failed enrollment attempts are rate-limited (5/min per client address, 30/hour globally).
- Refresh tokens rotate on use; reuse of a consumed refresh token revokes its token family.
