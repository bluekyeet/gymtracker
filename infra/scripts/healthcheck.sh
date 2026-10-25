#!/usr/bin/env bash
set -e
wget --spider -q http://localhost:${API_PORT:-8081}/v1/health || exit 1
