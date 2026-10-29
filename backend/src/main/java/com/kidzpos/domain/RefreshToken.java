package com.kidzpos.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Refresh token persisté pour rotation et révocation.
 *
 * Le client porte le token EN CLAIR dans un cookie httpOnly ; seule la valeur
 * hashée (SHA-256) est en base. Lookup au /refresh : SHA-256(cookie) → ligne.
 *
 * Rotation : à chaque usage, on émet un nouveau token et on marque l'ancien
 * revoked_at + replaced_by. Présenter un token déjà revoked_at = signe de vol
 * → invalidation en cascade pour l'user (cf RefreshTokenService).
 */
@Entity @Table(name = "refresh_tokens")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RefreshToken {
    @Id
    private String id;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private String replacedBy;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "ip", length = 64)
    private String ip;
}
