#!/usr/bin/env bash
# ops/backup/offsite_sync.sh — Réplication off-site du dossier $BACKUP_DIR.
#
# Lance ce script APRÈS pg_backup.sh dans le cron (ou en chaîne &&) pour
# garantir que les backups sont copiés hors de la machine du store.
#
# Sans backup off-site, un crash disque = perte définitive des données
# (la sync central est asynchrone et peut avoir des minutes de retard).
#
# Cron quotidien suggéré :
#   0 2 * * * /chemin/.../ops/backup/pg_backup.sh && \
#             /chemin/.../ops/backup/offsite_sync.sh \
#             >> /var/log/kidzpos-backup.log 2>&1
#
# Supporte 3 backends, sélectionnés par $BACKUP_OFFSITE_KIND :
#   rsync   : rsync vers un host SSH (NAS, autre serveur)
#   rclone  : rclone vers un remote configuré (S3, B2, GCS, etc.)
#   local   : cp vers un point de montage local (USB, /mnt/nas)
#
# Variables :
#   BACKUP_OFFSITE_KIND       rsync | rclone | local       (obligatoire)
#   BACKUP_OFFSITE_DEST       chemin/url destination       (obligatoire)
#   BACKUP_OFFSITE_RSYNC_OPTS options rsync supplémentaires (optionnel)
#   BACKUP_OFFSITE_RCLONE_FLAGS flags rclone supplémentaires (optionnel)
#   BACKUP_DIR                source (défaut: $ROOT/backups)
#
# Exemples :
#   BACKUP_OFFSITE_KIND=rsync  BACKUP_OFFSITE_DEST="backup@nas.local:/volume1/kidzpos/"
#   BACKUP_OFFSITE_KIND=rclone BACKUP_OFFSITE_DEST="s3-backup:kidzpos-prod/store-s1/"
#   BACKUP_OFFSITE_KIND=local  BACKUP_OFFSITE_DEST="/mnt/usb-backup/kidzpos/"

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
[ -f "$ROOT/.env" ] && { set -a; . "$ROOT/.env"; set +a; }

BACKUP_DIR="${BACKUP_DIR:-$ROOT/backups}"
BACKUP_OFFSITE_KIND="${BACKUP_OFFSITE_KIND:-}"
BACKUP_OFFSITE_DEST="${BACKUP_OFFSITE_DEST:-}"

ts()  { date -Iseconds; }
log() { printf '%s [offsite] %s\n' "$(ts)" "$*"; }
err() { printf '%s [offsite] ERROR: %s\n' "$(ts)" "$*" >&2; }

# ──── Pré-flight ───────────────────────────────────────────────────────────────
if [ -z "$BACKUP_OFFSITE_KIND" ] || [ -z "$BACKUP_OFFSITE_DEST" ]; then
  err "BACKUP_OFFSITE_KIND et BACKUP_OFFSITE_DEST doivent être définis (cf .env.example)."
  err "Off-site backup DÉSACTIVÉ — risque de perte définitive si crash disque."
  # Exit 0 pour ne pas faire échouer le cron si l'opérateur n'a pas encore configuré ;
  # une alerte Prometheus distincte (BackupOffsiteMissing) signalera l'oubli.
  exit 0
fi

[ -d "$BACKUP_DIR" ] || { err "Source $BACKUP_DIR absent — rien à pousser."; exit 1; }

# Compte les dumps disponibles (sanity-check)
COUNT=$(find "$BACKUP_DIR" -maxdepth 1 -name 'kidzpos-*.dump' | wc -l)
if [ "$COUNT" -eq 0 ]; then
  err "Aucun dump trouvé dans $BACKUP_DIR — pg_backup.sh a-t-il tourné ?"
  exit 1
fi
log "Source : $BACKUP_DIR ($COUNT dumps), Dest : $BACKUP_OFFSITE_DEST ($BACKUP_OFFSITE_KIND)"

# ──── Vérification checksums avant push (ne pas répliquer un fichier corrompu) ──
log "Vérification checksums locaux"
FAILED=0
for sha in "$BACKUP_DIR"/*.sha256; do
  [ -f "$sha" ] || continue
  if ! (cd "$BACKUP_DIR" && sha256sum -c --quiet "$(basename "$sha")"); then
    err "Checksum invalide : $sha — refus de répliquer un fichier corrompu"
    FAILED=$((FAILED + 1))
  fi
done
if [ "$FAILED" -gt 0 ]; then
  err "$FAILED fichier(s) corrompu(s) → abort"
  exit 1
fi

# ──── Push ─────────────────────────────────────────────────────────────────────
case "$BACKUP_OFFSITE_KIND" in
  rsync)
    command -v rsync >/dev/null || { err "rsync absent"; exit 1; }
    # --partial : reprise si interruption ; --delete-after : sync miroir (purge
    # ce que la rotation locale a effacé) ; -z : compression in-transit.
    # shellcheck disable=SC2086
    rsync -avz --partial --delete-after \
      ${BACKUP_OFFSITE_RSYNC_OPTS:-} \
      "$BACKUP_DIR/" "$BACKUP_OFFSITE_DEST"
    ;;
  rclone)
    command -v rclone >/dev/null || { err "rclone absent — installer https://rclone.org/install/"; exit 1; }
    # sync = miroir (delete extras) ; --checksum = vérifie l'intégrité distante
    # via hash (lent mais robuste). En cas de gros volume, --size-only suffit.
    # shellcheck disable=SC2086
    rclone sync \
      --checksum \
      --transfers 4 \
      --retries 3 \
      ${BACKUP_OFFSITE_RCLONE_FLAGS:-} \
      "$BACKUP_DIR" "$BACKUP_OFFSITE_DEST"
    ;;
  local)
    # cp -au = update mode (skip si dest plus récente). Pour purger les vieux
    # backups côté dest, on relance le find rotation localement.
    mkdir -p "$BACKUP_OFFSITE_DEST"
    cp -au "$BACKUP_DIR"/* "$BACKUP_OFFSITE_DEST/"
    # Rotation miroir : applique le même TTL côté dest
    RETENTION="${BACKUP_RETENTION_DAYS:-7}"
    find "$BACKUP_OFFSITE_DEST" -maxdepth 1 -type f \
      \( -name 'kidzpos-*.dump' -o -name 'kidzpos-*.dump.sha256' -o -name 'kidzpos-*.log' \) \
      -mtime +"$RETENTION" -delete
    ;;
  *)
    err "BACKUP_OFFSITE_KIND inconnu : '$BACKUP_OFFSITE_KIND' (attendu: rsync|rclone|local)"
    exit 1
    ;;
esac

log "OK : $COUNT dumps répliqués vers $BACKUP_OFFSITE_DEST"

# ──── Métriques pour Prometheus (textfile collector node_exporter) ─────────────
# Si node_exporter est configuré avec --collector.textfile.directory=/var/lib/prometheus/textfile/
# (cf docker-compose ops/), expose l'âge du dernier backup off-site pour alerter.
TEXTFILE_DIR="${TEXTFILE_COLLECTOR_DIR:-/var/lib/prometheus/textfile}"
if [ -d "$TEXTFILE_DIR" ] && [ -w "$TEXTFILE_DIR" ]; then
  cat > "$TEXTFILE_DIR/kidzpos_backup_offsite.prom.$$" <<EOF
# HELP kidzpos_backup_offsite_last_success_timestamp_seconds Last successful offsite backup unix ts
# TYPE kidzpos_backup_offsite_last_success_timestamp_seconds gauge
kidzpos_backup_offsite_last_success_timestamp_seconds $(date +%s)
# HELP kidzpos_backup_offsite_files_total Number of dump files synced
# TYPE kidzpos_backup_offsite_files_total gauge
kidzpos_backup_offsite_files_total $COUNT
EOF
  mv "$TEXTFILE_DIR/kidzpos_backup_offsite.prom.$$" "$TEXTFILE_DIR/kidzpos_backup_offsite.prom"
fi

exit 0
