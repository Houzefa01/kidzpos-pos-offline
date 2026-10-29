package com.kidzpos.sync;

import com.kidzpos.domain.OperationLog;
import com.kidzpos.domain.SyncInbox;
import com.kidzpos.repo.OperationLogRepository;
import com.kidzpos.repo.SyncInboxRepository;
import com.kidzpos.sync.SyncApiKeyFilter.SyncPrincipal;
import com.kidzpos.sync.SyncDtos.PullResponse;
import com.kidzpos.sync.SyncDtos.PushOperation;
import com.kidzpos.sync.SyncDtos.PushRequest;
import com.kidzpos.sync.SyncDtos.PushResponse;
import com.kidzpos.sync.SyncDtos.SyncStatusResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronisation local ↔ central.
 *
 * PUSH : implémentation réelle. Le local envoie un batch d'opérations ;
 *        le central déduplique par UUID et persiste les nouvelles dans
 *        son operation_log (synced=true par convention : déjà chez le
 *        destinataire final).
 *
 * PULL : stub conservé (volontaire — l'objectif actuel est PUSH only).
 *
 * Sécurité : protégé par
 *   (1) ROLE_ADMIN (JWT) — existant, conservé pour appel manuel admin.
 *   (2) X-Sync-Api-Key (header) — server-to-server, cf SyncApiKeyFilter.
 * Aucun caissier ne peut atteindre ces routes.
 *
 * Ne fait AUCUNE logique métier : pas d'effet sur stock, ventes, etc.
 * Pure centralisation des événements. La conversion event → état métier
 * (replay, snapshot) sera traitée dans une future itération.
 */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private static final Logger log = LoggerFactory.getLogger(SyncController.class);

    private final String nodeRole;
    private final String nodeId;
    private final OperationLogRepository repo;
    private final SyncInboxRepository inboxRepo;

    public SyncController(@Value("${kidzpos.node.role:standalone}") String nodeRole,
                          @Value("${kidzpos.node.id:default}") String nodeId,
                          OperationLogRepository repo,
                          SyncInboxRepository inboxRepo) {
        this.nodeRole = nodeRole;
        this.nodeId = nodeId;
        this.repo = repo;
        this.inboxRepo = inboxRepo;
    }

    /**
     * Réception d'un batch d'opérations. Idempotence : si une opération avec
     * cet UUID existe déjà, elle est IGNORÉE (le local peut donc rejouer
     * sans risque de duplication).
     *
     * Insertion dans une transaction unique → le batch est tout ou rien.
     */
    @PostMapping("/push")
    @Transactional
    public ResponseEntity<?> push(@Valid @RequestBody PushRequest req,
                                  @AuthenticationPrincipal Object principal) {
        if (req == null || req.operations() == null || req.operations().isEmpty()) {
            log.info("[sync] push received but empty (node={})", req == null ? "?" : req.nodeId());
            return ResponseEntity.ok(new PushResponse(0, 0, 0, List.of(), List.of()));
        }

        // V21 — Cross-validation : si l'appelant est authentifié par une clé per-store,
        // il NE PEUT pousser QUE des opérations de SON store. Empêche un store
        // compromis de pousser des operations forgées au nom d'un autre store.
        // - Mode JWT ADMIN : pas de SyncPrincipal → bypass (admin omnipotent).
        // - Mode legacy (clé partagée) : SyncPrincipal.perStore=false → bypass.
        // - Mode per-store : enforce strict.
        if (principal instanceof SyncPrincipal sp && sp.perStore() && sp.storeId() != null) {
            for (PushOperation op : req.operations()) {
                if (op.storeId() != null && !sp.storeId().equals(op.storeId())) {
                    log.warn("[sync] cross-store push rejected: authenticated store={} pushed op for store={}",
                            sp.storeId(), op.storeId());
                    return ResponseEntity.status(403).body(Map.of(
                            "error", "cross-store push forbidden",
                            "authenticatedStoreId", sp.storeId(),
                            "rejectedStoreId", op.storeId()
                    ));
                }
            }
        }

        int received = req.operations().size();
        List<UUID> accepted = new ArrayList<>();
        List<UUID> duplicates = new ArrayList<>();

        for (PushOperation op : req.operations()) {
            if (op.id() == null) continue;   // sanity : on skip silencieusement
            if (repo.existsById(op.id())) {
                duplicates.add(op.id());
                continue;
            }
            OperationLog row = OperationLog.builder()
                    .id(op.id())
                    .type(op.type())
                    .payload(op.payload() == null ? "{}" : op.payload())
                    .createdAt(op.createdAt() == null ? Instant.now() : op.createdAt())
                    // synced=true : par définition, l'opération est arrivée à
                    // destination finale (le central). Aucun re-push à faire.
                    .synced(true)
                    // V18 : preserve l'origine magasin pour le futur pull filtré.
                    // NULL accepté (client pré-V18) pour rétrocompat.
                    .storeId(op.storeId())
                    .build();
            repo.save(row);

            // V21 — Dual-write : si l'inbox processor tourne sur ce nœud (typiquement
            // le central avec kidzpos.sync.inbox.enabled=true), on alimente aussi
            // sync_inbox pour que les events soient matérialisés dans les tables métier.
            // Idempotence : check existsById sur sync_inbox également — si déjà inséré
            // (replay du même push), no-op silencieux.
            if (!inboxRepo.existsById(op.id())) {
                inboxRepo.save(SyncInbox.builder()
                        .id(op.id())
                        .type(op.type())
                        .payload(op.payload() == null ? "{}" : op.payload())
                        .createdAt(op.createdAt() == null ? Instant.now() : op.createdAt())
                        .processed(false)
                        .storeId(op.storeId())
                        .build());
            }
            accepted.add(op.id());
        }

        log.info("[sync] push processed (node={}) received={} accepted={} duplicates={}",
                req.nodeId(), received, accepted.size(), duplicates.size());

        return ResponseEntity.ok(new PushResponse(
                received, accepted.size(), duplicates.size(), accepted, duplicates));
    }

    /**
     * Pull incrémental : retourne les événements de operation_log dont
     * created_at > since, en ordre chronologique croissant, plafonné à limit.
     *
     * V18 — Filtre par storeId. Si le local fournit son magasin, le central ne
     * retourne QUE les événements de ce magasin (isolation multi-magasin).
     *
     * <p><b>Audit-2026-05</b> — En mode per-store (auth via {@link SyncApiKeyFilter}
     * avec clé V21), {@code storeId} est OBLIGATOIRE et doit match le storeId de
     * la clé. Empêche un store compromis (clé volée) de demander les events de
     * tous les magasins en omettant simplement le paramètre. Si {@code storeId}
     * est fourni mais diffère du storeId du principal → 403 strict.
     *
     * <p>Mode ADMIN-JWT ou legacy (clé partagée) : {@code storeId} reste optionnel
     * (admin omnipotent / legacy bypass — backward compat).
     *
     * since   : null = premier pull (renvoie tout depuis le début, plafonné).
     * storeId : obligatoire en per-store ; optionnel en ADMIN/legacy.
     * limit   : clampé entre 1 et 500 (anti-DoS).
     */
    @GetMapping("/pull")
    public ResponseEntity<PullResponse> pull(@RequestParam(required = false) Instant since,
                                             @RequestParam(required = false) String storeId,
                                             @RequestParam(required = false, defaultValue = "100") int limit,
                                             @AuthenticationPrincipal Object principal) {
        // V21-pull-guard : en mode per-store, le storeId doit être fourni et match.
        if (principal instanceof SyncPrincipal sp && sp.perStore() && sp.storeId() != null) {
            if (storeId == null || storeId.isBlank()) {
                log.warn("[sync] pull rejected: per-store auth (store={}) but no storeId query param",
                        sp.storeId());
                return ResponseEntity.status(400).body(new PullResponse(List.of(), false));
            }
            if (!sp.storeId().equals(storeId)) {
                log.warn("[sync] cross-store pull rejected: authenticated store={} requested store={}",
                        sp.storeId(), storeId);
                return ResponseEntity.status(403).body(new PullResponse(List.of(), false));
            }
        }
        int safeLimit = Math.min(Math.max(1, limit), 500);
        var pageable = PageRequest.of(0, safeLimit);
        var pageableSorted = org.springframework.data.domain.PageRequest.of(0, safeLimit,
                org.springframework.data.domain.Sort.by("createdAt").ascending());
        boolean hasStore = storeId != null && !storeId.isBlank();

        List<OperationLog> rows;
        if (hasStore && since != null) {
            rows = repo.findByStoreIdAndCreatedAtGreaterThanOrderByCreatedAtAsc(storeId, since, pageable);
        } else if (hasStore) {
            rows = repo.findByStoreIdOrderByCreatedAtAsc(storeId, pageable);
        } else if (since != null) {
            rows = repo.findByCreatedAtGreaterThanOrderByCreatedAtAsc(since, pageable);
        } else {
            rows = repo.findAll(pageableSorted).getContent();
        }

        List<PushOperation> ops = new ArrayList<>(rows.size());
        for (OperationLog op : rows) {
            ops.add(new PushOperation(op.getId(), op.getType(), op.getPayload(), op.getCreatedAt(), op.getStoreId()));
        }

        boolean hasMore = ops.size() == safeLimit;
        log.info("[sync] pull served (node={}) since={} storeId={} returned={} hasMore={}",
                nodeId, since, storeId, ops.size(), hasMore);

        return ResponseEntity.ok(new PullResponse(ops, hasMore));
    }

    /**
     * T15 — État de la sync (lag + backlog) pour le frontend.
     *
     * <p>Endpoint léger destiné à l'OfflineBanner : permet d'afficher
     * "X opérations en attente sur central — Y s de retard" plutôt que juste
     * "online/offline". L'utilisateur sait s'il peut éteindre sans risque, ou
     * s'il faut attendre que le push se vide.
     *
     * <p>Côté central : pendingCount lit operation_log.synced=false (qui devrait
     * être 0 vu que push marque synced=true). Côté store : lit le vrai backlog.
     *
     * <p>oldestPending : ISO timestamp de l'event le plus ancien non syncé
     * (NULL si rien à pousser). lagSeconds = age(oldestPending) côté serveur,
     * cohérent avec la métrique Prometheus kidzpos_sync_lag_seconds.
     */
    @GetMapping("/status")
    public ResponseEntity<SyncStatusResponse> status() {
        long pending = repo.countBySyncedFalse();
        var oldest = repo.findFirstBySyncedFalseOrderByCreatedAtAsc();
        long lagSeconds = oldest
                .map(o -> Math.max(0L, Instant.now().getEpochSecond() - o.getCreatedAt().getEpochSecond()))
                .orElse(0L);
        return ResponseEntity.ok(new SyncStatusResponse(
                nodeRole,
                nodeId,
                pending,
                oldest.map(o -> o.getCreatedAt()).orElse(null),
                lagSeconds
        ));
    }
}
