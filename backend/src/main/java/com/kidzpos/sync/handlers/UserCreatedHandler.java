package com.kidzpos.sync.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kidzpos.domain.User;
import com.kidzpos.repo.UserRepository;
import com.kidzpos.security.UserCache;
import com.kidzpos.sync.InboxHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Handler "user.created" — matérialise un utilisateur (employé / admin) sur ce nœud.
 *
 * Le payload contient le User complet, y compris {@code passwordHash} (BCrypt déjà
 * appliqué côté source). On dé-sérialise et persiste tel quel : le login fonctionne
 * sur tous les nœuds avec le même mot de passe immédiatement après sync.
 *
 * Idempotent via {@code existsById}. Invalide le {@link UserCache} si déjà chargé.
 */
@Component
public class UserCreatedHandler implements InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(UserCreatedHandler.class);

    private final UserRepository repo;
    private final ObjectMapper mapper;
    private final UserCache userCache;

    public UserCreatedHandler(UserRepository repo, ObjectMapper mapper, UserCache userCache) {
        this.repo = repo;
        this.mapper = mapper;
        this.userCache = userCache;
    }

    @Override
    public String type() {
        return "user.created";
    }

    @Override
    public void apply(UUID eventId, JsonNode payload) {
        JsonNode idNode = payload.get("id");
        if (idNode == null || idNode.isNull()) {
            throw new IllegalArgumentException("user.created: missing 'id'");
        }
        String id = idNode.asText();

        if (repo.existsById(id)) {
            log.debug("[user.created] {} already exists — skip", id);
            return;
        }

        User u;
        try {
            u = mapper.treeToValue(payload, User.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("user.created: cannot deserialize: " + e.getMessage());
        }
        repo.save(u);
        userCache.invalidate(id);
        log.debug("[user.created] materialized id={} role={} storeId={}",
                id, u.getRole(), u.getStoreId());
    }
}
