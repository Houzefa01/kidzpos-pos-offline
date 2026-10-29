package com.kidzpos.sync.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.kidzpos.domain.Product;
import com.kidzpos.repo.ProductRepository;
import com.kidzpos.sync.ConflictLogService;
import com.kidzpos.sync.InboxHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Handler "stock.adjusted" : aligne le stock local sur la valeur de référence.
 *
 * STRATÉGIE PRIVILÉGIÉE : utiliser {@code newStock} (valeur ABSOLUE) plutôt
 * que {@code delta}. Idempotent par nature : appliquer 3 fois "stock=10"
 * donne stock=10. Appliquer "delta=+3" 3 fois donne stock+9 (cassant).
 *
 * Si le payload ne contient PAS newStock, on tombe en fallback delta avec un
 * WARN — l'invariant idempotence est alors porté par {@code processed=true}
 * + la TX (apply+mark atomiques).
 *
 * Si le produit n'existe pas localement, skip silencieux (politique
 * conservatrice : on ne pré-crée pas de produit ici).
 *
 * Payload attendu :
 *   { productId, newStock }    — préféré (idempotent absolu)
 *   { productId, delta }       — fallback (idempotent via processed flag)
 */
@Component
public class StockAdjustedHandler implements InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(StockAdjustedHandler.class);

    private final ProductRepository products;
    private final ConflictLogService conflictLog;

    public StockAdjustedHandler(ProductRepository products, ConflictLogService conflictLog) {
        this.products = products;
        this.conflictLog = conflictLog;
    }

    @Override
    public String type() {
        return "stock.adjusted";
    }

    @Override
    public void apply(UUID eventId, JsonNode payload) {
        String productId = text(payload, "productId");
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("stock.adjusted: missing 'productId'");
        }

        Product p = products.findByIdIncludingDeleted(productId).orElse(null);
        if (p == null) {
            log.debug("[stock.adjusted] product {} not found locally — skip", productId);
            return; // skip silencieux
        }

        if (payload.hasNonNull("newStock")) {
            int requestedTarget = payload.get("newStock").asInt();
            int target = Math.max(0, requestedTarget);
            // V20 — Si l'event demande un stock négatif (incohérence remote),
            // on clamp à 0 ET on journalise le conflit pour audit.
            if (requestedTarget < 0) {
                conflictLog.recordNegativeStock(productId, p.getStoreId(), requestedTarget, payload.toString());
                log.warn("[stock.adjusted] {} clamped negative newStock={} → 0", productId, requestedTarget);
            }
            if (p.getStock() != target) {
                p.setStock(target);
                products.save(p);
            }
            return;
        }

        if (payload.hasNonNull("delta")) {
            log.warn("[stock.adjusted] {} using delta fallback (idempotence rely on processed flag)", productId);
            int delta = payload.get("delta").asInt();
            int requestedNext = p.getStock() + delta;
            int next = Math.max(0, requestedNext);
            // V20 — Idem : un delta qui ferait passer le stock en négatif est
            // clampé à 0 et journalisé. Le delta est probablement issu d'un
            // décompte concurrent non observé par le central.
            if (requestedNext < 0) {
                conflictLog.recordNegativeStock(productId, p.getStoreId(), requestedNext, payload.toString());
                log.warn("[stock.adjusted] {} clamped negative result (stock={}, delta={}) → 0",
                        productId, p.getStock(), delta);
            }
            if (next != p.getStock()) {
                p.setStock(next);
                products.save(p);
            }
            return;
        }

        log.warn("[stock.adjusted] {} no newStock nor delta in payload — skip", productId);
        // Ni newStock ni delta → on traite comme no-op (la ligne sera marquée processed=true).
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
