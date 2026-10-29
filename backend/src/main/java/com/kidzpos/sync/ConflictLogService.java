package com.kidzpos.sync;

import com.kidzpos.domain.ConflictLog;
import com.kidzpos.repo.ConflictLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Écriture best-effort du journal des conflits. Modelé sur OperationLogService :
 *   - Toute exception est avalée. Ne doit JAMAIS faire échouer le pipeline appelant.
 *   - Insertion dans la transaction courante (le handler décide du contexte).
 *   - Aucun side-effect réseau, aucun event publié.
 *
 * Constantes publiques pour les valeurs courantes — évite les fautes de frappe
 * et facilite la requête côté ops :
 *   SELECT * FROM conflict_log WHERE conflict_type = 'stale_update';
 */
@Service
public class ConflictLogService {

    private static final Logger log = LoggerFactory.getLogger(ConflictLogService.class);

    // ─── Types de conflit (constantes) ─────────────────────────────────────
    public static final String TYPE_STALE_UPDATE     = "stale_update";       // local plus récent que remote
    public static final String TYPE_NEGATIVE_STOCK   = "negative_stock";     // stock résultant < 0
    public static final String TYPE_VERSION_MISMATCH = "version_mismatch";   // @Version conflict (futur)
    public static final String TYPE_REFUND_DUPLICATE = "refund_duplicate";   // vente déjà remboursée (futur)

    // ─── Résolutions appliquées ────────────────────────────────────────────
    public static final String RES_SKIP_REMOTE   = "skip_remote";   // on ignore l'event, on garde le local
    public static final String RES_ACCEPT_REMOTE = "accept_remote"; // on remplace par remote (LWW)
    public static final String RES_CLAMP         = "clamp";         // valeur bornée (ex: stock=0)
    public static final String RES_LOGGED_ONLY   = "logged_only";   // observation, pas d'action

    private final ConflictLogRepository repo;

    public ConflictLogService(ConflictLogRepository repo) {
        this.repo = repo;
    }

    /**
     * Enregistre un conflit. Tous les paramètres sont optionnels sauf les
     * trois premiers (entityType, entityId, conflictType, resolution).
     */
    public void record(String entityType, String entityId, String storeId,
                       String conflictType, String resolution,
                       Integer localVersion, Integer remoteVersion,
                       Instant localUpdatedAt, Instant remoteUpdatedAt,
                       String payloadJson) {
        try {
            repo.save(ConflictLog.builder()
                    .id(UUID.randomUUID())
                    .entityType(entityType)
                    .entityId(entityId)
                    .storeId(storeId)
                    .conflictType(conflictType)
                    .resolution(resolution)
                    .localVersion(localVersion)
                    .remoteVersion(remoteVersion)
                    .localUpdatedAt(localUpdatedAt)
                    .remoteUpdatedAt(remoteUpdatedAt)
                    .payload(payloadJson)
                    .detectedAt(Instant.now())
                    .build());
        } catch (Exception e) {
            // Best-effort : on log mais on n'interrompt PAS le flux appelant.
            log.warn("conflict_log skipped (entityType={} entityId={} type={}): {}",
                    entityType, entityId, conflictType, e.getMessage());
        }
    }

    /** Helper : "stale update" (local plus récent que l'event remote). */
    public void recordStaleUpdate(String entityType, String entityId, String storeId,
                                  Integer localVersion, Integer remoteVersion,
                                  Instant localUpdatedAt, Instant remoteUpdatedAt,
                                  String payloadJson) {
        record(entityType, entityId, storeId, TYPE_STALE_UPDATE, RES_SKIP_REMOTE,
                localVersion, remoteVersion, localUpdatedAt, remoteUpdatedAt, payloadJson);
    }

    /** Helper : "negative stock" (clamp à 0). */
    public void recordNegativeStock(String entityId, String storeId,
                                    int attemptedValue, String payloadJson) {
        record("Product", entityId, storeId, TYPE_NEGATIVE_STOCK, RES_CLAMP,
                null, attemptedValue, null, null, payloadJson);
    }
}
