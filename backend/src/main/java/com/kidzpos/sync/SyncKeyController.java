package com.kidzpos.sync;

import com.kidzpos.domain.SyncApiKey;
import com.kidzpos.repo.SyncApiKeyRepository;
import com.kidzpos.sync.SyncApiKeyService.CreatedKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gestion des clés API par magasin. Tout est admin-only (cf SecurityConfig :
 * /api/sync/** hasRole("ADMIN")). Aucune route GET ne ré-expose le secret en
 * clair — il n'est retourné qu'à la création.
 *
 * Workflow type :
 *  1. ADMIN connecte au central : POST /api/sync/keys {storeId, label}
 *     → response {id, storeId, plaintext: "abc...xyz"} ← À COPIER
 *  2. Configurer le store : KIDZPOS_SYNC_API_KEY=abc...xyz + redéploiement
 *  3. Vérifier : POST /api/sync/push depuis le store doit fonctionner
 *  4. Une fois confirmé : DELETE /api/sync/keys/{ancienId} pour révoquer
 *
 * Backward compat : tant qu'aucune clé n'existe en DB, le filter accepte la
 * clé partagée historique (cf SyncApiKeyFilter).
 */
@RestController
@RequestMapping("/api/sync/keys")
public class SyncKeyController {

    private static final Logger log = LoggerFactory.getLogger(SyncKeyController.class);

    private final SyncApiKeyService service;
    private final SyncApiKeyRepository repo;

    public SyncKeyController(SyncApiKeyService service, SyncApiKeyRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    /**
     * Crée une nouvelle clé. Le {@code plaintext} retourné n'est PAS persisté
     * en clair et n'apparaîtra dans aucun GET subséquent.
     */
    @PostMapping
    public ResponseEntity<CreatedKeyResponse> create(@Valid @RequestBody CreateReq req) {
        CreatedKey k = service.create(req.storeId(), req.label());
        log.info("[sync-keys] created id={} storeId={} label={}",
                k.id(), k.storeId(), k.label());
        return ResponseEntity.ok(new CreatedKeyResponse(
                k.id(), k.storeId(), k.label(), k.createdAt(), k.plaintext()));
    }

    /** Liste les clés actives (toutes stores). Le hash n'est PAS exposé. */
    @GetMapping
    public ResponseEntity<List<KeySummary>> list() {
        var keys = repo.findByRevokedAtIsNullOrderByStoreIdAscCreatedAtDesc();
        return ResponseEntity.ok(keys.stream().map(KeySummary::of).toList());
    }

    /** Liste les clés (actives ET révoquées) pour un store précis — audit. */
    @GetMapping("/store/{storeId}")
    public ResponseEntity<List<KeySummary>> listByStore(@PathVariable String storeId) {
        var keys = repo.findByStoreIdOrderByCreatedAtDesc(storeId);
        return ResponseEntity.ok(keys.stream().map(KeySummary::of).toList());
    }

    /** Révoque par id. 404 si introuvable, 200 même si déjà révoquée (idempotent). */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> revoke(@PathVariable UUID id) {
        if (repo.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        boolean changed = service.revoke(id);
        log.info("[sync-keys] revoke id={} changed={}", id, changed);
        return ResponseEntity.ok(Map.of("revoked", changed));
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    public record CreateReq(@NotBlank String storeId, String label) {}

    /**
     * Résumé exposé par GET. PAS de keyHash, PAS de plaintext.
     * Le plaintext n'est lisible que dans CreatedKeyResponse, à la création.
     */
    public record KeySummary(UUID id, String storeId, String label,
                             Instant createdAt, Instant revokedAt, Instant lastUsedAt) {
        static KeySummary of(SyncApiKey k) {
            return new KeySummary(k.getId(), k.getStoreId(), k.getLabel(),
                    k.getCreatedAt(), k.getRevokedAt(), k.getLastUsedAt());
        }
    }

    public record CreatedKeyResponse(UUID id, String storeId, String label,
                                     Instant createdAt, String plaintext) {}
}
