package com.kidzpos.sale;

import com.kidzpos.IntegrationTestBase;
import com.kidzpos.domain.*;
import com.kidzpos.dto.Dtos.CheckoutReq;
import com.kidzpos.dto.Dtos.RefundReq;
import com.kidzpos.dto.Dtos.SaleItemReq;
import com.kidzpos.repo.*;
import com.kidzpos.security.JwtAuthFilter.AuthPrincipal;
import com.kidzpos.web.SaleController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V22 — Vérifie que {@code clientRefundId} rend le POST /api/sales/refund
 * idempotent au sens fort : le même clientRefundId rejoué N fois ne crée
 * qu'UN seul refund, ne restocke qu'UNE fois, et retourne toujours la même
 * réponse (le refund existant).
 *
 * <p>Avant V22, un retry réseau / replay outbox / double-clic créait deux refunds
 * pour la même vente : double crédit comptable, double restock, état corrompu.
 *
 * <p>Différent de {@link RefundConcurrencyTest} qui exerce la course sur la
 * contrainte (storeId, seq) avec des clientRefundId distincts.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefundIdempotencyTest extends IntegrationTestBase {

    @Autowired SaleController saleController;
    @Autowired ProductRepository products;
    @Autowired StoreRepository stores;
    @Autowired SaleRepository sales;
    @Autowired StockMovementRepository moves;
    @Autowired SettingsRepository settings;

    private static final String STORE_ID = "store-refund-idem";
    private static final String PROD = "prod-refund-idem";

    @BeforeEach
    void cleanAndSeed() {
        moves.deleteAll();
        sales.deleteAll();
        products.findByIdIncludingDeleted(PROD).ifPresent(products::delete);
        stores.findById(STORE_ID).ifPresent(stores::delete);
        if (settings.count() == 0) {
            settings.save(Settings.builder().id(1L)
                    .maxDiscountPercent(10).pointsPerAr(0.0002).arPerPoint(100)
                    .shopName("test").currency("AR").build());
        }
        stores.save(Store.builder().id(STORE_ID).name("R").location("").build());
        products.save(Product.builder()
                .id(PROD).name("X").price(1000).stock(10)
                .storeId(STORE_ID).sku("SKU-RI").createdAt(Instant.now()).build());
    }

    @Test
    void sameClientRefundId_replayedThreeTimes_creates_exactly_one_refund() {
        // Création d'une vente : stock 10 → 7
        authenticate("admin-1", "ADMIN", null);
        ResponseEntity<?> sale = saleController.checkout(new CheckoutReq(
                STORE_ID, "sale-idem-1",
                List.of(new SaleItemReq(PROD, 3)),
                0.0, PaymentMode.CASH, 3000.0, null, 0, "AR"), currentPrincipal());
        assertThat(sale.getStatusCode().is2xxSuccessful()).isTrue();
        String saleId = ((Sale) sale.getBody()).getId();
        assertThat(products.findById(PROD).orElseThrow().getStock()).isEqualTo(7);

        // 3 refunds avec le MÊME clientRefundId → 1 seul appliqué, 2 absorbés
        String clientRefundId = "client-refund-" + java.util.UUID.randomUUID();
        ResponseEntity<?> r1 = saleController.refund(new RefundReq(saleId, clientRefundId), currentPrincipal());
        ResponseEntity<?> r2 = saleController.refund(new RefundReq(saleId, clientRefundId), currentPrincipal());
        ResponseEntity<?> r3 = saleController.refund(new RefundReq(saleId, clientRefundId), currentPrincipal());

        // Les 3 doivent retourner 200 OK (replay absorbé silencieusement)
        assertThat(r1.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(r2.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(r3.getStatusCode().is2xxSuccessful()).isTrue();

        // Et toutes 3 doivent retourner LE MÊME refund (même id)
        String id1 = ((Sale) r1.getBody()).getId();
        String id2 = ((Sale) r2.getBody()).getId();
        String id3 = ((Sale) r3.getBody()).getId();
        assertThat(id2).isEqualTo(id1);
        assertThat(id3).isEqualTo(id1);

        // Côté DB : 1 vente + 1 refund (pas 3 refunds)
        long refundCount = sales.findAll().stream()
                .filter(s -> saleId.equals(s.getRefundedFrom())).count();
        assertThat(refundCount).isEqualTo(1L);

        // Stock restocké d'EXACTEMENT 3 (pas 9 — pas de triple restock)
        assertThat(products.findById(PROD).orElseThrow().getStock()).isEqualTo(10);

        SecurityContextHolder.clearContext();
    }

    @Test
    void legacyRefund_without_clientRefundId_still_works_for_backwardCompat() {
        // Création d'une vente
        authenticate("admin-1", "ADMIN", null);
        ResponseEntity<?> sale = saleController.checkout(new CheckoutReq(
                STORE_ID, "sale-legacy-1",
                List.of(new SaleItemReq(PROD, 2)),
                0.0, PaymentMode.CASH, 2000.0, null, 0, "AR"), currentPrincipal());
        assertThat(sale.getStatusCode().is2xxSuccessful()).isTrue();
        String saleId = ((Sale) sale.getBody()).getId();

        // Refund sans clientRefundId (client pré-V22)
        ResponseEntity<?> r = saleController.refund(new RefundReq(saleId, null), currentPrincipal());
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();

        // Le refund est créé, mais sans clientRefundId
        Sale refund = (Sale) r.getBody();
        assertThat(refund.getClientRefundId()).isNull();
        assertThat(refund.getRefundedFrom()).isEqualTo(saleId);

        SecurityContextHolder.clearContext();
    }

    private AuthPrincipal currentPrincipal() {
        return (AuthPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private void authenticate(String userId, String role, String storeId) {
        var principal = new AuthPrincipal(userId, "T " + userId, userId + "@t", role, storeId);
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
