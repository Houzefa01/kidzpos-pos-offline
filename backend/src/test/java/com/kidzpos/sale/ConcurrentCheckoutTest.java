package com.kidzpos.sale;

import com.kidzpos.IntegrationTestBase;
import com.kidzpos.domain.*;
import com.kidzpos.dto.Dtos.CheckoutReq;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cas 1 — Checkout concurrent : 2 caisses sur le même produit, stock = 1.
 * Invariant : exactement UNE vente passe ; stock final ≥ 0 ; aucune vente n'a un total négatif.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConcurrentCheckoutTest extends IntegrationTestBase {

    @Autowired SaleController saleController;
    @Autowired ProductRepository products;
    @Autowired StoreRepository stores;
    @Autowired SettingsRepository settings;
    @Autowired SaleRepository sales;
    @Autowired StockMovementRepository moves;

    private static final String STORE_ID = "test-store-concurrent";
    private static final String PRODUCT_ID = "test-prod-concurrent";

    @BeforeEach
    void cleanAndSeed() {
        moves.deleteAll();
        sales.deleteAll();
        products.findByIdIncludingDeleted(PRODUCT_ID).ifPresent(products::delete);
        stores.findById(STORE_ID).ifPresent(stores::delete);
        if (settings.count() == 0) {
            settings.save(Settings.builder().id(1L)
                    .maxDiscountPercent(10).pointsPerAr(0.0002).arPerPoint(100)
                    .shopName("test").currency("AR").build());
        }
        stores.save(Store.builder().id(STORE_ID).name("Test").location("").build());
        products.save(Product.builder()
                .id(PRODUCT_ID).name("Stock-1").price(1000).stock(1)
                .storeId(STORE_ID).sku("SKU-CONC-1").createdAt(Instant.now())
                .build());
    }

    @Test
    void onlyOneCheckoutSucceedsWhenStockIsOne() throws Exception {
        int threads = 8;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var successes = new AtomicInteger();
        var stockInsufficient = new AtomicInteger();
        var seqConflicts = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    authenticate("u-test-" + idx, "EMPLOYEE", STORE_ID);
                    var req = new CheckoutReq(
                            STORE_ID,
                            "client-sale-" + idx,
                            List.of(new SaleItemReq(PRODUCT_ID, 1)),
                            0.0,
                            PaymentMode.CASH,
                            1000.0,
                            null, 0, "AR");
                    ResponseEntity<?> res = saleController.checkout(req, currentPrincipal());
                    if (res.getStatusCode().is2xxSuccessful()) {
                        successes.incrementAndGet();
                    } else if (res.getStatusCode().value() == 400) {
                        stockInsufficient.incrementAndGet();
                    } else if (res.getStatusCode().value() == 409) {
                        seqConflicts.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Toute exception non gérée = bug
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

        // EXACTEMENT une vente créée
        var allSales = sales.findAll();
        assertThat(allSales).hasSize(1);
        assertThat(allSales.get(0).getTotal()).isPositive();

        // Stock final = 0 (jamais négatif)
        var p = products.findById(PRODUCT_ID).orElseThrow();
        assertThat(p.getStock()).isEqualTo(0);

        // Au moins un succès et le reste = stock insuffisant (le retry seq absorbe la concurrence)
        assertThat(successes.get()).isEqualTo(1);
        assertThat(stockInsufficient.get() + seqConflicts.get()).isEqualTo(threads - 1);
    }

    private AuthPrincipal currentPrincipal() {
        return (AuthPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private void authenticate(String userId, String role, String storeId) {
        var principal = new AuthPrincipal(userId, "Test " + userId, userId + "@test", role, storeId);
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
