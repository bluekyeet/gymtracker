#!/bin/sh
curl -fsS http://localhost:8080/health || exit 1
