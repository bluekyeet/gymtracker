#!/usr/bin/env bash
set -e
BACKUP_DIR="/backups"
mkdir -p "$BACKUP_DIR"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_FILE="$BACKUP_DIR/gymtracker_$TIMESTAMP.sql"
pg_dump -h postgres -U "$POSTGRES_USER" "$POSTGRES_DB" > "$BACKUP_FILE"
gzip "$BACKUP_FILE"
echo "Backup saved to $BACKUP_FILE.gz"
