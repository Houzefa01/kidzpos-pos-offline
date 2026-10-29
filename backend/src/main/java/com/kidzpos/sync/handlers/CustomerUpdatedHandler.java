package com.kidzpos.sync.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.kidzpos.domain.Customer;
import com.kidzpos.repo.CustomerRepository;
import com.kidzpos.sync.InboxHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Handler "customer.updated" — applique les modifications partielles d'un client.
 *
 * Stratégie : charge le local, copie les champs présents dans le payload,
 * save. Préserve le {@code @Version} local (incrémenté par JPA) et évite
 * un OptimisticLockException en désérialisant l'objet entier (versions
 * peuvent différer entre nœuds — c'est attendu).
 *
 * Si le customer n'existe pas localement, skip silencieux (politique
 * conservatrice — un update sans created préalable signale une désynchro).
 */
@Component
public class CustomerUpdatedHandler implements InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomerUpdatedHandler.class);

    private final CustomerRepository repo;

    public CustomerUpdatedHandler(CustomerRepository repo) {
        this.repo = repo;
    }

    @Override
    public String type() {
        return "customer.updated";
    }

    @Override
    public void apply(UUID eventId, JsonNode payload) {
        String id = text(payload, "id");
        if (id == null) throw new IllegalArgumentException("customer.updated: missing 'id'");

        Customer c = repo.findById(id).orElse(null);
        if (c == null) {
            log.debug("[customer.updated] {} not found locally — skip", id);
            return;
        }

        // MAJ partielle par champ (similaire à CustomerController.update).
        if (payload.hasNonNull("name"))       c.setName(payload.get("name").asText());
        if (payload.hasNonNull("phone"))      c.setPhone(payload.get("phone").asText());
        if (payload.hasNonNull("email"))      c.setEmail(payload.get("email").asText());
        if (payload.hasNonNull("points"))     c.setPoints(payload.get("points").asInt());
        if (payload.hasNonNull("totalSpent")) c.setTotalSpent(payload.get("totalSpent").asDouble());
        if (payload.hasNonNull("visits"))     c.setVisits(payload.get("visits").asInt());
        // storeId NON modifié (immuable une fois assigné).

        repo.save(c);
        log.debug("[customer.updated] applied id={}", id);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
