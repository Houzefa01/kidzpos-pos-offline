package com.kidzpos.sync.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.kidzpos.repo.UserRepository;
import com.kidzpos.security.UserCache;
import com.kidzpos.sync.InboxHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Handler "user.deleted" — supprime un utilisateur localement.
 *
 * Payload : {@code { id, storeId }}.
 * Idempotent : no-op silencieux si déjà absent. Invalide le {@link UserCache}.
 */
@Component
public class UserDeletedHandler implements InboxHandler {

    private static final Logger log = LoggerFactory.getLogger(UserDeletedHandler.class);

    private final UserRepository repo;
    private final UserCache userCache;

    public UserDeletedHandler(UserRepository repo, UserCache userCache) {
        this.repo = repo;
        this.userCache = userCache;
    }

    @Override
    public String type() {
        return "user.deleted";
    }

    @Override
    public void apply(UUID eventId, JsonNode payload) {
        JsonNode idNode = payload.get("id");
        if (idNode == null || idNode.isNull()) {
            throw new IllegalArgumentException("user.deleted: missing 'id'");
        }
        String id = idNode.asText();
        repo.findById(id).ifPresent(u -> {
            repo.delete(u);
            userCache.invalidate(id);
            log.debug("[user.deleted] removed id={}", id);
        });
    }
}
