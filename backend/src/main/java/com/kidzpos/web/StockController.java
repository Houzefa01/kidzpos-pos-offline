package com.kidzpos.web;

import com.kidzpos.domain.MovementType;
import com.kidzpos.domain.StockMovement;
import com.kidzpos.dto.Dtos.*;
import com.kidzpos.events.EventBus;
import com.kidzpos.observability.BusinessMetrics;
import com.kidzpos.repo.ProductRepository;
import com.kidzpos.repo.StockMovementRepository;
import com.kidzpos.security.JwtAuthFilter.AuthPrincipal;
import com.kidzpos.security.StoreAccessGuard;
import com.kidzpos.sync.OperationLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stock")
public class StockController {

    private static final Logger log = LoggerFactory.getLogger(StockController.class);

    private final ProductRepository products;
    private final StockMovementRepository moves;
    private final EventBus bus;
    private final BusinessMetrics metrics;
    private final OperationLogService opLog;
    private final com.kidzpos.sync.NodeContext nodeContext;

    public StockController(ProductRepository products, StockMovementRepository moves,
                           EventBus bus, BusinessMetrics metrics,
                           OperationLogService opLog,
                           com.kidzpos.sync.NodeContext nodeContext) {
        this.products = products; this.moves = moves; this.bus = bus; this.metrics = metrics;
        this.opLog = opLog;
        this.nodeContext = nodeContext;
    }

    /**
     * P4 — Politique GET cross-store STRICTE : un EMPLOYEE sans storeId (=
     * demande "tous les magasins") ou avec un storeId ≠ son magasin → 403.
     * ADMIN passe partout. Pas de fallback silencieux.
     */
    @GetMapping("/movements")
    public ResponseEntity<?> movements(@RequestParam(required = false) String storeId,
                                       @AuthenticationPrincipal AuthPrincipal me) {
        var deny = StoreAccessGuard.denyIfCrossStore(me, storeId);
        if (deny != null) return deny;
        // V19-audit — sur nœud store-scoped, force le scope (anti-fuite ADMIN).
        String scope = StoreAccessGuard.enforceStoreScope(storeId, me, nodeContext);
        var list = scope == null ? moves.findAllByOrderByDateDesc() : moves.findByStoreIdOrderByDateDesc(scope);
        return ResponseEntity.ok(list);
    }

    @PostMapping("/adjust")
    @Transactional
    public ResponseEntity<?> adjust(@Valid @RequestBody StockAdjustReq r, @AuthenticationPrincipal AuthPrincipal me) {
        // P1.1 : un EMPLOYEE ne peut ajuster un produit que de SON magasin.
        // Check en amont du path idempotent pour éviter de leak l'état d'un produit
        // cross-store via la réponse idempotente.
        var p = products.findById(r.productId()).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();
        var deny = StoreAccessGuard.denyIfCrossStore(me, p.getStoreId());
        if (deny != null) return deny;

        // Idempotence (V8) : si ce mouvement client a déjà été appliqué (replay outbox),
        // on retourne l'état courant sans dupliquer le delta.
        if (isIdempotentReplay(r.clientMovementId())) {
            return ResponseEntity.ok(p);
        }
        int newStock = p.getStock() + r.delta();
        if (newStock < 0) return ResponseEntity.badRequest().body(Map.of("error", "Stock négatif"));
        p.setStock(newStock); products.save(p);
        moves.save(StockMovement.builder()
                .productId(p.getId()).storeId(p.getStoreId())
                .type(r.delta() >= 0 ? MovementType.IN : MovementType.OUT)
                .quantity(r.delta()).date(Instant.now())
                .userId(me.id()).reason(r.reason())
                .clientMovementId(blankToNull(r.clientMovementId()))
                .build());
        // ETAPE 3 — journal d'opérations (best-effort).
        opLog.record("stock.adjust", Map.of(
                "productId", p.getId(),
                "storeId", p.getStoreId(),
                "delta", r.delta(),
                "reason", r.reason(),
                "newStock", p.getStock(),
                "clientMovementId", r.clientMovementId()));
        bus.publish("product", "updated", p);
        bus.publish("stock", "moved", Map.of("productId", p.getId()));
        return ResponseEntity.ok(p);
    }

    @PostMapping("/transfer")
    @Transactional
    public ResponseEntity<?> transfer(@Valid @RequestBody TransferReq r, @AuthenticationPrincipal AuthPrincipal me) {
        if (!"ADMIN".equals(me.role())) return ResponseEntity.status(403).body(Map.of("error", "Admin requis"));
        // Idempotence (V8) : seul le mouvement source porte clientMovementId,
        // sa présence en base signifie que les deux moves (out/in) ont déjà été inscrits.
        if (isIdempotentReplay(r.clientMovementId())) {
            return ResponseEntity.ok(Map.of("idempotent", true));
        }
        var src = products.findById(r.productId()).orElse(null);
        if (src == null) return ResponseEntity.notFound().build();
        // V19-audit — Sur un nœud store-scoped, le produit source DOIT appartenir
        // au store de ce nœud. Même un ADMIN ne peut pas transférer un produit
        // qu'il ne possède pas. 404 (pas 403) pour ne pas révéler l'existence.
        // Le target store reste libre (volontairement, c'est le but du transfer).
        if (!StoreAccessGuard.isInScope(src.getStoreId(), me, nodeContext)) {
            return ResponseEntity.notFound().build();
        }
        if (src.getStock() < r.quantity()) return ResponseEntity.badRequest().body(Map.of("error", "Stock insuffisant"));

        // Fix sync (audit 2026-05) — si le produit cible n'existe pas encore dans le
        // magasin destination, on le CRÉE *et on émet product.created* dans
        // l'operation_log. Sans cet event, le central (StockTransferHandler) ne
        // trouve pas le produit cible (dst == null) et saute silencieusement
        // l'incrément + le mouvement côté destination → la cible du transfert
        // disparaît de la vue agrégée du central (2 unités évaporées). L'event
        // product.created est journalisé AVANT le stock.transfer → createdAt
        // antérieur → matérialisé en premier sur le central → dst trouvé.
        var existingTarget = products.findByStoreIdAndSkuIgnoreCase(r.targetStoreId(), src.getSku());
        com.kidzpos.domain.Product target;
        if (existingTarget.isPresent()) {
            target = existingTarget.get();
        } else {
            target = products.save(com.kidzpos.domain.Product.builder()
                    .id("p" + System.currentTimeMillis())
                    .name(src.getName()).price(src.getPrice()).stock(0)
                    .storeId(r.targetStoreId()).category(src.getCategory()).sku(src.getSku())
                    .createdAt(Instant.now()).build());
            opLog.record("product.created", target);
            bus.publish("product", "created", target);
        }

        src.setStock(src.getStock() - r.quantity());
        target.setStock(target.getStock() + r.quantity());
        products.save(src); products.save(target);

        // clientMovementId porté uniquement par le mouvement source (le partner stays null)
        // pour respecter la contrainte unique partielle sans casser la garde d'idempotence.
        moves.save(StockMovement.builder()
                .productId(src.getId()).storeId(src.getStoreId())
                .type(MovementType.TRANSFER).quantity(-r.quantity())
                .date(Instant.now()).userId(me.id())
                .targetStoreId(r.targetStoreId())
                .clientMovementId(blankToNull(r.clientMovementId()))
                .build());
        moves.save(StockMovement.builder()
                .productId(target.getId()).storeId(target.getStoreId())
                .type(MovementType.TRANSFER).quantity(r.quantity())
                .date(Instant.now()).userId(me.id())
                .targetStoreId(src.getStoreId()).build());

        // ETAPE 3 — journal d'opérations (best-effort).
        opLog.record("stock.transfer", Map.of(
                "sourceProductId", src.getId(),
                "sourceStoreId", src.getStoreId(),
                "targetProductId", target.getId(),
                "targetStoreId", target.getStoreId(),
                "quantity", r.quantity(),
                "clientMovementId", r.clientMovementId()));
        bus.publish("product", "bulkUpdated", null);
        bus.publish("stock", "transferred", Map.of("from", src.getId(), "to", target.getId()));
        return ResponseEntity.ok(Map.of("source", src, "target", target));
    }

    private boolean isIdempotentReplay(String clientMovementId) {
        boolean replay = clientMovementId != null
                && !clientMovementId.isBlank()
                && moves.existsByClientMovementId(clientMovementId);
        if (replay) {
            metrics.stockIdempotentReplay.increment();
            log.info("Idempotent stock replay absorbed: clientMovementId={}", clientMovementId);
        }
        return replay;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
