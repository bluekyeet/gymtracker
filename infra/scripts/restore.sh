#!/usr/bin/env bash
set -e
if [ -z "$1" ]; then
    echo "Usage: ./restore.sh <backup_file.sql.gz>"
    exit 1
fi
GUNZIP_FILE="${1%.gz}"
gunzip -c "$1" > "$GUNZIP_FILE"
psql -h postgres -U "$POSTGRES_USER" "$POSTGRES_DB" < "$GUNZIP_FILE"
rm "$GUNZIP_FILE"
echo "Database restored successfully from $1"
