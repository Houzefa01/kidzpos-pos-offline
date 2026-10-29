package com.kidzpos.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTOs pour la synchronisation local → central (PUSH only).
 *
 * Forme stable du contrat : ne pas modifier ces records sans
 * versionner l'endpoint (ex: /api/sync/push/v2). Le local et le
 * central peuvent être déployés à des versions différentes — on
 * privilégie un format simple, additif.
 */
public class SyncDtos {

    /**
     * Une opération telle qu'elle a été journalisée localement.
     * Mapping 1:1 avec {@link com.kidzpos.domain.OperationLog} (sans le flag
     * `synced` qui est interne au local).
     *
     *   - id        : UUID stable, sert à l'idempotence côté central
     *   - type      : ex "sale.checkout", "stock.adjust", "stock.transfer"
     *   - payload   : JSON string (la sérialisation est faite par le local)
     *   - createdAt : horodatage d'origine (préserve l'ordre chronologique)
     */
    public record PushOperation(
            @NotNull UUID id,
            @NotBlank String type,
            @NotNull String payload,
            @NotNull Instant createdAt,
            /* V18 — magasin d'origine. Optionnel pour compat ascendante :
             *   - clients pré-V18 ne le posent pas → NULL côté central
             *   - le central tolère NULL (rétrocompat) mais le runtime
             *     filtre cross-store quand storeId est présent. */
            String storeId
    ) {}

    /**
     * Batch envoyé par le local. `nodeId` permet au central de tracer la
     * provenance (futur multi-magasins). `operations` : 1..N entrées.
     */
    public record PushRequest(
            String nodeId,
            @NotNull List<PushOperation> operations
    ) {}

    /**
     * Réponse du central. `accepted` = opérations effectivement persistées
     * (nouvelles). `duplicates` = ids reçus mais déjà connus → idempotence,
     * le local peut les marquer synced=true en toute confiance.
     */
    public record PushResponse(
            int receivedCount,
            int acceptedCount,
            int duplicateCount,
            List<UUID> acceptedIds,
            List<UUID> duplicateIds
    ) {}

    /**
     * Réponse du pull : événements postérieurs au cursor `since`, ordre
     * chronologique croissant. Forme identique aux PushOperation (le PULL
     * et le PUSH transportent la même structure d'événement, juste dans
     * des sens opposés).
     *
     * `hasMore` = true si le central a tronqué à `limit` (probablement plus
     * de données dispo) → le local peut enchaîner un autre pullOnce dans le
     * même tick scheduler.
     */
    public record PullResponse(
            List<PushOperation> operations,
            boolean hasMore
    ) {}

    /**
     * T15 — État de la sync exposé au frontend via GET /api/sync/status.
     *
     * <p>Consommé par OfflineBanner pour afficher "X ops en attente, Y s de
     * retard" plutôt qu'un simple "online/offline". Permet à l'opérateur de
     * savoir s'il peut éteindre la machine sans perdre de données.
     *
     * @param nodeRole       "central" | "local" | "standalone"
     * @param nodeId         identifiant logique du nœud
     * @param pendingCount   nombre d'events {@code operation_log.synced=false}
     * @param oldestPending  timestamp du plus vieux event non syncé (null = rien)
     * @param lagSeconds     age(oldestPending) en secondes (0 si rien)
     */
    public record SyncStatusResponse(
            String nodeRole,
            String nodeId,
            long pendingCount,
            Instant oldestPending,
            long lagSeconds
    ) {}
}
