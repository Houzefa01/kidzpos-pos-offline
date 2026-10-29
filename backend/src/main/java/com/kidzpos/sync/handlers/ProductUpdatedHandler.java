package com.kidzpos.sync.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.kidzpos.domain.Product;
import com.kidzpos.repo.ProductRepository;
import com.kidzpos.sync.ConflictLogService;
import com.kidzpos.sync.InboxHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Handler "product.updated" : applique les mises à jour de catalogue.
 *
 * Stratégie : MAJ par champ optionnelle — on n'écrit que les champs présents
 * dans le payload (on ne nullifie pas un champ absent). Évite de casser des
 * données locales qui auraient évolué entre temps.
 *
 * Si le produit n'existe PAS localement, on skip silencieusement (la ligne
 * sera marquée processed=true). Ne pas créer-or-update implicitement — un
 * "product.created" doit avoir précédé, ou un opérateur doit créer le produit
 * localement d'abord (politique métier conservatrice).
 *
 * Idempotence : MAJ idempotente par nature (réécrire les mêmes champs avec
 * les mêmes valeurs = no-op fonctionnel). Pas de risque de doublement.
 *
 * Payload attendu (tous champs optionnels sauf id) :
 *   { id, name?, price?, stock?, storeId?, sku?, category? }
 */
@Component
public class ProductUpdatedHandler implements InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(ProductUpdatedHandler.class);

    private final ProductRepository products;
    private final ConflictLogService conflictLog;

    public ProductUpdatedHandler(ProductRepository products, ConflictLogService conflictLog) {
        this.products = products;
        this.conflictLog = conflictLog;
    }

    @Override
    public String type() {
        return "product.updated";
    }

    @Override
    public void apply(UUID eventId, JsonNode payload) {
        String id = text(payload, "id");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("product.updated: missing 'id'");
        }

        Product p = products.findByIdIncludingDeleted(id).orElse(null);
        if (p == null) {
            log.debug("[product.updated] {} not found locally — skip", id);
            return; // skip silencieux : politique conservatrice (cf javadoc)
        }

        // V20 — Détection conflit par timestamp (Last-Writer-Wins par updated_at).
        // Si le local a été modifié APRÈS la création de l'event, l'event arrive
        // "du passé" → on l'IGNORE et on journalise dans conflict_log pour audit.
        // Tolérance : si l'un des timestamps est NULL (legacy), on applique
        // normalement (pas de détection possible).
        Instant localUpdatedAt = p.getUpdatedAt();
        Instant remoteUpdatedAt = parseInstant(payload, "updatedAt");
        if (localUpdatedAt != null && remoteUpdatedAt != null
                && localUpdatedAt.isAfter(remoteUpdatedAt)) {
            log.warn("[product.updated] stale event skipped id={} localUpdatedAt={} remoteUpdatedAt={}",
                    id, localUpdatedAt, remoteUpdatedAt);
            conflictLog.recordStaleUpdate("Product", id, p.getStoreId(),
                    p.getVersion(), parseInteger(payload, "version"),
                    localUpdatedAt, remoteUpdatedAt,
                    payload.toString());
            return; // skip apply — local wins (LWW)
        }

        if (payload.hasNonNull("name")) p.setName(payload.get("name").asText());
        if (payload.hasNonNull("price")) p.setPrice(payload.get("price").asDouble());
        if (payload.hasNonNull("stock")) p.setStock(Math.max(0, payload.get("stock").asInt()));
        if (payload.hasNonNull("storeId")) p.setStoreId(payload.get("storeId").asText());
        if (payload.hasNonNull("sku")) p.setSku(payload.get("sku").asText());
        if (payload.has("category")) {
            // permet explicitement de remettre category=null si payload le précise
            p.setCategory(payload.get("category").isNull() ? null : payload.get("category").asText());
        }

        // V20-fix sync : aligner updatedAt local sur l'horodatage SOURCE de CET event
        // (et non now()), pour un LWW cohérent dans le temps. Le @PreUpdate auto a été
        // retiré (cf Product) → ce set explicite tient ; les saves de stock ne touchent
        // plus updatedAt, donc l'ordre d'arrivée stock/catalogue ne fausse plus le LWW.
        if (remoteUpdatedAt != null) {
            p.setUpdatedAt(remoteUpdatedAt);
        }

        products.save(p);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    /** Parse un Instant ISO-8601 depuis un champ JSON. Retourne null si absent ou invalide. */
    private static Instant parseInstant(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        try {
            return Instant.parse(v.asText());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Parse un Integer depuis un champ JSON. Retourne null si absent ou non-numérique. */
    private static Integer parseInteger(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.canConvertToInt()) return null;
        return v.asInt();
    }
}
