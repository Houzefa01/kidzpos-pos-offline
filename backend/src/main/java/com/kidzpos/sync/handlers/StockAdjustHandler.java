package com.kidzpos.sync.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.kidzpos.domain.MovementType;
import com.kidzpos.domain.Product;
import com.kidzpos.domain.StockMovement;
import com.kidzpos.repo.ProductRepository;
import com.kidzpos.repo.StockMovementRepository;
import com.kidzpos.sync.InboxHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Handler "stock.adjust" — applique un ajustement de stock + journalise le
 * mouvement (équivalent serveur du StockController.adjust).
 *
 * Payload attendu (cf StockController.adjust → opLog.record) :
 *   {
 *     productId, storeId, delta, reason, newStock, clientMovementId
 *   }
 *
 * Idempotence STRICTE via {@code clientMovementId} :
 *   - {@code StockMovementRepository.existsByClientMovementId} → skip si déjà appliqué.
 *
 * Cas d'usage :
 *   - Sur le CENTRAL : matérialise les ajustements de stock du store dans
 *     central.products + central.stock_movements.
 *   - Sur un STORE (pull echo) : skip via existsByClientMovementId.
 */
@Component
public class StockAdjustHandler implements InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(StockAdjustHandler.class);

    private final ProductRepository products;
    private final StockMovementRepository movements;

    public StockAdjustHandler(ProductRepository products, StockMovementRepository movements) {
        this.products = products;
        this.movements = movements;
    }

    @Override
    public String type() {
        return "stock.adjust";
    }

    @Override
    public void apply(UUID eventId, JsonNode payload) {
        String productId = text(payload, "productId");
        if (productId == null) throw new IllegalArgumentException("stock.adjust: missing 'productId'");

        // Idempotence FORTE : si ce mouvement client est déjà appliqué, skip silencieux.
        String clientMovementId = text(payload, "clientMovementId");
        if (clientMovementId != null && movements.existsByClientMovementId(clientMovementId)) {
            log.debug("[stock.adjust] clientMovementId={} already applied — skip", clientMovementId);
            return;
        }

        Product p = products.findByIdIncludingDeleted(productId).orElse(null);
        if (p == null) {
            log.debug("[stock.adjust] product {} not found locally — skip", productId);
            return;
        }

        // Si "newStock" est fourni, alignement absolu (idempotent par nature).
        // Sinon, applique le delta (idempotence portée par clientMovementId + processed flag).
        int delta;
        int previous = p.getStock();
        if (payload.hasNonNull("newStock")) {
            int target = Math.max(0, payload.get("newStock").asInt());
            delta = target - previous;
            p.setStock(target);
        } else if (payload.hasNonNull("delta")) {
            delta = payload.get("delta").asInt();
            p.setStock(Math.max(0, previous + delta));
        } else {
            log.warn("[stock.adjust] {} no delta nor newStock — skip", productId);
            return;
        }
        products.save(p);

        // Journalise le mouvement (équivalent de StockController.adjust)
        movements.save(StockMovement.builder()
                .productId(productId)
                .storeId(text(payload, "storeId"))
                .type(delta >= 0 ? MovementType.IN : MovementType.OUT)
                .quantity(delta)
                .date(Instant.now())
                .userId(null)
                .reason(text(payload, "reason"))
                .clientMovementId(clientMovementId)
                .build());

        log.debug("[stock.adjust] applied productId={} delta={} clientMovementId={}",
                productId, delta, clientMovementId);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
