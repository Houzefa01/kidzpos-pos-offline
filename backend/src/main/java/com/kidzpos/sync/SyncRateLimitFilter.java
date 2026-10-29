package com.kidzpos.sync;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limit sur /api/sync/** (push + pull).
 *
 * <p>Fenêtre glissante configurable (défaut 60s / 120 requêtes). Limite par
 * <em>store</em> quand le header {@link SyncApiKeyFilter#HEADER_STORE} est
 * présent (mode per-store V21), sinon par IP (legacy ou JWT ADMIN manuel).
 *
 * <p>Un store qui pousse à intervalle 30s avec batch 200 ops reste
 * confortablement sous le quota (~2 req/min). Le quota protège contre :
 * <ul>
 *   <li>Un store compromis qui spammerait push à débit max.</li>
 *   <li>Un client buggé en boucle de retry serré.</li>
 *   <li>Un script de test laissé tourner accidentellement en prod.</li>
 * </ul>
 *
 * <p>Toutes les requêtes (succès + erreur) consomment le quota — contrairement
 * à login/refresh qui ne comptent que les 401, parce qu'ici on protège la
 * ressource elle-même, pas un endpoint de credentials.
 *
 * <p>In-memory mono-instance. À porter sur Redis si on passe à plusieurs
 * backends centraux (même remarque que LoginRateLimitFilter).
 */
@Component
public class SyncRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SyncRateLimitFilter.class);
    private static final String PATH_PREFIX = "/api/sync/";

    private final int limit;
    private final long windowMs;

    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public SyncRateLimitFilter(
            @Value("${kidzpos.ratelimit.sync.limit:120}") int limit,
            @Value("${kidzpos.ratelimit.sync.window-ms:60000}") long windowMs) {
        this.limit = Math.max(1, limit);
        this.windowMs = Math.max(1000L, windowMs);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String path = req.getRequestURI();
        if (path == null || !path.startsWith(PATH_PREFIX)) {
            chain.doFilter(req, res);
            return;
        }

        String key = bucketKey(req);
        long now = System.currentTimeMillis();
        Deque<Long> q = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
            if (q.size() >= limit) {
                long retryAfter = Math.max(1, (q.peekFirst() + windowMs - now) / 1000);
                log.warn("[sync] rate-limit hit key={} path={} ({}s restantes, limit={}/{}s)",
                        key, path, retryAfter, limit, windowMs / 1000);
                res.setStatus(429);
                res.setHeader("Retry-After", String.valueOf(retryAfter));
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"sync rate limit exceeded, retry in " + retryAfter + "s\"}");
                return;
            }
            q.addLast(now);
        }

        chain.doFilter(req, res);
    }

    /**
     * Clé d'agrégation du quota :
     * <ul>
     *   <li>Header {@code X-Sync-Store-Id} présent → {@code "store:<id>"} (per-store V21).</li>
     *   <li>Sinon → {@code "ip:<addr>"} (legacy + JWT ADMIN).</li>
     * </ul>
     * Un store ne peut pas bypass son quota en omettant le header — sans header,
     * le filter {@link SyncApiKeyFilter} en mode per-store refuse déjà la requête.
     */
    private static String bucketKey(HttpServletRequest req) {
        String store = req.getHeader(SyncApiKeyFilter.HEADER_STORE);
        if (store != null && !store.isBlank()) return "store:" + store;
        return "ip:" + req.getRemoteAddr();
    }
}
