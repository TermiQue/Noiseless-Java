#!/bin/sh
set -eu
umask 077
BASE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BACKUP_DIR=${1:-"$BASE/backups"}
mkdir -p "$BACKUP_DIR"
DESTINATION="$BACKUP_DIR/noiseless-$(date +%Y%m%d-%H%M%S).db"
cd "$BASE/deploy"
docker compose exec -T app java -jar /app/noiseless.jar backup /data/snapshot.db
docker compose cp app:/data/snapshot.db "$DESTINATION"
docker compose cp app:/data/ai-master.key "$DESTINATION.key"
docker compose exec -T app rm /data/snapshot.db
printf '%s\n' "$DESTINATION"
