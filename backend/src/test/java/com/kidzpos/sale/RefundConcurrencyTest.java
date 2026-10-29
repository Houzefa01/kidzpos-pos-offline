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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cas 3 — Refund concurrent : 1 vente, 2 tentatives de refund simultanées.
 * Invariant : exactement UN refund créé, stock restocké d'exactement la quantité de la vente.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefundConcurrencyTest extends IntegrationTestBase {

    @Autowired SaleController saleController;
    @Autowired ProductRepository products;
    @Autowired StoreRepository stores;
    @Autowired SaleRepository sales;
    @Autowired StockMovementRepository moves;
    @Autowired SettingsRepository settings;

    private static final String STORE_ID = "store-refund";
    private static final String PROD = "prod-refund";

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
                .storeId(STORE_ID).sku("SKU-R").createdAt(Instant.now()).build());
    }

    @Test
    void onlyOneRefundCreatedWhenIssuedTwiceConcurrently() throws Exception {
        // 1) Création d'une vente
        authenticate("admin-1", "ADMIN", null);
        var sale = saleController.checkout(new CheckoutReq(
                STORE_ID, "sale-refund-test",
                List.of(new SaleItemReq(PROD, 3)),
                0.0, PaymentMode.CASH, 3000.0, null, 0, "AR"), currentPrincipal());
        SecurityContextHolder.clearContext();
        assertThat(sale.getStatusCode().is2xxSuccessful()).isTrue();
        Sale created = (Sale) sale.getBody();
        assertThat(created).isNotNull();
        String saleId = created.getId();

        // Stock = 10 - 3 = 7 après la vente
        assertThat(products.findById(PROD).orElseThrow().getStock()).isEqualTo(7);

        // 2) Deux refunds concurrents sur cette vente
        int threads = 4;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var ok = new AtomicInteger();
        var rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    authenticate("admin-" + idx, "ADMIN", null);
                    // V22 — clientRefundId distinct par tentative pour exercer la
                    // course sur uk_sales_client_refund_id séparément de l'idempotence
                    // (testée dans RefundIdempotencyTest). Ici on ne veut tester QUE
                    // l'invariant historique : 1 seul refund pour la même vente.
                    var res = saleController.refund(
                            new RefundReq(saleId, "thread-" + idx + "-" + java.util.UUID.randomUUID()),
                            currentPrincipal());
                    if (res.getStatusCode().is2xxSuccessful()) ok.incrementAndGet();
                    else rejected.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    SecurityContextHolder.clearContext();
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        // EXACTEMENT un refund réussi
        assertThat(ok.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);

        // Une seule entrée Sale avec refundedFrom = saleId
        long refunds = sales.findAll().stream()
                .filter(s -> saleId.equals(s.getRefundedFrom())).count();
        assertThat(refunds).isEqualTo(1L);

        // Stock restocké à 10
        assertThat(products.findById(PROD).orElseThrow().getStock()).isEqualTo(10);
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
