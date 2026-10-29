package com.kidzpos.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import com.kidzpos.sync.SyncApiKeyFilter;
import com.kidzpos.sync.SyncRateLimitFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter,
                                           LoginRateLimitFilter loginRateLimitFilter,
                                           RefreshRateLimitFilter refreshRateLimitFilter,
                                           SyncApiKeyFilter syncApiKeyFilter,
                                           SyncRateLimitFilter syncRateLimitFilter) throws Exception {
        http
            .csrf(c -> c.disable())
            .cors(c -> {})
            // ─── PR http-hardening — security headers ─────────────────────────
            // Le backend sert UNIQUEMENT du JSON (/api/**) + spec OpenAPI + actuator.
            // Pas de HTML, pas de scripts à exécuter côté navigateur. CSP très stricte
            // (default-src 'none') = défense en profondeur contre l'embedding tiers.
            // Le frontend dist/ est servi par un serveur statique séparé (start-server.sh),
            // ses propres headers CSP/HSTS sont à configurer côté reverse-proxy en prod.
            //
            //  - HSTS : envoyé uniquement quand request.isSecure() (Spring default).
            //    LAN HTTP : pas d'effet. Derrière Caddy HTTPS : 1 an + includeSubDomains.
            //  - X-Frame-Options: DENY + CSP frame-ancestors 'none' : anti-clickjacking
            //    (double ceinture pour les navigateurs anciens qui ignorent CSP).
            //  - X-Content-Type-Options: nosniff (par défaut Spring) : empêche le MIME
            //    sniffing qui pourrait interpréter du JSON comme du script.
            //  - Referrer-Policy: strict-origin-when-cross-origin : pas de leak d'URL
            //    complète (avec query params type ?storeId=) vers les origines tierces.
            .headers(h -> h
                .frameOptions(f -> f.deny())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))   // 1 an
                .referrerPolicy(r -> r.policy(
                    ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'none'; "
                    + "frame-ancestors 'none'; "
                    + "base-uri 'none'; "
                    + "form-action 'none'"))
                // X-Content-Type-Options: nosniff + Cache-Control: no-cache par défaut
                // sont activés par Spring Security → on les conserve implicitement.
            )
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(
                    "/api/auth/**",
                    "/actuator/health",
                    // /actuator/prometheus est ouvert pour scraping. En prod : restreindre
                    // au CIDR du Prometheus via le reverse-proxy (Caddy/nginx).
                    "/actuator/prometheus",
                    "/api/events/stream",
                    // springdoc-openapi : spec OpenAPI 3 consommée par
                    // openapi-typescript côté frontend (cf scripts/gen-api-types.mjs).
                    // Contenu = structure des controllers + DTOs, pas de donnée client.
                    // En prod publique, restreindre via reverse-proxy au CIDR du build runner.
                    "/v3/api-docs",
                    "/v3/api-docs/**"
                ).permitAll()
                // M4 : /api/exchange/refresh déclenche un appel HTTP sortant ⇒ exiger une auth
                // pour éviter qu'un client non autorisé sur le LAN ne spamme l'API externe.
                // Matcher explicite (identique au défaut anyRequest().authenticated() ci-dessous,
                // mais rend l'intention de sécurité auto-documentée dans la chaîne).
                .requestMatchers(HttpMethod.POST, "/api/exchange/refresh").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/users/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/users/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/users/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/settings/**").hasRole("ADMIN")
                // I3 : seules les actions destructrices/sensibles sont ADMIN-only.
                // Création produit/client : autorisée à tout authentifié (caissier).
                .requestMatchers(HttpMethod.DELETE, "/api/products/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/customers/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/stores/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/stores/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/stores/**").hasRole("ADMIN")
                // T15 — /api/sync/status retourne lag + pendingCount au frontend
                // (OfflineBanner badge). Lecture seule, aucun risque, doit être
                // accessible à toute caisse authentifiée. AVANT le matcher ADMIN
                // pour shadowing correct.
                .requestMatchers(HttpMethod.GET, "/api/sync/status").authenticated()
                // ETAPE 4 — endpoints de synchronisation local ↔ central.
                // Stubs aujourd'hui ; destinés à être appelés par un démon, pas par les caissiers.
                .requestMatchers("/api/sync/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            // Les deux filtres sont placés avant le filtre canonique Spring Security
            // UsernamePasswordAuthenticationFilter (on ne peut pas référencer un filtre
            // custom comme cible). L'ordre relatif entre eux n'importe pas pour la
            // correction : rate-limit ne s'active que sur POST /api/auth/login (qui n'a
            // pas de header Authorization), et jwt ne s'active qu'avec un header.
            .addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(refreshRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            // SyncRateLimitFilter AVANT JWT/SyncApiKey : on rate-limit même les
            // requêtes non-authentifiées (un attaquant non-auth peut DoS le matcher).
            // Coût négligeable (1 ConcurrentHashMap lookup).
            .addFilterBefore(syncRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            // SyncApiKeyFilter APRÈS JwtAuthFilter : si un appel arrive avec un JWT
            // valide, le contexte est déjà peuplé et le filtre ne fait rien. Sinon
            // (cas server-to-server), il pose ROLE_ADMIN après validation du header.
            .addFilterAfter(syncApiKeyFilter, JwtAuthFilter.class);
        return http.build();
    }
}
