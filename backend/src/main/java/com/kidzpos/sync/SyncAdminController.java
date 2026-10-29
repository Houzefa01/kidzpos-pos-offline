package com.kidzpos.sync;

import com.kidzpos.domain.QuarantineEvent;
import com.kidzpos.domain.SyncInbox;
import com.kidzpos.repo.QuarantineEventRepository;
import com.kidzpos.repo.SyncInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Outils d'exploitation sur la quarantaine des événements sync_inbox.
 *
 * ENDPOINTS :
 *   - POST /api/sync/quarantine/{id}/replay : ré-injecte un événement
 *     quarantiné dans sync_inbox pour qu'il soit retenté au prochain tick
 *     du processor. Idempotent.
 *   - GET  /api/sync/quarantine?type=&limit=&offset= : listing paginé avec
 *     filtre optionnel par type d'événement.
 *
 * SÉCURITÉ : couvert par la règle existante de SecurityConfig
 *   .requestMatchers("/api/sync/**").hasRole("ADMIN")
 * Acceptable via JWT (admin humain) OU X-Sync-Api-Key (outil d'ops),
 * la 2e voie passant aussi en ROLE_ADMIN via SyncApiKeyFilter.
 *
 * NE TOUCHE PAS :
 *   - Les lignes quarantine_events (jamais supprimées automatiquement → audit)
 *   - Les handlers (rien n'est appliqué ici, juste réinjection)
 *   - Le scheduler / processor (qui prendra le relais au prochain tick)
 */
@RestController
@RequestMapping("/api/sync/quarantine")
public class SyncAdminController {

    private static final Logger log = LoggerFactory.getLogger(SyncAdminController.class);

    private final QuarantineEventRepository quarantineRepo;
    private final SyncInboxRepository inboxRepo;
    private final NodeContext nodeContext;
    private final TransactionTemplate tx;

    public SyncAdminController(QuarantineEventRepository quarantineRepo,
                               SyncInboxRepository inboxRepo,
                               NodeContext nodeContext,
                               PlatformTransactionManager txm) {
        this.quarantineRepo = quarantineRepo;
        this.inboxRepo = inboxRepo;
        this.nodeContext = nodeContext;
        this.tx = new TransactionTemplate(txm);
    }

    // ─── REPLAY ─────────────────────────────────────────────────────────────

    /**
     * Ré-injecte l'événement quarantiné dans sync_inbox.
     *
     * 4 trajectoires possibles :
     *   1. quarantine introuvable → 404
     *   2. sync_inbox row existe DÉJÀ à processed=false → no-op (already-pending)
     *      → l'événement est déjà dans la file de retry, le replay serait redondant.
     *   3. sync_inbox row existe à processed=true → on remet processed=false +
     *      reset retry_count=0 + last_attempt_at=null → "restored".
     *   4. sync_inbox row n'existe plus (purge manuelle, restore partiel) →
     *      on RECRÉE la ligne depuis le snapshot quarantine → "reinjected".
     *
     * Dans tous les cas, la ligne quarantine_events est PRÉSERVÉE (audit trail).
     */
    @PostMapping("/{id}/replay")
    public ResponseEntity<?> replay(@PathVariable UUID id) {
        QuarantineEvent qe = quarantineRepo.findById(id).orElse(null);
        if (qe == null) {
            log.warn("[sync-admin] replay rejected: quarantine event not found id={}", id);
            return ResponseEntity.status(404).body(Map.of(
                    "error", "quarantine event not found",
                    "id", id.toString()));
        }

        try {
            ReplayOutcome outcome = tx.execute(status -> doReplay(qe));
            return logAndReturn(outcome, qe);
        } catch (Exception e) {
            // Échec technique : la TX a rollback. Aucune modification persistée.
            log.error("[sync-admin] replay FAILED quarantineId={} type={}: {}",
                    qe.getId(), qe.getEventType(), e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "replay failed",
                    "reason", e.getMessage() == null ? "unknown" : e.getMessage(),
                    "quarantineId", qe.getId().toString(),
                    "type", qe.getEventType()));
        }
    }

    /**
     * Logique unique du replay, exécutée dans une transaction unique.
     */
    private ReplayOutcome doReplay(QuarantineEvent qe) {
        UUID inboxId = qe.getOriginalInboxId();
        Optional<SyncInbox> existing = inboxRepo.findById(inboxId);

        if (existing.isPresent()) {
            SyncInbox row = existing.get();
            if (!row.isProcessed()) {
                // Cas 2 : déjà en attente d'être (re)traité par le processor.
                // Aucune action requise. Retour explicite pour ne pas mentir.
                return new ReplayOutcome(
                        "already-pending",
                        qe.getId(),
                        inboxId,
                        "sync_inbox row already exists with processed=false (retry_count="
                                + row.getRetryCount() + ")");
            }
            // Cas 3 : déjà appliqué OU déjà quarantiné via processed=true.
            // On reset le tracking pour permettre une retentative propre.
            row.setProcessed(false);
            row.setRetryCount(0);
            row.setLastAttemptAt(null);
            inboxRepo.save(row);
            return new ReplayOutcome(
                    "restored",
                    qe.getId(),
                    inboxId,
                    "sync_inbox row reset (processed=false, retry_count=0)");
        }

        // Cas 4 : la ligne sync_inbox a disparu (action manuelle hors process).
        // On recrée depuis le snapshot quarantine — id, type, payload, createdAt
        // sont conservés à l'identique pour préserver l'idempotence côté handler.
        // V18 : on préserve aussi storeId pour que la garde cross-store du
        // processor s'applique correctement au prochain tick.
        SyncInbox neu = SyncInbox.builder()
                .id(inboxId)
                .type(qe.getEventType())
                .payload(qe.getPayload())
                .createdAt(qe.getCreatedAt())
                .processed(false)
                .retryCount(0)
                .lastAttemptAt(null)
                .storeId(qe.getStoreId())
                .build();
        inboxRepo.save(neu);
        return new ReplayOutcome(
                "reinjected",
                qe.getId(),
                inboxId,
                "sync_inbox row recreated from quarantine snapshot");
    }

    /**
     * Log selon le résultat — niveaux conformes au cahier :
     *   INFO  : replay effectif (reinjected / restored)
     *   WARN  : situation suspecte mais non bloquante (already-pending)
     *   ERROR : géré dans le catch supérieur (technique)
     */
    private ResponseEntity<?> logAndReturn(ReplayOutcome outcome, QuarantineEvent qe) {
        switch (outcome.status()) {
            case "reinjected", "restored" -> log.info(
                    "[sync-admin] replay {} quarantineId={} originalInboxId={} type={}",
                    outcome.status(), qe.getId(), qe.getOriginalInboxId(), qe.getEventType());
            case "already-pending" -> log.warn(
                    "[sync-admin] replay no-op (already-pending) quarantineId={} originalInboxId={} type={}",
                    qe.getId(), qe.getOriginalInboxId(), qe.getEventType());
            default -> log.warn(
                    "[sync-admin] replay unexpected status='{}' quarantineId={}",
                    outcome.status(), qe.getId());
        }
        return ResponseEntity.ok(outcome);
    }

    // ─── LISTING ────────────────────────────────────────────────────────────

    /**
     * Listing paginé pour l'outillage d'ops.
     *
     * Params :
     *   - type   : filtre exact sur event_type. Vide/absent → tous les types.
     *   - limit  : 1..500 (clampé). Défaut 50.
     *   - offset : ≥ 0 (clampé). Défaut 0.
     *
     * Pagination par offset (pas par page) pour s'adapter à un offset arbitraire.
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String type,
                                  @RequestParam(required = false, defaultValue = "50") int limit,
                                  @RequestParam(required = false, defaultValue = "0") int offset) {
        int safeLimit = Math.min(Math.max(1, limit), 500);
        int safeOffset = Math.max(0, offset);
        String safeType = (type == null || type.isBlank()) ? null : type;
        // V19-audit — Sur nœud store-scoped, filtre AUTOMATIQUE par store.
        // Sur central, retourne toutes les quarantines (cross-store agrégé, OK).
        String scope = nodeContext.isStoreScoped() ? nodeContext.storeId() : null;

        List<QuarantineEvent> items = quarantineRepo.findPage(safeType, scope, safeLimit, safeOffset);
        long total = quarantineRepo.countByType(safeType, scope);

        log.debug("[sync-admin] listing type={} storeScope={} limit={} offset={} returned={}/{}",
                safeType, scope, safeLimit, safeOffset, items.size(), total);

        return ResponseEntity.ok(Map.of(
                "items", items,
                "total", total,
                "limit", safeLimit,
                "offset", safeOffset,
                "type", safeType == null ? "" : safeType,
                "storeScope", scope == null ? "" : scope
        ));
    }

    // ─── DTO interne ────────────────────────────────────────────────────────

    /**
     * Réponse du replay. `status` ∈ {reinjected, restored, already-pending}.
     */
    public record ReplayOutcome(String status, UUID quarantineId, UUID inboxId, String message) {}
}
