package com.kidzpos.web;

import com.kidzpos.domain.Product;
import com.kidzpos.dto.Dtos.ProductReq;
import com.kidzpos.events.EventBus;
import com.kidzpos.observability.BusinessMetrics;
import com.kidzpos.repo.ProductRepository;
import com.kidzpos.security.JwtAuthFilter.AuthPrincipal;
import com.kidzpos.security.StoreAccessGuard;
import com.kidzpos.sync.NodeContext;
import com.kidzpos.sync.OperationLogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductRepository repo;
    private final EventBus bus;
    private final BusinessMetrics metrics;
    private final NodeContext nodeContext;
    private final OperationLogService opLog;
    public ProductController(ProductRepository repo, EventBus bus, BusinessMetrics metrics,
                             NodeContext nodeContext, OperationLogService opLog) {
        this.repo = repo; this.bus = bus; this.metrics = metrics;
        this.nodeContext = nodeContext;
        this.opLog = opLog;
    }

    /**
     * P4 — Politique GET cross-store STRICTE : un EMPLOYEE sans storeId (=
     * demande "tous les magasins") ou avec un storeId ≠ son magasin → 403.
     * ADMIN passe partout. Pas de fallback silencieux.
     *
     * PR http-hardening — Cache HTTP : weak ETag dérivé du contenu de la
     * collection (id + version de chaque produit). Si le client repasse cet
     * ETag en If-None-Match et que rien n'a bougé → 304 Not Modified sans
     * body → ~zéro octet à transférer + le client garde son état local.
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String storeId,
                                  @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                  @AuthenticationPrincipal AuthPrincipal me) {
        var deny = StoreAccessGuard.denyIfCrossStore(me, storeId);
        if (deny != null) return deny;
        // V22 — ADMIN omnipotent (voit le storeId demandé ou tous si null) ;
        // EMPLOYEE strictement contraint à son storeId via le JWT (cf JavaDoc
        // de StoreAccessGuard#enforceStoreScope pour la politique complète).
        String scope = StoreAccessGuard.enforceStoreScope(storeId, me, nodeContext);
        var products = scope == null ? repo.findAll() : repo.findByStoreId(scope);
        String etag = collectionEtag(products);
        if (etag.equals(ifNoneMatch)) {
            // 304 doit ré-émettre l'ETag (RFC 7232 §4.1) et un body vide.
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(products);
    }

    /**
     * Weak ETag (préfixe W/) sur la collection : pas byte-exact (dépend de
     * l'ordre de findAll), mais sémantiquement stable tant que (id, version)
     * de chaque produit est inchangé. CRC32 suffit pour le hachage : on n'a
     * pas besoin de résistance aux collisions cryptographique (le pire cas
     * = false negative = on renvoie une 304 alors qu'on aurait dû renvoyer
     * 200 ; probabilité ~10⁻¹⁰ pour des catalogues < 10k items, et toute
     * mutation incrémente le version → CRC change).
     */
    private static String collectionEtag(List<Product> products) {
        CRC32 crc = new CRC32();
        for (Product p : products) {
            crc.update(p.getId().getBytes(StandardCharsets.UTF_8));
            crc.update((byte) ':');
            crc.update(String.valueOf(p.getVersion()).getBytes(StandardCharsets.UTF_8));
            crc.update((byte) '|');
        }
        return "W/\"" + products.size() + "-" + Long.toHexString(crc.getValue()) + "\"";
    }

    /** Variant GET unique : retourne ETag = version pour permettre If-Match au PUT.
     *
     *  V19 — Durcissement : si on est sur un nœud store-scoped, un produit
     *  appartenant à un autre magasin renvoie 404 (pas le contenu). Évite
     *  une fuite d'information silencieuse via ce read-by-ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Product> getOne(@PathVariable String id,
                                          @AuthenticationPrincipal AuthPrincipal me) {
        return repo.findById(id)
                .filter(p -> StoreAccessGuard.isInScope(p.getStoreId(), me, nodeContext))
                .map(p -> ResponseEntity.ok()
                        .header(HttpHeaders.ETAG, etagOf(p))
                        .body(p))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody ProductReq r, @AuthenticationPrincipal AuthPrincipal me) {
        // P1.1 (extension) : un EMPLOYEE ne peut créer un produit que dans SON magasin.
        var deny = StoreAccessGuard.denyIfCrossStore(me, r.storeId());
        if (deny != null) return deny;
        if (repo.findByStoreIdAndSkuIgnoreCase(r.storeId(), r.sku()).isPresent()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "SKU déjà utilisé dans ce magasin"));
        }
        var p = Product.builder()
                .id(r.id() != null ? r.id() : "p" + System.currentTimeMillis())
                .name(r.name()).price(r.price()).stock(r.stock())
                .storeId(r.storeId()).category(r.category()).sku(r.sku())
                .createdAt(Instant.now())
                .build();
        var saved = repo.save(p);
        opLog.record("product.created", saved);
        bus.publish("product", "created", saved);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable String id, @Valid @RequestBody ProductReq r,
                                    @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                    @AuthenticationPrincipal AuthPrincipal me) {
        var p = repo.findById(id).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();
        // P1.1 (extension) : double check sur le produit existant ET sur le storeId
        // demandé. Empêche (a) un EMPLOYEE de modifier un produit cross-store et
        // (b) un EMPLOYEE de déplacer un produit hors de son magasin (ou d'en
        // siphonner un en le ré-allouant à son magasin).
        var denyCurrent = StoreAccessGuard.denyIfCrossStore(me, p.getStoreId());
        if (denyCurrent != null) return denyCurrent;
        var denyTarget = StoreAccessGuard.denyIfCrossStore(me, r.storeId());
        if (denyTarget != null) return denyTarget;
        // Optimistic locking : si le client a lu un ETag (via GET /{id}) et le repasse
        // en If-Match, on rejette 412 si la version a changé entretemps. L'absence du
        // header reste tolérée pour rétrocompat avec clients qui ne lisent pas l'ETag.
        if (ifMatch != null && !ifMatch.isBlank()) {
            Integer expected = parseEtag(ifMatch);
            if (expected == null || !expected.equals(p.getVersion())) {
                metrics.optimisticLockConflict.increment();
                return ResponseEntity.status(412)
                        .body(Map.of("error", "Le produit a été modifié entretemps, rechargez"));
            }
        }
        if (!p.getSku().equalsIgnoreCase(r.sku())) {
            var conflict = repo.findByStoreIdAndSkuIgnoreCase(r.storeId(), r.sku());
            if (conflict.isPresent() && !conflict.get().getId().equals(id)) {
                return ResponseEntity.badRequest().body(Map.of("error", "SKU déjà utilisé"));
            }
        }
        p.setName(r.name()); p.setPrice(r.price()); p.setStock(r.stock());
        p.setStoreId(r.storeId()); p.setCategory(r.category()); p.setSku(r.sku());
        // V20-fix sync : édition catalogue → on horodate explicitement (le @PreUpdate
        // auto a été retiré pour que les changements de stock ne polluent plus le
        // updatedAt servant au LWW cross-nœud). cf Product.initUpdatedAt.
        p.setUpdatedAt(Instant.now());
        try {
            var saved = repo.saveAndFlush(p);  // flush pour déclencher la check @Version maintenant
            opLog.record("product.updated", saved);
            bus.publish("product", "updated", saved);
            return ResponseEntity.ok()
                    .header(HttpHeaders.ETAG, etagOf(saved))
                    .body(saved);
        } catch (ObjectOptimisticLockingFailureException e) {
            metrics.optimisticLockConflict.increment();
            return ResponseEntity.status(412)
                    .body(Map.of("error", "Le produit a été modifié entretemps, rechargez"));
        }
    }

    private static String etagOf(Product p) {
        return "\"" + p.getVersion() + "\"";
    }

    private static Integer parseEtag(String raw) {
        // Accepte "12" et W/"12"
        String s = raw.trim();
        if (s.startsWith("W/")) s = s.substring(2).trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id,
                                    @AuthenticationPrincipal AuthPrincipal me) {
        var p = repo.findById(id).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();
        // V19 — Garde scope : même un ADMIN sur le serveur du magasin s1 ne
        // peut pas soft-deleter un produit du magasin s2. 404 (pas 403) pour
        // ne pas révéler l'existence.
        if (!StoreAccessGuard.isInScope(p.getStoreId(), me, nodeContext)) {
            return ResponseEntity.notFound().build();
        }
        // Soft-delete : préserve l'intégrité référentielle avec sale_items.product_id
        // (pas de FK mais on garde la trace) et libère le SKU via l'index partiel.
        p.setDeletedAt(Instant.now());
        repo.save(p);
        opLog.record("product.deleted", java.util.Map.of("id", id));
        bus.publish("product", "deleted", java.util.Map.of("id", id));
        return ResponseEntity.noContent().build();
    }
}
