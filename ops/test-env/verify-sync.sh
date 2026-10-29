#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# verify-sync.sh — Compare l'état des 2 bases pour vérifier l'identité post-sync.
#
# Affiche pour chaque entité (products, sales, sale_items, stock_movements,
# customers) :
#   - le compte sur central (filtré par store_id=s1)
#   - le compte sur store s1
#   - le delta + ✓/⚠
#   - les IDs présents d'un côté mais pas de l'autre (10 premiers)
#
# "Identique" attendu après sync :
#   - central.sales (WHERE store_id=s1)  = store_s1.sales  (mêmes IDs)
#   - central.stock_movements (WHERE store_id=s1) = store_s1.stock_movements
#   - central agrège : union de TOUS les stores ⇒ central.sales total ≥ store
#
# Usage :
#   ./ops/test-env/verify-sync.sh
# ─────────────────────────────────────────────────────────────────────────────
set -e

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-kidzpos}"
PGPASSWORD="${PGPASSWORD:-kidzpos}"
export PGPASSWORD

DB_CENTRAL="${DB_CENTRAL:-kidzpos_central}"
DB_STORE="${DB_STORE:-kidzpos_store_s1}"
STORE_ID="${STORE_ID:-s1}"

psql_central() { psql -t -A -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_CENTRAL" -c "$1" 2>/dev/null; }
psql_store()   { psql -t -A -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_STORE"   -c "$1" 2>/dev/null; }

echo "════════════════════════════════════════════════════════════════════"
echo "  Sync verification — central vs store_$STORE_ID"
echo "════════════════════════════════════════════════════════════════════"

# ─── État sync interne ───────────────────────────────────────────────────────
echo ""
echo "── État sync interne ──────────────────────────────────────────────"
printf "  %-30s %15s %15s\n" "Table" "Central" "Store"
printf "  %-30s %15s %15s\n" "-----" "-------" "-----"
for tbl in operation_log sync_inbox quarantine_events conflict_log; do
    C=$(psql_central "SELECT COUNT(*) FROM $tbl;" || echo "n/a")
    S=$(psql_store   "SELECT COUNT(*) FROM $tbl;" || echo "n/a")
    printf "  %-30s %15s %15s\n" "$tbl" "$C" "$S"
done

UNSYNC_S=$(psql_store "SELECT COUNT(*) FROM operation_log WHERE synced=false;" || echo "n/a")
UNPROC_C=$(psql_central "SELECT COUNT(*) FROM sync_inbox WHERE processed=false;" || echo "n/a")
UNPROC_S=$(psql_store "SELECT COUNT(*) FROM sync_inbox WHERE processed=false;" || echo "n/a")
echo ""
echo "  Store   : $UNSYNC_S ops en attente de push, $UNPROC_S events inbox à traiter"
echo "  Central : $UNPROC_C events inbox à matérialiser"

# ─── Données métier ──────────────────────────────────────────────────────────
echo ""
echo "── Données métier (filtre store_id=$STORE_ID sur central) ──────────"
printf "  %-30s %15s %15s %15s\n" "Table" "Central(s=$STORE_ID)" "Store" "Δ"
printf "  %-30s %15s %15s %15s\n" "-----" "-----------------" "-----" "--"
for tbl in products sales sale_items stock_movements customers; do
    if [ "$tbl" = "sale_items" ]; then
        C=$(psql_central "SELECT COUNT(*) FROM sale_items si JOIN sales s ON si.sale_id=s.id WHERE s.store_id='$STORE_ID';" || echo "n/a")
        S=$(psql_store   "SELECT COUNT(*) FROM sale_items;" || echo "n/a")
    else
        C=$(psql_central "SELECT COUNT(*) FROM $tbl WHERE store_id='$STORE_ID';" || echo "n/a")
        S=$(psql_store   "SELECT COUNT(*) FROM $tbl;" || echo "n/a")
    fi
    if [[ "$C" =~ ^[0-9]+$ && "$S" =~ ^[0-9]+$ ]]; then
        DELTA=$((C - S))
        if [ "$DELTA" -eq 0 ]; then
            STATUS="$DELTA  ✓"
        elif [ "$DELTA" -lt 0 ]; then
            STATUS="$DELTA  ⚠ push?"
        else
            STATUS="+$DELTA  ⚠ pull?"
        fi
    else
        STATUS="n/a"
    fi
    printf "  %-30s %15s %15s %15s\n" "$tbl" "$C" "$S" "$STATUS"
done

# ─── Diff d'IDs : sales ──────────────────────────────────────────────────────
echo ""
echo "── IDs ventes sur store mais ABSENT central (10 premiers) ──────────"
IDS_C=$(psql_central "SELECT id FROM sales WHERE store_id='$STORE_ID' ORDER BY id;" | sort)
IDS_S=$(psql_store   "SELECT id FROM sales ORDER BY id;" | sort)
MISSING=$(comm -23 <(echo "$IDS_S") <(echo "$IDS_C") | head -10)
if [ -z "$MISSING" ]; then
    echo "  ✓ Toutes les ventes du store sont matérialisées sur central"
else
    echo "$MISSING" | sed 's/^/  → /'
fi

echo ""
echo "── IDs sur central(s=$STORE_ID) mais ABSENT store (10 premiers) ─────"
MISSING_REV=$(comm -13 <(echo "$IDS_S") <(echo "$IDS_C") | head -10)
if [ -z "$MISSING_REV" ]; then
    echo "  ✓ État identique"
else
    echo "$MISSING_REV" | sed 's/^/  → /'
fi

# ─── Snapshot quarantaines & conflits ────────────────────────────────────────
echo ""
echo "── Quarantaines récentes (central, top 5) ──────────────────────────"
psql_central "SELECT event_type, reason, store_id FROM quarantine_events ORDER BY created_at DESC LIMIT 5;" | sed 's/^/  /'

echo ""
echo "── Conflits récents (central, top 5) ───────────────────────────────"
psql_central "SELECT entity_type, entity_id, conflict_type, resolution FROM conflict_log ORDER BY detected_at DESC LIMIT 5;" | sed 's/^/  /'

echo ""
echo "════════════════════════════════════════════════════════════════════"
echo "  Lecture :"
echo "    Δ=0  → identique (sync OK)"
echo "    Δ<0  → store devant central (push pas encore propagé, retry 10s)"
echo "    Δ>0  → central devant store (pull pas encore relu — OK pour autres stores)"
echo "════════════════════════════════════════════════════════════════════"
