package com.kidzpos.sync.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kidzpos.domain.Customer;
import com.kidzpos.repo.CustomerRepository;
import com.kidzpos.sync.InboxHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Handler "customer.created" — matérialise un client dans la table customers.
 *
 * Idempotent via {@code existsById}. Source de vérité du contenu = payload remote.
 * On reset {@code version=0} et on laisse le @PreUpdate poser {@code updatedAt}
 * (le @Version JPA de l'entité Customer commence local à 0, indépendamment
 * de la version sur le nœud source — comportement attendu en distribué).
 */
@Component
public class CustomerCreatedHandler implements InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomerCreatedHandler.class);

    private final CustomerRepository repo;
    private final ObjectMapper mapper;

    public CustomerCreatedHandler(CustomerRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "customer.created";
    }

    @Override
    public void apply(UUID eventId, JsonNode payload) {
        String id = text(payload, "id");
        if (id == null) throw new IllegalArgumentException("customer.created: missing 'id'");

        if (repo.existsById(id)) {
            log.debug("[customer.created] {} already exists — skip", id);
            return;
        }

        Customer c;
        try {
            c = mapper.treeToValue(payload, Customer.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("customer.created: cannot deserialize: " + e.getMessage());
        }
        // Reset version locale (chaque nœud a sa propre numérotation @Version).
        c.setVersion(0);
        if (c.getCreatedAt() == null) c.setCreatedAt(Instant.now());
        repo.save(c);
        log.debug("[customer.created] materialized id={} storeId={}", id, c.getStoreId());
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
