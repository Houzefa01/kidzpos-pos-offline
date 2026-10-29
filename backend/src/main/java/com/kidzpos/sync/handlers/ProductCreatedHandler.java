package com.kidzpos.sync.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.kidzpos.domain.Product;
import com.kidzpos.repo.ProductRepository;
import com.kidzpos.sync.InboxHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Handler "product.created" : crée le produit localement s'il n'existe pas déjà.
 *
 * Idempotence : check {@code findByIdIncludingDeleted} (inclut les soft-deleted)
 * avant toute création → impossible de violer la PK même si l'événement est
 * rejoué. Un produit soft-deleted localement reste tel quel — on ne le ressuscite
 * pas automatiquement (volonté du gestionnaire local).
 *
 * Payload attendu (best-effort sur les champs optionnels) :
 *   { id, name, price, stock, storeId, sku, category? }
 *
 * Erreurs :
 *   - id manquant → IllegalArgumentException (ligne reste à processed=false)
 *   - violation de contrainte JPA → exception métier (idem)
 */
@Component
public class ProductCreatedHandler implements InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(ProductCreatedHandler.class);

    private final ProductRepository products;

    public ProductCreatedHandler(ProductRepository products) {
        this.products = products;
    }

    @Override
    public String type() {
        return "product.created";
    }

    @Override
    public void apply(UUID eventId, JsonNode payload) {
        String id = text(payload, "id");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("product.created: missing 'id'");
        }

        // Idempotence : inclut les soft-deleted (sinon on tomberait en collision PK
        // sur la prochaine création d'un id "supprimé" puis re-créé en central).
        if (products.findByIdIncludingDeleted(id).isPresent()) {
            log.debug("[product.created] {} already exists locally — skip", id);
            return; // skip silencieux : la TX commit, ligne marquée processed=true
        }

        // V20-fix sync : on PRÉSERVE les horodatages SOURCE (createdAt/updatedAt de
        // l'émetteur) au lieu de re-stamper l'horloge locale. Sans ça, le central
        // stampait updatedAt=now() ici, et tout product.updated ultérieur (émis par
        // le store à un instant antérieur en horloge source) passait pour "stale"
        // dans le LWW de ProductUpdatedHandler → édition perdue sur le central.
        Instant srcCreated = parseInstant(payload, "createdAt");
        // Le payload product.created peut NE PAS porter d'updatedAt : côté émetteur,
        // ProductController.create fait repo.save() sans flush, donc le @PrePersist
        // (qui pose updatedAt=now) n'a pas encore tourné quand opLog.record sérialise.
        // Un produit fraîchement créé a updatedAt == createdAt → on retombe sur
        // createdAt. Indispensable pour le LWW : sans ça, le central re-stampe
        // updatedAt=now() à la matérialisation et juge "stale" tout product.updated
        // ultérieur (perte d'édition nom/prix sur le central).
        Instant srcUpdated = parseInstant(payload, "updatedAt");
        if (srcUpdated == null) srcUpdated = srcCreated;

        Product p = Product.builder()
                .id(id)
                .name(text(payload, "name"))
                .price(payload.path("price").asDouble(0))
                .stock(payload.path("stock").asInt(0))
                .storeId(text(payload, "storeId"))
                .sku(text(payload, "sku"))
                .category(payload.has("category") && !payload.get("category").isNull()
                        ? payload.get("category").asText()
                        : null)
                .createdAt(srcCreated != null ? srcCreated : Instant.now())
                .build();

        // On impose l'updatedAt SOURCE (le @PreUpdate auto a été retiré, et le
        // @PrePersist ne pose une valeur que si elle est absente → notre valeur tient).
        if (srcUpdated != null) {
            p.setUpdatedAt(srcUpdated);
        }

        // Si les contraintes NOT NULL côté entité (name, storeId, sku) sont violées,
        // le save throw → la TX rollback → ligne reste à processed=false (loggée).
        products.save(p);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    /** Parse un Instant ISO-8601 depuis un champ JSON. null si absent/invalide. */
    private static Instant parseInstant(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        try {
            return Instant.parse(v.asText());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
