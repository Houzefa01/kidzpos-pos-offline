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
 * Handler "user.updated" — applique les modifications d'un utilisateur sur ce nœud.
 *
 * Si l'id n'existe pas localement, on délègue au comportement de
 * {@link UserCreatedHandler#apply} (matérialise) pour éviter le trou en cas d'ordre
 * d'arrivée inversé (update avant created). Invalide le {@link UserCache}.
 */
@Component
public class UserUpdatedHandler implements InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(UserUpdatedHandler.class);

    private final UserRepository repo;
    private final ObjectMapper mapper;
    private final UserCache userCache;

    public UserUpdatedHandler(UserRepository repo, ObjectMapper mapper, UserCache userCache) {
        this.repo = repo;
        this.mapper = mapper;
        this.userCache = userCache;
    }

    @Override
    public String type() {
        return "user.updated";
    }

    @Override
    public void apply(UUID eventId, JsonNode payload) {
        JsonNode idNode = payload.get("id");
        if (idNode == null || idNode.isNull()) {
            throw new IllegalArgumentException("user.updated: missing 'id'");
        }
        String id = idNode.asText();

        User remote;
        try {
            remote = mapper.treeToValue(payload, User.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("user.updated: cannot deserialize: " + e.getMessage());
        }

        var existing = repo.findById(id);
        if (existing.isEmpty()) {
            // Compense un ordre d'arrivée inversé (update reçu avant created).
            repo.save(remote);
            userCache.invalidate(id);
            log.debug("[user.updated] materialized (no prior) id={}", id);
            return;
        }
        User u = existing.get();
        u.setName(remote.getName());
        u.setEmail(remote.getEmail());
        u.setRole(remote.getRole());
        u.setStoreId(remote.getStoreId());
        u.setActive(remote.isActive());
        u.setPasswordHash(remote.getPasswordHash());
        repo.save(u);
        userCache.invalidate(id);
        log.debug("[user.updated] applied id={}", id);
    }
}
