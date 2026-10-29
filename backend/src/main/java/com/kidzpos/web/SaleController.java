package com.kidzpos.web;

import com.kidzpos.domain.*;
import com.kidzpos.dto.Dtos.*;
import com.kidzpos.observability.BusinessMetrics;
import com.kidzpos.repo.*;
import com.kidzpos.security.JwtAuthFilter.AuthPrincipal;
import com.kidzpos.security.StoreAccessGuard;
import com.kidzpos.sync.NodeContext;
import com.kidzpos.sync.OperationLogService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/sales")
public class SaleController {

    private static final Logger log = LoggerFactory.getLogger(SaleController.class);

    /** Retry sur collision uk_sale_store_seq (concurrence checkout/refund parallèle). */
    private static final int SEQ_RETRY_MAX = 5;

    /**
     * Audit-2026-05 — Cap dur sur les listings non paginés. Protège contre la
     * bombe à retardement : sans cap, un store avec 100k+ ventes serait livré
     * en une seule réponse (~100 Mo JSON, OOM frontend, GC backend).
     * 2000 = ~6 mois de ventes pour un magasin moyen ; suffit largement pour
     * l'historique consulté en POS ; au-delà, le client DOIT paginer.
     * Override via {@code KIDZPOS_SALES_LIST_DEFAULT_CAP} (env) en cas de besoin.
     */
    private final int unpaginatedListCap;

    private final SaleRepository sales;
    private final ProductRepository products;
    private final CustomerRepository customers;
    private final SettingsRepository settingsRepo;
    private final StockMovementRepository moves;
    private final com.kidzpos.events.EventBus bus;
    private final BusinessMetrics metrics;
    private final OperationLogService opLog;
    private final NodeContext nodeContext;
    private final TransactionTemplate tx;

    public SaleController(SaleRepository sales, ProductRepository products,
                          CustomerRepository customers, SettingsRepository settingsRepo,
                          StockMovementRepository moves, com.kidzpos.events.EventBus bus,
                          BusinessMetrics metrics,
                          OperationLogService opLog,
                          NodeContext nodeContext,
                          PlatformTransactionManager txm,
                          @org.springframework.beans.factory.annotation.Value("${kidzpos.sales.list-default-cap:2000}") int unpaginatedListCap) {
        this.sales = sales; this.products = products;
        this.customers = customers; this.settingsRepo = settingsRepo;
        this.moves = moves; this.bus = bus; this.metrics = metrics;
        this.opLog = opLog;
        this.nodeContext = nodeContext;
        this.tx = new TransactionTemplate(txm);
        this.unpaginatedListCap = Math.max(50, unpaginatedListCap);
    }

    /**
     * Listing des ventes.
     *
     * <p>Rétrocompat : si {@code page} non fourni → renvoie la List<Sale> historique,
     * MAIS désormais capée à {@link #unpaginatedListCap} entrées (défaut 2000,
     * configurable via {@code KIDZPOS_SALES_LIST_DEFAULT_CAP}). Au-delà, le client
     * DOIT paginer explicitement.
     *
     * <p>Audit-2026-05 : avant ce cap, un store avec 100k+ ventes retournait ~100 Mo
     * de JSON en une seule requête → OOM frontend + GC backend. Le cap est appliqué
     * en triant par date DESC : on garde les ventes les plus récentes (= celles qui
     * intéressent réellement le POS).
     *
     * <p>Avec {@code page} → Spring {@code Page<Sale>}, accès à toutes les ventes en
     * navigation incrémentale ({@code content} + {@code totalElements}).
     *
     * <p>P4 — Politique GET cross-store STRICTE : un EMPLOYEE qui appelle sans
     * {@code storeId} ou avec un storeId ≠ son magasin → 403. ADMIN passe partout.
     */
    // @Transactional(readOnly) : maintient la session ouverte pendant l'init des
    // collections items (LAZY + @BatchSize). Les ventes sont chargées sans
    // @EntityGraph (la pagination + JOIN FETCH forçait HHH90003004 / pagination en
    // mémoire), puis on force l'init par lot dans cette transaction avant de
    // retourner — Jackson sérialise alors des collections déjà matérialisées.
    @GetMapping
    @Transactional(readOnly = true)
    public Object list(@RequestParam(required = false) String storeId,
                       @RequestParam(required = false) Integer page,
                       @RequestParam(required = false, defaultValue = "50") Integer size,
                       @AuthenticationPrincipal AuthPrincipal me) {
        var deny = StoreAccessGuard.denyIfCrossStore(me, storeId);
        if (deny != null) return deny;
        // V19-audit — enforceStoreScope force le storeId du nœud sur tout local
        // store-scoped, peu importe ce qui est demandé. Sur central, conserve
        // le comportement legacy (storeId optionnel).
        String scope = StoreAccessGuard.enforceStoreScope(storeId, me, nodeContext);
        if (page == null) {
            // Cap dur : utilise la variante paginée pour ne charger que les N
            // dernières ventes, puis on retourne juste le content (List<Sale>) →
            // forme de réponse inchangée pour les clients legacy.
            var capped = org.springframework.data.domain.PageRequest.of(0, unpaginatedListCap);
            var pageRes = scope == null
                    ? sales.findAllByOrderByDateDesc(capped)
                    : sales.findByStoreIdOrderByDateDesc(scope, capped);
            if (pageRes.getTotalElements() > unpaginatedListCap) {
                log.warn("Sales list capped: store={} total={} returned={} (use ?page=N for full history)",
                        scope, pageRes.getTotalElements(), unpaginatedListCap);
            }
            return initItems(pageRes.getContent());
        }
        var pageable = org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.min(500, Math.max(1, size)));
        var pageRes = scope == null
                ? sales.findAllByOrderByDateDesc(pageable)
                : sales.findByStoreIdOrderByDateDesc(scope, pageable);
        initItems(pageRes.getContent());
        return pageRes;
    }

    /** Force l'init batché des collections items dans la transaction courante. */
    private List<Sale> initItems(List<Sale> list) {
        for (Sale s : list) s.getItems().size();
        return list;
    }

    /**
     * V19 — Durcissement : 404 si la vente appartient à un autre magasin sur
     * un nœud store-scoped (sinon comportement legacy via StoreAccessGuard).
     * Évite une fuite d'information silencieuse via ce read-by-ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Sale> get(@PathVariable String id,
                                    @AuthenticationPrincipal AuthPrincipal me) {
        return sales.findById(id)
                .filter(s -> StoreAccessGuard.isInScope(s.getStoreId(), me, nodeContext))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@Valid @RequestBody CheckoutReq req, @AuthenticationPrincipal AuthPrincipal me) {
        // P1.1 : un EMPLOYEE ne peut checkout que sur SON magasin. Check en amont
        // pour ne pas leak l'existence d'une vente cross-store via le path idempotent.
        var deny = StoreAccessGuard.denyIfCrossStore(me, req.storeId());
        if (deny != null) return deny;

        // I8 : idempotence — si le client a déjà reçu une réponse pour ce clientSaleId
        // (cas du replay outbox après reconnexion), renvoyer la vente existante.
        if (req.clientSaleId() != null && !req.clientSaleId().isBlank()) {
            var existing = sales.findById(req.clientSaleId());
            if (existing.isPresent()) {
                metrics.saleIdempotentReplay.increment();
                log.info("Idempotent sale replay absorbed: clientSaleId={}", req.clientSaleId());
                return ResponseEntity.ok(existing.get());
            }
        }
        return runWithSeqRetry(() -> tx.execute(status -> doCheckout(req, me)));
    }

    private ResponseEntity<?> doCheckout(CheckoutReq req, AuthPrincipal me) {
        Settings s = settingsRepo.findById(1L).orElseThrow();

        // Charger produits + valider stock
        // V19-audit — Lookup STRICTEMENT scoped par req.storeId() (déjà gardé en amont
        // contre cross-store). Empêche un caissier de vendre/décrémenter un produit
        // appartenant à un autre magasin même s'il connaît son ID.
        Map<String, Product> prodMap = new HashMap<>();
        for (var it : req.items()) {
            var p = products.findByIdAndStoreId(it.productId(), req.storeId()).orElse(null);
            if (p == null) return ResponseEntity.badRequest().body(Map.of("error", "Produit introuvable: " + it.productId()));
            if (p.getStock() < it.quantity()) return ResponseEntity.badRequest().body(Map.of("error", "Stock insuffisant: " + p.getName()));
            prodMap.put(p.getId(), p);
        }

        // Calculs
        double subtotal = 0;
        List<SaleItem> items = new ArrayList<>();
        for (var it : req.items()) {
            Product p = prodMap.get(it.productId());
            subtotal += p.getPrice() * it.quantity();
            items.add(SaleItem.builder()
                    .productId(p.getId()).name(p.getName())
                    .quantity(it.quantity()).price(p.getPrice())
                    .build());
        }

        double discount = req.discount();
        if (!"ADMIN".equals(me.role())) {
            double maxDiscount = subtotal * s.getMaxDiscountPercent() / 100.0;
            if (discount > maxDiscount) {
                return ResponseEntity.status(403).body(Map.of("error", "Remise dépasse plafond employé"));
            }
        }

        // B5 : redeem possible uniquement si client identifié + points effectivement détenus
        // V19-audit — Lookup customer strictement scoped (tolérance legacy NULL préservée).
        // Empêche un caissier de débiter des points d'un client d'un autre magasin.
        if (req.pointsRedeemed() > 0) {
            if (req.customerId() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Client requis pour utiliser des points"));
            }
            var c = customers.findByIdAndStoreIdOrLegacy(req.customerId(), req.storeId()).orElse(null);
            if (c == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Client introuvable"));
            }
            if (req.pointsRedeemed() > c.getPoints()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Points insuffisants"));
            }
        }

        double pointsValue = req.pointsRedeemed() * s.getArPerPoint();
        double total = Math.max(0, subtotal - discount - pointsValue);
        int pointsEarned = (int) Math.floor(total * s.getPointsPerAr());

        long seq = sales.findMaxSeqByStoreId(req.storeId()).orElse(0L) + 1;
        // I8 : si le client a fourni un clientSaleId, on l'utilise comme ID de vente
        // (permet la traçabilité front ↔ back et l'idempotence du checkout).
        String saleId = (req.clientSaleId() != null && !req.clientSaleId().isBlank())
                ? req.clientSaleId()
                : "sale-" + UUID.randomUUID();

        // Devise demandée par le client → fallback : devise globale du shop.
        String saleCurrency = (req.currency() != null && !req.currency().isBlank())
                ? req.currency()
                : (s.getCurrency() != null ? s.getCurrency() : "AR");

        Sale sale = Sale.builder()
                .id(saleId).seq(seq)
                .storeId(req.storeId())
                .userId(me.id()).userName(me.name())
                .subtotal(round(subtotal))
                .discount(round(discount)).total(round(total))
                .date(Instant.now())
                .customerId(req.customerId()).customerName(null)
                .pointsEarned(pointsEarned).pointsRedeemed(req.pointsRedeemed())
                .paymentMode(req.paymentMode())
                .amountPaid(req.amountPaid())
                .change(req.amountPaid() == null ? null : round(req.amountPaid() - total))
                .currency(saleCurrency)
                .build();

        for (var i : items) { i.setSale(sale); sale.getItems().add(i); }

        // Décrémente stock atomiquement (B6) + journal
        for (var it : req.items()) {
            Product p = prodMap.get(it.productId());
            int updated = products.decrementStockIfAvailable(p.getId(), it.quantity());
            if (updated == 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Stock insuffisant (concurrence): " + p.getName()));
            }
            p.setStock(p.getStock() - it.quantity()); // sync l'objet en mémoire pour la réponse JSON
            moves.save(StockMovement.builder()
                    .productId(p.getId()).storeId(p.getStoreId())
                    .type(MovementType.SALE).quantity(-it.quantity())
                    .date(Instant.now()).userId(me.id())
                    .relatedSaleId(saleId).build());
        }

        // Client / fidélité
        // V19-audit — Lookup strictement scoped (tolérance legacy NULL préservée).
        if (req.customerId() != null) {
            customers.findByIdAndStoreIdOrLegacy(req.customerId(), req.storeId()).ifPresent(c -> {
                c.setPoints(Math.max(0, c.getPoints() + pointsEarned - req.pointsRedeemed()));
                c.setTotalSpent(round(c.getTotalSpent() + total));
                c.setVisits(c.getVisits() + 1);
                if (c.getName() != null) sale.setCustomerName(c.getName());
                customers.save(c);
            });
        }

        // saveAndFlush : déclenche la contrainte uk_sale_store_seq dans cette transaction
        // (sans flush, l'erreur surviendrait au commit, hors du try/catch du retry).
        var saved = sales.saveAndFlush(sale);
        // ETAPE 3 — journal d'opérations (best-effort, n'interrompt pas le checkout).
        opLog.record("sale.checkout", saved);
        bus.publish("sale", "created", saved);
        bus.publish("product", "bulkUpdated", null);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/refund")
    public ResponseEntity<?> refund(@Valid @RequestBody RefundReq req, @AuthenticationPrincipal AuthPrincipal me) {
        if (!"ADMIN".equals(me.role())) return ResponseEntity.status(403).body(Map.of("error", "Admin requis"));
        // V22 — Idempotence du refund. Si le client a déjà reçu une réponse pour
        // ce clientRefundId (replay outbox, retry réseau), renvoyer le refund existant
        // au lieu d'en créer un nouveau. Check en amont du retry-seq pour éviter
        // toute contention DB en cas de boucle de retry serrée côté client.
        if (req.clientRefundId() != null && !req.clientRefundId().isBlank()) {
            var existing = sales.findByClientRefundId(req.clientRefundId());
            if (existing.isPresent()) {
                metrics.refundIdempotentReplay.increment();
                log.info("Idempotent refund replay absorbed: clientRefundId={}", req.clientRefundId());
                return ResponseEntity.ok(existing.get());
            }
        }
        return runWithSeqRetry(() -> tx.execute(status -> doRefund(req, me)));
    }

    private ResponseEntity<?> doRefund(RefundReq req, AuthPrincipal me) {
        var orig = sales.findById(req.saleId()).orElse(null);
        if (orig == null) return ResponseEntity.notFound().build();
        // P1.1 : défense en profondeur. La guard ADMIN ci-dessus suffit aujourd'hui,
        // mais si on assouplit la politique (refund par EMPLOYEE sur son magasin),
        // ce check empêche un EMPLOYEE de rembourser une vente d'un autre magasin.
        var deny = StoreAccessGuard.denyIfCrossStore(me, orig.getStoreId());
        if (deny != null) return deny;
        // I5 : refund idempotent — bloquer double-remboursement et refund-d'un-refund
        if (orig.getRefundedFrom() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Une vente d'avoir ne peut être remboursée"));
        }
        if (sales.existsByRefundedFrom(orig.getId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Vente déjà remboursée"));
        }

        long seq = sales.findMaxSeqByStoreId(orig.getStoreId()).orElse(0L) + 1;
        String id = "ref-" + UUID.randomUUID();
        Sale refund = Sale.builder()
                .id(id).seq(seq).storeId(orig.getStoreId())
                .userId(me.id()).userName(me.name())
                .subtotal(-orig.getSubtotal())
                .discount(-orig.getDiscount()).total(-orig.getTotal())
                .date(Instant.now())
                .pointsEarned(0).pointsRedeemed(0)
                .paymentMode(orig.getPaymentMode())
                .refundedFrom(orig.getId())
                // V22 — Persisté pour bloquer tout futur replay du même clientRefundId.
                // L'index UNIQUE partiel garantit aussi la sécurité en cas de race
                // (deux requêtes concurrentes avec le même clientRefundId : une réussit,
                // l'autre attrape DataIntegrityViolationException → 409).
                .clientRefundId(
                        req.clientRefundId() != null && !req.clientRefundId().isBlank()
                                ? req.clientRefundId()
                                : null)
                .build();

        for (var i : orig.getItems()) {
            var ri = SaleItem.builder()
                    .productId(i.getProductId()).name(i.getName())
                    .quantity(-i.getQuantity()).price(i.getPrice())
                    .sale(refund).build();
            refund.getItems().add(ri);
            // Restock même si le produit a été soft-deleted depuis la vente.
            products.findByIdIncludingDeleted(i.getProductId()).ifPresent(p -> {
                p.setStock(p.getStock() + i.getQuantity());
                products.save(p);
                moves.save(StockMovement.builder()
                        .productId(p.getId()).storeId(p.getStoreId())
                        .type(MovementType.REFUND).quantity(i.getQuantity())
                        .date(Instant.now()).userId(me.id())
                        .relatedSaleId(id).build());
            });
        }
        var saved = sales.saveAndFlush(refund);
        // ETAPE 3 — journal d'opérations (best-effort).
        opLog.record("sale.refund", saved);
        bus.publish("sale", "refunded", saved);
        bus.publish("product", "bulkUpdated", null);
        return ResponseEntity.ok(saved);
    }

    /**
     * Nom de la contrainte unique sur (storeId, seq) — cf entité Sale + V1.
     * Source de vérité côté DDL : @UniqueConstraint(name = "uk_sale_store_seq").
     */
    private static final String SEQ_CONSTRAINT = "uk_sale_store_seq";

    /**
     * Exécute une opération retournant ResponseEntity, avec retry UNIQUEMENT sur
     * collision de {@link #SEQ_CONSTRAINT} (concurrence checkout/refund parallèle).
     *
     * P2.2 — Toute autre {@link DataIntegrityViolationException} est propagée :
     *  - re-jouer la même mutation produirait la même erreur (FK, PK, autre unique)
     *  - le GlobalExceptionHandler la traduit en 409 "Conflit de données"
     *  - le compteur {@code saleSeqRetry} n'est incrémenté que sur retry RÉEL
     *    (anti-pollution métrique).
     */
    private ResponseEntity<?> runWithSeqRetry(java.util.function.Supplier<ResponseEntity<?>> op) {
        for (int attempt = 0; attempt < SEQ_RETRY_MAX; attempt++) {
            try {
                return op.get();
            } catch (DataIntegrityViolationException e) {
                if (!isSeqCollision(e)) {
                    // Violation d'une autre contrainte → ne pas retry : remonter pour
                    // que le handler global retourne 409 avec le message exact.
                    throw e;
                }
                metrics.saleSeqRetry.increment();
                if (attempt == SEQ_RETRY_MAX - 1) {
                    log.warn("Sale seq retry exhausted after {} attempts on {}", SEQ_RETRY_MAX, SEQ_CONSTRAINT);
                    return ResponseEntity.status(409).body(Map.of("error", "Conflit numérotation, réessayez"));
                }
                // retry : la prochaine itération relit MAX(seq) dans une nouvelle transaction
            }
        }
        return ResponseEntity.status(409).body(Map.of("error", "Conflit numérotation"));
    }

    /**
     * True ssi la violation est sur la contrainte d'unicité (storeId, seq).
     *
     * Stratégie : Postgres inclut le constraint name dans le message d'erreur
     * (org.postgresql.util.PSQLException). On cherche le nom dans la chaîne de
     * causes pour rester robuste si Hibernate enveloppe l'exception différemment
     * selon les versions.
     */
    private static boolean isSeqCollision(DataIntegrityViolationException e) {
        Throwable cause = e;
        while (cause != null) {
            String msg = cause.getMessage();
            if (msg != null && msg.contains(SEQ_CONSTRAINT)) return true;
            cause = cause.getCause();
        }
        return false;
    }

    /** Ariary canonique : pas de centimes. On arrondit à l'entier le plus proche. */
    private static double round(double v) { return Math.round(v); }
}
