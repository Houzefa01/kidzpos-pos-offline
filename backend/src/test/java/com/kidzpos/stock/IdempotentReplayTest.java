package com.kidzpos.stock;

import com.kidzpos.IntegrationTestBase;
import com.kidzpos.domain.*;
import com.kidzpos.dto.Dtos.StockAdjustReq;
import com.kidzpos.dto.Dtos.TransferReq;
import com.kidzpos.repo.*;
import com.kidzpos.security.JwtAuthFilter.AuthPrincipal;
import com.kidzpos.web.StockController;
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
 * Cas 2 — Replay outbox : double envoi de adjust et transfer avec même clientMovementId.
 * Invariant : une seule application en base (stock + une seule entrée dans stock_movements).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdempotentReplayTest extends IntegrationTestBase {

    @Autowired StockController stockController;
    @Autowired ProductRepository products;
    @Autowired StoreRepository stores;
    @Autowired StockMovementRepository moves;
    @Autowired SettingsRepository settings;

    @BeforeEach
    void cleanAndSeed() {
        moves.deleteAll();
        products.findAll().forEach(products::delete);
        stores.findAll().forEach(stores::delete);
        if (settings.count() == 0) {
            settings.save(Settings.builder().id(1L)
                    .maxDiscountPercent(10).pointsPerAr(0.0002).arPerPoint(100)
                    .shopName("test").currency("AR").build());
        }
        stores.save(Store.builder().id("s-a").name("A").location("").build());
        stores.save(Store.builder().id("s-b").name("B").location("").build());
        products.save(Product.builder()
                .id("p-adj").name("Adj").price(1000).stock(10)
                .storeId("s-a").sku("SKU-A").createdAt(Instant.now()).build());
        products.save(Product.builder()
                .id("p-tr").name("Tr").price(1000).stock(10)
                .storeId("s-a").sku("SKU-T").createdAt(Instant.now()).build());
    }

    @Test
    void adjustReplayedTwiceAppliesOnce() {
        authenticate("ADMIN", null);
        String mid = "client-mvt-replay-1";

        var req = new StockAdjustReq("p-adj", 5, "test", mid);
        var first = stockController.adjust(req, currentPrincipal());
        var second = stockController.adjust(req, currentPrincipal());

        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(second.getStatusCode().is2xxSuccessful()).isTrue();

        // Stock +5 une seule fois
        var p = products.findById("p-adj").orElseThrow();
        assertThat(p.getStock()).isEqualTo(15);

        // Un seul mouvement persisté avec ce clientMovementId
        var count = moves.findAll().stream()
                .filter(m -> mid.equals(m.getClientMovementId()))
                .count();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void transferReplayedTwiceAppliesOnce() {
        authenticate("ADMIN", null);
        String mid = "client-mvt-replay-tr";

        var req = new TransferReq("p-tr", "s-b", 3, mid);
        var first = stockController.transfer(req, currentPrincipal());
        var second = stockController.transfer(req, currentPrincipal());

        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(second.getStatusCode().is2xxSuccessful()).isTrue();

        // Source stock = 10 - 3 = 7 (pas 10 - 6)
        var src = products.findById("p-tr").orElseThrow();
        assertThat(src.getStock()).isEqualTo(7);

        // Le mouvement source porte le clientMovementId une seule fois
        var sourceMoves = moves.findAll().stream()
                .filter(m -> mid.equals(m.getClientMovementId()))
                .count();
        assertThat(sourceMoves).isEqualTo(1L);

        // Cible = 1 produit, stock = 3 (créé par le 1er transfer, intact au 2e replay idempotent)
        var dst = products.findByStoreIdAndSkuIgnoreCase("s-b", "SKU-T").orElseThrow();
        assertThat(dst.getStock()).isEqualTo(3);
    }

    private AuthPrincipal currentPrincipal() {
        return (AuthPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private void authenticate(String role, String storeId) {
        var principal = new AuthPrincipal("u-test", "Test", "test@test", role, storeId);
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
