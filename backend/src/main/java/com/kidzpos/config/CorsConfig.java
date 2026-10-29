package com.kidzpos.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    @Value("${kidzpos.cors.allowed-origins}")
    private String allowedOrigins;

    /**
     * Logue au démarrage un récapitulatif des origines acceptées + WARN explicite
     * si la conf contient des wildcards. Permet de détecter à la lecture des logs
     * un déploiement prod laissé sur la valeur de défaut (patterns LAN larges).
     */
    @PostConstruct
    void warnIfWildcardCors() {
        boolean hasWildcard = "*".equals(allowedOrigins)
                || Arrays.stream(allowedOrigins.split(",")).map(String::trim).anyMatch(s -> s.contains("*"));
        if (hasWildcard) {
            log.warn("CORS contient des wildcards ({}). OK en LAN ; pour une mise en prod publique HTTPS, "
                    + "exporter CORS_ALLOWED_ORIGINS=https://votre-domaine sans astérisque.", allowedOrigins);
        } else {
            log.info("CORS allowed-origins: {}", allowedOrigins);
        }
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration cfg = new CorsConfiguration();
        
        // Security: Never use "*" with allowCredentials=true
        // Always require explicit allowed origins in production
        if ("*".equals(allowedOrigins)) {
            cfg.addAllowedOriginPattern("*");
            cfg.setAllowCredentials(false);  // FIX: Set to false when using wildcard
        } else {
            for (String o : allowedOrigins.split(",")) {
                String origin = o.trim();
                if (origin.contains("*")) {
                    cfg.addAllowedOriginPattern(origin);
                } else {
                    cfg.addAllowedOrigin(origin);
                }
            }
            cfg.setAllowCredentials(true);  // OK: Only when using specific origins
        }
        
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return new CorsFilter(src);
    }
}

