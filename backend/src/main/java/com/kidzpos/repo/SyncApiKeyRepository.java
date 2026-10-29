package com.kidzpos.repo;

import com.kidzpos.domain.SyncApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accès aux clés API par magasin. Cf {@link SyncApiKey} et migration V21.
 *
 * NB : les filtres "active" reposent sur le prédicat {@code revokedAt IS NULL}
 * — appuyés par les index partiels {@code idx_sync_api_keys_*_active}.
 */
public interface SyncApiKeyRepository extends JpaRepository<SyncApiKey, UUID> {

    /** Lookup au filter (chemin chaud). Index partiel sur key_hash WHERE revoked_at IS NULL. */
    Optional<SyncApiKey> findFirstByKeyHashAndRevokedAtIsNull(String keyHash);

    /** Listing admin par store, plus récente d'abord. */
    List<SyncApiKey> findByStoreIdOrderByCreatedAtDesc(String storeId);

    /** Listing global non révoquées pour {@code GET /api/sync/keys}. */
    List<SyncApiKey> findByRevokedAtIsNullOrderByStoreIdAscCreatedAtDesc();

    /** True ssi au moins une clé active existe — bascule le filtre en mode per-store. */
    boolean existsByRevokedAtIsNull();
}
