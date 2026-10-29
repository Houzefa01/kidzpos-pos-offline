package com.kidzpos.events;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M5 : tokens éphémères pour authentifier le handshake SSE.
 *
 * EventSource ne peut pas envoyer de header Authorization ; le client passe
 * donc un token à usage unique en query param. Émis par /api/events/auth (auth JWT)
 * et consommé par /api/events/stream.
 *
 * In-memory : suffit pour un backend mono-instance. Une connexion SSE survit
 * à l'expiration du token (on ne vérifie qu'au handshake), donc TTL court OK.
 */
@Component
public class EventTokenStore {

    public static final long TTL_MS = 60_000L;

    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    public String issue() {
        pruneExpired();
        String token = UUID.randomUUID().toString();
        tokens.put(token, System.currentTimeMillis() + TTL_MS);
        return token;
    }

    /** Vérifie ET retire le token (à usage unique). */
    public boolean consume(String token) {
        if (token == null) return false;
        Long expiresAt = tokens.remove(token);
        return expiresAt != null && expiresAt > System.currentTimeMillis();
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(e -> e.getValue() <= now);
    }

    int size() { return tokens.size(); }
}
