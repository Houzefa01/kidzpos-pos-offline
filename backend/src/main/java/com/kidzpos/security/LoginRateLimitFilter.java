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
 * M3 : limite les tentatives de login par IP. Fenêtre glissante configurable
 * (défaut 5 minutes / 5 échecs). In-memory (pas de Redis) — suffisant pour un
 * déploiement LAN mono-backend ; à reprendre si on passe à plusieurs instances.
 *
 * Seules les réponses 401 (mauvais mot de passe) consomment le quota — un login
 * réussi n'entame pas le compteur, et une 5xx non plus.
 *
 * <p>T5 : calibrable via env sans recompile :
 * <pre>
 *   KIDZPOS_RATELIMIT_LOGIN_LIMIT=10
 *   KIDZPOS_RATELIMIT_LOGIN_WINDOW_MS=600000
 * </pre>
 */
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);

    private static final String LOGIN_PATH = "/api/auth/login";

    private final int limit;
    private final long windowMs;

    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public LoginRateLimitFilter(
            @Value("${kidzpos.ratelimit.login.limit:5}") int limit,
            @Value("${kidzpos.ratelimit.login.window-ms:300000}") long windowMs) {
        this.limit = Math.max(1, limit);
        this.windowMs = Math.max(1000L, windowMs);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        if (!isLoginPost(req)) {
            chain.doFilter(req, res);
            return;
        }

        String ip = clientIp(req);
        long now = System.currentTimeMillis();
        Deque<Long> q = attempts.computeIfAbsent(ip, k -> new ArrayDeque<>());

        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
            if (q.isEmpty()) {
                attempts.remove(ip, q);   // libère la map quand l'IP redevient saine
            } else if (q.size() >= limit) {
                long retryAfter = Math.max(1, (q.peekFirst() + windowMs - now) / 1000);
                log.warn("Login rate-limit dépassé pour IP {} ({}s restantes)", ip, retryAfter);
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

    private boolean isLoginPost(HttpServletRequest req) {
        return "POST".equalsIgnoreCase(req.getMethod()) && LOGIN_PATH.equals(req.getRequestURI());
    }

    /**
     * En LAN direct, getRemoteAddr() suffit. Derrière un reverse-proxy (Caddy/nginx)
     * il faudrait lire X-Forwarded-For, mais alors le proxy doit être configuré pour
     * écraser ce header sinon le client peut le forger.
     */
    private String clientIp(HttpServletRequest req) {
        return req.getRemoteAddr();
    }
}
