# Deployment

See DESIGN.md section 9 and `docs/WEBSERVER.md`.

## Docker Compose Deployment
- Uses `compose.yaml`, `compose.dev.yaml`, `compose.test.yaml`, and `compose.prod.yaml`.
- Exposes exactly two application ports: website (`WEBSITE_PORT`) and API (`API_PORT`).
- PostgreSQL has no public port mapping and is private to the Docker network.
- Persistent volumes (`pgdata`, backup storage) are preserved across `docker compose down`.
- Run secret initialization via `infra/scripts/init-secrets.sh` before starting production or development stacks.
