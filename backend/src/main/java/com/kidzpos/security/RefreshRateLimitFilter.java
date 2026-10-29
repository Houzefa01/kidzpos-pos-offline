package com.kidzpos.security;

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
 * P2.2 — Rate limit sur POST /api/auth/refresh.
 *
 * Fenêtre glissante configurable (défaut 1 minute / 10 échecs 401 par IP).
 * Comme pour le filtre login, seules les requêtes refusées consomment le quota
 * — un client légitime qui refresh toutes les 15 minutes ne le voit jamais.
 *
 * <p>T5 : calibrable via env :
 * <pre>
 *   KIDZPOS_RATELIMIT_REFRESH_LIMIT=20
 *   KIDZPOS_RATELIMIT_REFRESH_WINDOW_MS=120000
 * </pre>
 *
 * In-memory mono-instance : suffit pour un backend unique ; à porter sur Redis
 * si on passe à plusieurs instances (même remarque que LoginRateLimitFilter).
 *
 * Derrière un reverse-proxy (Caddy/nginx), `req.getRemoteAddr()` retourne l'IP
 * réelle grâce à `server.forward-headers-strategy: NATIVE` (cf application.yml).
 */
@Component
public class RefreshRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RefreshRateLimitFilter.class);

    private static final String PATH = "/api/auth/refresh";

    private final int limit;
    private final long windowMs;

    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public RefreshRateLimitFilter(
            @Value("${kidzpos.ratelimit.refresh.limit:10}") int limit,
            @Value("${kidzpos.ratelimit.refresh.window-ms:60000}") long windowMs) {
        this.limit = Math.max(1, limit);
        this.windowMs = Math.max(1000L, windowMs);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        if (!isRefreshPost(req)) {
            chain.doFilter(req, res);
            return;
        }

        String ip = req.getRemoteAddr();
        long now = System.currentTimeMillis();
        Deque<Long> q = attempts.computeIfAbsent(ip, k -> new ArrayDeque<>());

        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
            if (q.isEmpty()) {
                attempts.remove(ip, q);
            } else if (q.size() >= limit) {
                long retryAfter = Math.max(1, (q.peekFirst() + windowMs - now) / 1000);
                log.warn("Refresh rate-limit dépassé pour IP {} ({}s restantes)", ip, retryAfter);
                res.setStatus(429);
                res.setHeader("Retry-After", String.valueOf(retryAfter));
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"Trop de tentatives, réessayez dans " + retryAfter + "s\"}");
                return;
            }
        }

        chain.doFilter(req, res);

        // Seuls les échecs d'authentification (401) consomment le quota.
        if (res.getStatus() == 401) {
            Deque<Long> q2 = attempts.computeIfAbsent(ip, k -> new ArrayDeque<>());
            synchronized (q2) { q2.addLast(System.currentTimeMillis()); }
        }
    }

    private boolean isRefreshPost(HttpServletRequest req) {
        return "POST".equalsIgnoreCase(req.getMethod()) && PATH.equals(req.getRequestURI());
    }
}
