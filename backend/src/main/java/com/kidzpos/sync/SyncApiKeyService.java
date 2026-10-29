package com.kidzpos.sync;

import com.kidzpos.domain.SyncApiKey;
import com.kidzpos.repo.SyncApiKeyRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Génération + validation des clés API par magasin.
 *
 * Format de clé : 32 octets aléatoires, encodés en base64url SANS padding
 * (43 chars). Entropie ≈ 256 bits — résiste à n'importe quel brute-force.
 *
 * Stockage : SHA-256 hex du plaintext. Pas de BCrypt :
 *   - La clé est déjà entropique (pas un password user faible)
 *   - Le filter doit valider à chaque requête sync → ne peut pas se permettre
 *     les 100ms de BCrypt
 *   - SHA-256 + entropie 256 bits = secret indistinguable d'un random
 *
 * Le secret en clair est retourné UNE SEULE FOIS à la création, jamais
 * persisté ni reloggué. Si l'admin perd la copie → générer une nouvelle clé
 * et révoquer l'ancienne.
 */
@Service
public class SyncApiKeyService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int KEY_BYTES = 32;     // 256 bits

    private final SyncApiKeyRepository repo;

    public SyncApiKeyService(SyncApiKeyRepository repo) {
        this.repo = repo;
    }

    /**
     * Crée une nouvelle clé pour {@code storeId}. Retourne le secret en clair —
     * À COPIER IMMÉDIATEMENT (jamais accessible ensuite).
     */
    public CreatedKey create(String storeId, String label) {
        String plaintext = generateSecret();
        String hash = sha256Hex(plaintext);
        SyncApiKey row = repo.save(SyncApiKey.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .keyHash(hash)
                .label(label)
                .createdAt(Instant.now())
                .build());
        return new CreatedKey(row.getId(), row.getStoreId(), row.getLabel(),
                row.getCreatedAt(), plaintext);
    }

    /**
     * Valide une clé en clair. Retourne le row correspondant ssi le hash match
     * ET la clé n'est pas révoquée. Met à jour {@code lastUsedAt} en best-effort.
     */
    public Optional<SyncApiKey> validate(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return Optional.empty();
        String hash = sha256Hex(plaintext);
        Optional<SyncApiKey> found = repo.findFirstByKeyHashAndRevokedAtIsNull(hash);
        found.ifPresent(k -> {
            // Touch lastUsedAt — best-effort, on swallow toute exception (le filter
            // ne doit pas échouer parce que l'audit a hoqueté).
            try {
                k.setLastUsedAt(Instant.now());
                repo.save(k);
            } catch (Exception ignored) {
                // Volontairement silencieux : audit-only.
            }
        });
        return found;
    }

    /** Révoque par id. Idempotent : ré-révoquer une clé déjà révoquée est no-op. */
    public boolean revoke(UUID id) {
        return repo.findById(id).map(k -> {
            if (k.getRevokedAt() != null) return false;
            k.setRevokedAt(Instant.now());
            repo.save(k);
            return true;
        }).orElse(false);
    }

    /** True ssi au moins une clé active existe → bascule SyncApiKeyFilter en mode per-store. */
    public boolean hasAnyActiveKey() {
        return repo.existsByRevokedAtIsNull();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String generateSecret() {
        byte[] bytes = new byte[KEY_BYTES];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 du UTF-8 du plaintext → hex 64 chars. */
    static String sha256Hex(String plaintext) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 est requis par la JLS — impossible en pratique.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Réponse de création — le {@code plaintext} ne réapparaîtra JAMAIS dans
     * un GET. L'admin doit le copier immédiatement.
     */
    public record CreatedKey(UUID id, String storeId, String label,
                             Instant createdAt, String plaintext) {}
}
