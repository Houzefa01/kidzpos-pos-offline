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
 * Handler "stock.transfer" — applique un transfert inter-magasin.
 *
 * Payload attendu (cf StockController.transfer → opLog.record) :
 *   {
 *     sourceProductId, sourceStoreId,
 *     targetProductId, targetStoreId,
 *     quantity, clientMovementId
 *   }
 *
 * Idempotence : clientMovementId via stock_movements (unique partial V8).
 *
 * Cas d'usage : sur le CENTRAL uniquement, normalement (les transferts sont
 * admin-only et le central agrège). Sur un store, l'event peut revenir via
 * pull → skip silencieux via existsByClientMovementId.
 */
@Component
public class StockTransferHandler implements InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(StockTransferHandler.class);

    private final ProductRepository products;
    private final StockMovementRepository movements;

    public StockTransferHandler(ProductRepository products, StockMovementRepository movements) {
        this.products = products;
        this.movements = movements;
    }

    @Override
    public String type() {
        return "stock.transfer";
    }

    @Override
    public void apply(UUID eventId, JsonNode payload) {
        String clientMovementId = text(payload, "clientMovementId");
        if (clientMovementId != null && movements.existsByClientMovementId(clientMovementId)) {
            log.debug("[stock.transfer] clientMovementId={} already applied — skip", clientMovementId);
            return;
        }

        String srcId = text(payload, "sourceProductId");
        String dstId = text(payload, "targetProductId");
        String srcStore = text(payload, "sourceStoreId");
        String dstStore = text(payload, "targetStoreId");
        int qty = payload.hasNonNull("quantity") ? payload.get("quantity").asInt() : 0;
        if (srcId == null || dstId == null || qty <= 0) {
            throw new IllegalArgumentException("stock.transfer: invalid payload (srcId/dstId/qty)");
        }

        // Source : decrement
        Product src = products.findByIdIncludingDeleted(srcId).orElse(null);
        if (src != null) {
            src.setStock(Math.max(0, src.getStock() - qty));
            products.save(src);
            movements.save(StockMovement.builder()
                    .productId(srcId)
                    .storeId(srcStore)
                    .type(MovementType.TRANSFER)
                    .quantity(-qty)
                    .date(Instant.now())
                    .userId(null)
                    .reason("Transfert vers " + dstStore)
                    .clientMovementId(clientMovementId)  // unique sur source
                    .build());
        }

        // Cible : increment
        Product dst = products.findByIdIncludingDeleted(dstId).orElse(null);
        if (dst != null) {
            dst.setStock(dst.getStock() + qty);
            products.save(dst);
            movements.save(StockMovement.builder()
                    .productId(dstId)
                    .storeId(dstStore)
                    .type(MovementType.TRANSFER)
                    .quantity(qty)
                    .date(Instant.now())
                    .userId(null)
                    .reason("Transfert depuis " + srcStore)
                    // pas de clientMovementId sur cible — contrainte unique partielle
                    .build());
        }

        log.debug("[stock.transfer] applied src={} → dst={} qty={} clientMovementId={}",
                srcId, dstId, qty, clientMovementId);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
