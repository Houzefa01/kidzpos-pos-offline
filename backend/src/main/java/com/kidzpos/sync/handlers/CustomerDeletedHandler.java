package com.kidzpos.sync.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.kidzpos.repo.CustomerRepository;
import com.kidzpos.sync.InboxHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Handler "customer.deleted" — supprime un client localement.
 *
 * Payload attendu : {@code { id, storeId }}.
 * Idempotent : si l'id n'existe pas, no-op silencieux (déjà supprimé ou
 * jamais reçu sur ce nœud).
 */
@Component
public class CustomerDeletedHandler implements InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomerDeletedHandler.class);

    private final CustomerRepository repo;

    public CustomerDeletedHandler(CustomerRepository repo) {
        this.repo = repo;
    }

    @Override
    public String type() {
        return "customer.deleted";
    }

    @Override
    public void apply(UUID eventId, JsonNode payload) {
        JsonNode v = payload.get("id");
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("customer.deleted: missing 'id'");
        }
        String id = v.asText();
        repo.findById(id).ifPresent(c -> {
            repo.delete(c);
            log.debug("[customer.deleted] removed id={}", id);
        });
    }
}
