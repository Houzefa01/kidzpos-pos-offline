package com.kidzpos.observability;

import com.kidzpos.security.JwtAuthFilter.AuthPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Injecte un correlationId par requête dans le MDC pour les logs structurés.
 * Si le client envoie X-Request-Id, on le respecte (utile pour tracer depuis le front).
 * Sinon UUID généré côté serveur. Renvoyé en réponse pour que le client puisse le logger.
 *
 * Pose aussi userId/storeId post-authentification — utile pour grep multi-caisse.
 *
 * Ordre : APRÈS JwtAuthFilter (sinon principal pas encore peuplé) mais avant les controllers.
 * On utilise un ordre élevé (HIGHEST_PRECEDENCE + 10) qui est avant la plupart des filtres
 * Spring et postpone la lecture du principal au moment où le filterChain le résout.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_CORRELATION = "correlationId";
    public static final String MDC_USER = "userId";
    public static final String MDC_STORE = "storeId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String cid = req.getHeader(HEADER);
        if (cid == null || cid.isBlank() || cid.length() > 64) {
            cid = UUID.randomUUID().toString();
        }
        MDC.put(MDC_CORRELATION, cid);
        res.setHeader(HEADER, cid);
        try {
            chain.doFilter(req, res);
            // Après auth filter, le principal est posé : on enrichit le MDC pour les logs
            // émis pendant le rendu de la réponse (peu, mais utile).
            populateFromPrincipal();
        } finally {
            MDC.remove(MDC_CORRELATION);
            MDC.remove(MDC_USER);
            MDC.remove(MDC_STORE);
        }
    }

    private void populateFromPrincipal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal p)) return;
        if (p.id() != null) MDC.put(MDC_USER, p.id());
        if (p.storeId() != null) MDC.put(MDC_STORE, p.storeId());
    }
}
