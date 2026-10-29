package com.kidzpos.sync.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.kidzpos.repo.ProductRepository;
import com.kidzpos.sync.InboxHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Handler "product.deleted" — soft-delete d'un produit localement.
 *
 * Cohérent avec {@code ProductController#delete} : pose {@code deletedAt} au lieu
 * de DELETE physique, pour préserver l'intégrité référentielle avec
 * {@code sale_items.product_id} et libérer le SKU via l'index partiel.
 *
 * Payload attendu : {@code { id }}.
 * Idempotent : si l'id n'existe pas (jamais reçu), ou est déjà supprimé, no-op.
 */
@Component
public class ProductDeletedHandler implements InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(ProductDeletedHandler.class);

    private final ProductRepository repo;

    public ProductDeletedHandler(ProductRepository repo) {
        this.repo = repo;
    }

    @Override
    public String type() {
        return "product.deleted";
    }

    @Override
    public void apply(UUID eventId, JsonNode payload) {
        JsonNode v = payload.get("id");
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("product.deleted: missing 'id'");
        }
        String id = v.asText();
        repo.findByIdIncludingDeleted(id).ifPresent(p -> {
            if (p.getDeletedAt() != null) {
                log.debug("[product.deleted] {} already soft-deleted — skip", id);
                return;
            }
            p.setDeletedAt(Instant.now());
            repo.save(p);
            log.debug("[product.deleted] soft-deleted id={}", id);
        });
    }
}
