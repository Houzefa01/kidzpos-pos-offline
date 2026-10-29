package com.kidzpos.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

@Component
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    /** Taille minimale de la clé HMAC-SHA256 en octets (RFC 7518 §3.2). */
    private static final int MIN_SECRET_BYTES = 32;

    public JwtService(@Value("${kidzpos.jwt.secret:}") String secret,
                      @Value("${kidzpos.jwt.expiration-hours:0}") long hours,
                      @Value("${kidzpos.auth.access-token-minutes:0}") long minutes) {
        // P1.2 : fail-fast au démarrage si le secret est manquant ou trop court.
        // Plus de défaut dans application.yml — un déploiement sans JWT_SECRET
        // exporté refuse de démarrer (mieux qu'un défaut connu qui ferait passer
        // des tokens forgeables en production).
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET non défini. Exporter une chaîne aléatoire ≥ "
                    + MIN_SECRET_BYTES + " octets avant de démarrer "
                    + "(ex: export JWT_SECRET=\"$(openssl rand -base64 48)\").");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET trop court (" + keyBytes.length + " octets) — minimum "
                    + MIN_SECRET_BYTES + " octets requis pour HMAC-SHA256. "
                    + "Régénérer avec : openssl rand -base64 48");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        // Préfère access-token-minutes (P2). Fallback expiration-hours pour rétrocompat
        // avec d'anciens .env. Si rien → 15 min (recommandation P2).
        long ms = minutes > 0 ? minutes * 60_000L
                : hours > 0 ? hours * 3600_000L
                : 15 * 60_000L;
        this.expirationMs = ms;
    }

    public String generate(String userId, String email, String role, String storeId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId)
                .claims(Map.of(
                        "email", email,
                        "role", role,
                        "storeId", storeId == null ? "" : storeId
                ))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
