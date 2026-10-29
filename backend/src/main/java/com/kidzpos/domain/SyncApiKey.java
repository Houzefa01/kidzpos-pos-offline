package com.kidzpos.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Clé API par magasin pour authentifier les appels server-to-server sur
 * {@code /api/sync/**}. Cf migration V21.
 *
 * Le secret en clair n'est JAMAIS persisté — seul son SHA-256 hex est stocké.
 * Retourné une seule fois à la création (POST /api/sync/keys) puis perdu.
 *
 * Une clé est dite "active" tant que {@code revokedAt IS NULL}. Les clés
 * révoquées sont conservées pour audit (qui a push quoi, quand). On peut
 * avoir plusieurs clés actives par store (rotation : créer la nouvelle,
 * déployer, révoquer l'ancienne).
 */
@Entity
@Table(name = "sync_api_keys")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SyncApiKey {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "store_id", nullable = false, length = 64)
    private String storeId;

    @Column(name = "key_hash", nullable = false, length = 128)
    private String keyHash;

    @Column(length = 255)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** {@code NULL} = clé active. Sinon : timestamp de la révocation. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Horodatage du dernier usage authentifié — utile pour détecter les clés "mortes". */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;
}
