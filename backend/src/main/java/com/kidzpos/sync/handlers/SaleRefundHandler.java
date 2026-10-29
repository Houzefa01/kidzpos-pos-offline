package com.kidzpos.sync.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kidzpos.domain.MovementType;
import com.kidzpos.domain.Sale;
import com.kidzpos.domain.SaleItem;
import com.kidzpos.domain.StockMovement;
import com.kidzpos.repo.ProductRepository;
import com.kidzpos.repo.SaleRepository;
import com.kidzpos.repo.StockMovementRepository;
import com.kidzpos.sync.InboxHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Handler "sale.refund" — matérialise un remboursement (= une nouvelle ligne
 * Sale avec {@code refundedFrom} pointant la vente d'origine).
 *
 * Sémantique strictement identique à {@link SaleCheckoutHandler} :
 * désérialisation Jackson + save. Le champ refundedFrom est déjà dans le
 * payload — pas de logique additionnelle.
 *
 * Idempotent via existsById sur l'ID de refund.
 */
@Component
public class SaleRefundHandler implements InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(SaleRefundHandler.class);

    private final SaleRepository sales;
    private final ProductRepository products;
    private final StockMovementRepository movements;
    private final ObjectMapper mapper;

    public SaleRefundHandler(SaleRepository sales,
                             ProductRepository products,
                             StockMovementRepository movements,
                             ObjectMapper mapper) {
        this.sales = sales;
        this.products = products;
        this.movements = movements;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "sale.refund";
    }

    @Override
    public void apply(UUID eventId, JsonNode payload) {
        String saleId = textNonNull(payload, "id");
        if (saleId == null) {
            throw new IllegalArgumentException("sale.refund: missing 'id' in payload");
        }

        if (sales.existsById(saleId)) {
            log.debug("[sale.refund] {} already exists — skip", saleId);
            return;
        }

        Sale s;
        try {
            s = mapper.treeToValue(payload, Sale.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("sale.refund: cannot deserialize: " + e.getMessage());
        }

        // V21-bidir : seq local + reset item ids (cf SaleCheckoutHandler).
        long localSeq = sales.findMaxSeqByStoreId(s.getStoreId()).orElse(0L) + 1;
        s.setSeq(localSeq);

        if (s.getItems() != null) {
            for (SaleItem item : s.getItems()) {
                if (item.getSale() == null) item.setSale(s);
                item.setId(null);
            }
        }
        sales.save(s);

        // V21-stock : restock + journal REFUND movement.
        // Les items du refund ont quantity NEGATIVE (cf SaleController.doRefund).
        // Donc p.stock - item.quantity = p.stock + abs(quantity) = restock.
        // Idempotent via existsById(saleId) au début.
        if (s.getItems() != null) {
            Instant refundDate = s.getDate() != null ? s.getDate() : Instant.now();
            for (SaleItem item : s.getItems()) {
                products.findByIdIncludingDeleted(item.getProductId()).ifPresent(p -> {
                    p.setStock(p.getStock() - item.getQuantity());  // - neg = + abs
                    products.save(p);
                    movements.save(StockMovement.builder()
                            .productId(p.getId())
                            .storeId(p.getStoreId())
                            .type(MovementType.REFUND)
                            .quantity(-item.getQuantity())  // valeur positive
                            .date(refundDate)
                            .userId(s.getUserId())
                            .relatedSaleId(s.getId())
                            .build());
                });
            }
        }

        log.debug("[sale.refund] materialized id={} refundedFrom={} storeId={}",
                saleId, s.getRefundedFrom(), s.getStoreId());
    }

    private static String textNonNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
