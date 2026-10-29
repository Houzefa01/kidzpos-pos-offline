package com.kidzpos.sync;

import com.kidzpos.domain.SyncApiKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * Authentification server-to-server pour /api/sync/**.
 *
 * <h3>Deux modes d'authentification</h3>
 *
 * <h4>1. Per-store (V21+, mode privilégié)</h4>
 * Le store envoie {@code X-Sync-Api-Key} ET {@code X-Sync-Store-Id}.
 * Le filter lookup en DB via {@link SyncApiKeyService} :
 *   - hash match + clé non révoquée → ROLE_ADMIN posé avec storeId
 *     comme principal {@link SyncPrincipal#storeId()}.
 *   - storeId du header DOIT match le storeId associé à la clé en DB.
 *
 * Bénéfices : révocation ciblée, audit (last_used_at), cross-validation
 * dans {@link SyncController#push} (un store ne peut push QUE ses ops).
 *
 * <h4>2. Clé partagée legacy (deprecated mais préservée)</h4>
 * Tant qu'aucune clé per-store n'existe en DB (V21) et que
 * {@code kidzpos.sync.inbound.api-key} est configuré, l'ancien mode reste
 * actif : header {@code X-Sync-Api-Key} comparé en temps constant → ROLE_ADMIN
 * avec principal "sync-client" (pas de storeId, pas de cross-validation).
 *
 * <h3>Ordre des règles</h3>
 * <ol>
 *   <li>Authentification JWT déjà posée → on ne touche pas.</li>
 *   <li>Au moins une clé per-store active en DB → mode per-store strict.</li>
 *   <li>Sinon, fallback legacy.</li>
 * </ol>
 *
 * Comparaison en temps constant via {@link MessageDigest#isEqual(byte[], byte[])}.
 */
@Component
public class SyncApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER_KEY = "X-Sync-Api-Key";
    public static final String HEADER_STORE = "X-Sync-Store-Id";
    private static final String PATH_PREFIX = "/api/sync/";

    private static final Logger log = LoggerFactory.getLogger(SyncApiKeyFilter.class);

    /** Clé partagée legacy (pré-V21). Vide → mode legacy désactivé. */
    private final String legacyKey;
    private final SyncApiKeyService keyService;

    public SyncApiKeyFilter(@Value("${kidzpos.sync.inbound.api-key:}") String legacyKey,
                            SyncApiKeyService keyService) {
        this.legacyKey = legacyKey == null ? "" : legacyKey;
        this.keyService = keyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String path = req.getRequestURI();
        if (path == null || !path.startsWith(PATH_PREFIX)) {
            chain.doFilter(req, res);
            return;
        }

        // Déjà authentifié par JWT → ne pas écraser.
        var existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated()) {
            chain.doFilter(req, res);
            return;
        }

        String providedKey = req.getHeader(HEADER_KEY);
        String providedStore = req.getHeader(HEADER_STORE);

        // ── Mode per-store (V21+) ─────────────────────────────────────────
        // On bascule en per-store STRICT dès qu'au moins une clé existe en DB.
        // Ça évite que la legacy redevienne acceptable une fois la migration
        // commencée (sinon un attaquant pourrait deviner la legacy ET bypasser
        // la révocation des clés per-store).
        if (keyService.hasAnyActiveKey()) {
            if (providedKey != null && providedStore != null && !providedStore.isBlank()) {
                Optional<SyncApiKey> match = keyService.validate(providedKey);
                if (match.isPresent() && providedStore.equals(match.get().getStoreId())) {
                    authenticateAsStore(match.get().getStoreId());
                    log.debug("[sync] per-store API-KEY accepted store={} path={}",
                            match.get().getStoreId(), path);
                    chain.doFilter(req, res);
                    return;
                }
                log.warn("[sync] per-store API-KEY mismatch on {} from {} (store-header={}, key-valid={}, store-match={})",
                        path, req.getRemoteAddr(), providedStore,
                        match.isPresent(),
                        match.map(k -> providedStore.equals(k.getStoreId())).orElse(false));
            } else if (providedKey != null) {
                // Mode per-store actif mais header store manquant — legacy n'est plus accepté.
                log.warn("[sync] per-store mode active but X-Sync-Store-Id missing on {} from {}",
                        path, req.getRemoteAddr());
            }
            // Pas de short-circuit : on laisse Spring Security rendre le 401/403.
            chain.doFilter(req, res);
            return;
        }

        // ── Mode legacy (pré-V21) ─────────────────────────────────────────
        if (legacyKey.isBlank()) {
            // Aucune clé legacy configurée ET aucune clé per-store en DB :
            // seul ROLE_ADMIN via JWT passera (comportement initial).
            chain.doFilter(req, res);
            return;
        }
        if (providedKey != null && constantTimeEquals(providedKey, legacyKey)) {
            authenticateAsLegacy();
            log.debug("[sync] legacy shared API-KEY accepted on {} (TODO: migrate to per-store keys)", path);
        } else if (providedKey != null) {
            log.warn("[sync] legacy API-KEY mismatch on {} from {} (header present, value rejected)",
                    path, req.getRemoteAddr());
        }

        chain.doFilter(req, res);
    }

    private void authenticateAsStore(String storeId) {
        var auth = new UsernamePasswordAuthenticationToken(
                new SyncPrincipal(storeId, /* perStore */ true),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void authenticateAsLegacy() {
        var auth = new UsernamePasswordAuthenticationToken(
                new SyncPrincipal(null, /* perStore */ false),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ba, bb);
    }

    /**
     * Identité du caller authentifié par API key.
     *
     * @param storeId    storeId associé à la clé (mode per-store) ou {@code null} (legacy)
     * @param perStore   {@code true} = mode per-store (V21+) ; {@code false} = clé partagée
     */
    public record SyncPrincipal(String storeId, boolean perStore) {}
}
