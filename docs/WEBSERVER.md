# Gym Tracker Web Server Specification & Architecture

This document specifies the server-side architecture, configuration, device connections, Docker Compose setup, and deployment requirements for Gym Tracker, superseding previous server setup instructions where conflicting.

## 1. Goals

- Start the complete server stack with `docker compose up`.
- Use Docker Compose for development, automated tests, and production.
- Keep server configuration in Compose YAML files rather than requiring a `.env` file.
- Expose exactly two application ports: one for the website and one for the API.
- Never expose PostgreSQL to the public network.
- Let the phone and watch connect by entering a server URL and enrollment key in their settings.
- Keep the apps fully functional as local workout loggers when no server is configured.
- Keep secrets out of Git, logs, error messages, and API responses.
- Keep the deployment small and understandable.

## 2. Services and networking

The base `compose.yaml` defines four services.

| Service | Purpose | Public port |
|---|---|---|
| `web` | Gym Tracker website, served as static files | `WEBSITE_PORT` |
| `api` | Ktor API and database migrations | `API_PORT` |
| `postgres` | Persistent PostgreSQL database | None |
| `backup` | Optional encrypted database backups | None |

There is **no Caddy service and no mandatory reverse proxy** in this design. Website and API use separate published ports.

PostgreSQL must not have a `ports` mapping.

## 3. Configuration and secrets

- `compose.yaml`: common service definitions and safe development defaults.
- `compose.dev.yaml`: development ports, hot reload, and local development overrides.
- `compose.test.yaml`: isolated test databases and test runners.
- `compose.prod.yaml`: production-specific settings and restrictions.
- `compose.secrets.example.yaml`: an example showing how local secret files are supplied.

A local setup is possible without manually creating a `.env` file.
