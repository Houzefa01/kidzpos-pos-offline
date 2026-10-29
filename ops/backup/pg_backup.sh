#!/usr/bin/env bash
# ops/backup/pg_backup.sh — Backup PostgreSQL KidzPOS.
#
# Usage :
#   ./pg_backup.sh                    # backup avec defaults (.env optionnel)
#   PG_DB=kidzpos ./pg_backup.sh      # override ad-hoc
#
# Cron quotidien suggéré (à mettre dans la crontab de l'utilisateur kidzpos) :
#   0 2 * * * /chemin/absolu/ops/backup/pg_backup.sh >> /var/log/kidzpos-backup.log 2>&1
#
# Fonctionnalités :
#   • pg_dump format custom compressé (--format=custom -Z 9)
#   • Timestamp dans le nom de fichier
#   • Rotation N jours (BACKUP_RETENTION_DAYS, défaut 7)
#   • SHA-256 checksum dans <fichier>.sha256
#   • Log structuré stdout (capture-able par cron)
#   • Exit 0 OK / 1 KO (cron-friendly)

set -euo pipefail

# ──── Config (override par env ou .env) ────────────────────────────────────────
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
[ -f "$ROOT/.env" ] && { set -a; . "$ROOT/.env"; set +a; }

PG_HOST="${PG_HOST:-${DB_HOST:-localhost}}"
PG_PORT="${PG_PORT:-${DB_PORT:-5432}}"
PG_DB="${PG_DB:-${DB_NAME:-kidzpos}}"
PG_USER="${PG_USER:-${DB_USER:-kidzpos}}"
PG_PASSWORD="${PG_PASSWORD:-${DB_PASSWORD:-kidzpos}}"

BACKUP_DIR="${BACKUP_DIR:-$ROOT/backups}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-7}"

# ──── Pré-flight ───────────────────────────────────────────────────────────────
ts() { date -Iseconds; }
log() { printf '%s [pg_backup] %s\n' "$(ts)" "$*"; }
err() { printf '%s [pg_backup] ERROR: %s\n' "$(ts)" "$*" >&2; }

command -v pg_dump >/dev/null || { err "pg_dump absent — installer postgresql-client"; exit 1; }
command -v sha256sum >/dev/null || { err "sha256sum absent (coreutils)"; exit 1; }

mkdir -p "$BACKUP_DIR"

STAMP="$(date +%Y%m%dT%H%M%S)"
FILE="$BACKUP_DIR/kidzpos-${STAMP}.dump"
LOGFILE="$BACKUP_DIR/kidzpos-${STAMP}.log"

# ──── Dump ─────────────────────────────────────────────────────────────────────
log "Démarrage backup → $FILE"
log "Source : ${PG_USER}@${PG_HOST}:${PG_PORT}/${PG_DB}"

export PGPASSWORD="$PG_PASSWORD"
if ! pg_dump \
      --host="$PG_HOST" \
      --port="$PG_PORT" \
      --username="$PG_USER" \
      --dbname="$PG_DB" \
      --format=custom \
      --compress=9 \
      --no-owner \
      --no-privileges \
      --file="$FILE" 2> "$LOGFILE"; then
  err "pg_dump a échoué (cf $LOGFILE)"
  rm -f "$FILE"
  exit 1
fi
unset PGPASSWORD

if [ ! -s "$FILE" ]; then
  err "Fichier de backup vide : $FILE"
  exit 1
fi

# ──── Checksum ─────────────────────────────────────────────────────────────────
sha256sum "$FILE" > "${FILE}.sha256"
SIZE_HR="$(du -h "$FILE" | cut -f1)"
log "OK : $FILE ($SIZE_HR), checksum dans ${FILE}.sha256"

# ──── Vérification d'intégrité (pg_restore -l) ────────────────────────────────
if ! pg_restore --list "$FILE" > /dev/null 2>> "$LOGFILE"; then
  err "Le fichier de backup n'est pas un dump custom valide — pg_restore -l a échoué"
  exit 1
fi
log "Intégrité du dump OK (pg_restore -l)"

# ──── Rotation ─────────────────────────────────────────────────────────────────
PURGED=$(find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'kidzpos-*.dump' -o -name 'kidzpos-*.dump.sha256' -o -name 'kidzpos-*.log' \) \
  -mtime +"$BACKUP_RETENTION_DAYS" -print -delete | wc -l)
[ "$PURGED" -gt 0 ] && log "Rotation : $PURGED fichier(s) > ${BACKUP_RETENTION_DAYS}j supprimés"

# ──── Récap ────────────────────────────────────────────────────────────────────
KEPT=$(find "$BACKUP_DIR" -maxdepth 1 -name 'kidzpos-*.dump' | wc -l)
log "Backups conservés : $KEPT"
exit 0
