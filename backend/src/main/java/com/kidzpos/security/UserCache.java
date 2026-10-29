package com.kidzpos.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.kidzpos.domain.User;
import com.kidzpos.repo.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache mémoire des entités {@link User} sur le hot path d'authentification.
 *
 * Pourquoi : {@link JwtAuthFilter#doFilterInternal} lisait la table users à
 * CHAQUE requête authentifiée (SELECT users WHERE id = ?). À 100 req/s, ça
 * faisait 100 lectures DB/s constantes sur une donnée qui change rarement.
 *
 * TTL court (30 s par défaut) : compromis entre cache hit ratio et fraîcheur.
 * Le TTL borne la fenêtre maximale pendant laquelle une modification user
 * (toggle actif, change role, change storeId) reste invisible aux requêtes
 * authentifiées. En pratique, on invalide aussi explicitement depuis
 * {@code UserController} (update + delete) → la fenêtre stale est ~0 dans
 * le cas nominal mono-instance.
 *
 * Multi-instance : l'invalidation explicite est locale à la JVM. Si N
 * backends derrière LB, une modif sur instance A reste cachée jusqu'à
 * TTL sur instance B. Acceptable (TTL court) ; portage Redis si besoin.
 *
 * Sémantique cache :
 *  - {@link Optional#empty()} = user inconnu (cache négatif pour absorber
 *    les tokens valides pointant vers un user supprimé entre 2 requêtes,
 *    sans re-query la DB à chaque fois).
 */
@Component
public class UserCache {

    private final UserRepository repo;
    private final Cache<String, Optional<User>> cache;

    public UserCache(UserRepository repo,
                     @Value("${kidzpos.auth.user-cache-seconds:30}") long ttlSeconds,
                     @Value("${kidzpos.auth.user-cache-size:1000}") long maxSize,
                     MeterRegistry meterRegistry) {
        this.repo = repo;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofSeconds(Math.max(1, ttlSeconds)))
                .recordStats()
                .build();
        // Expose hit_count / miss_count / hit_ratio via Micrometer Gauges.
        // La table users tient quelques dizaines d'entrées max — pas de risque
        // d'explosion mémoire ; le maxSize 1000 est un garde-fou ultime.
        meterRegistry.gauge("kidzpos.auth.user_cache.hits", cache, c -> c.stats().hitCount());
        meterRegistry.gauge("kidzpos.auth.user_cache.misses", cache, c -> c.stats().missCount());
        meterRegistry.gauge("kidzpos.auth.user_cache.size", cache, c -> (double) c.estimatedSize());
    }

    /**
     * Retourne l'utilisateur ou {@link Optional#empty()}. Cache hit ou
     * delegate à {@link UserRepository#findById}. Stocke aussi les misses
     * pour absorber les tokens orphelins.
     */
    public Optional<User> findById(String userId) {
        if (userId == null) return Optional.empty();
        return cache.get(userId, repo::findById);
    }

    /** À appeler après toute mutation user (update, delete, toggle). */
    public void invalidate(String userId) {
        if (userId != null) cache.invalidate(userId);
    }

    /** Reset complet — utilisé en test, ou opérations admin bulk. */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    /** Exposé pour tests / debug. Pas d'usage production. */
    CacheStats stats() {
        return cache.stats();
    }
}
