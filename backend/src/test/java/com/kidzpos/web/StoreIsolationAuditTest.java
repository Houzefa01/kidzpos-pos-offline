package com.kidzpos.web;

import com.kidzpos.IntegrationTestBase;
import com.kidzpos.domain.*;
import com.kidzpos.dto.Dtos.*;
import com.kidzpos.repo.*;
import com.kidzpos.security.JwtAuthFilter.AuthPrincipal;
import com.kidzpos.sync.NodeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V19-audit / V22 — Tests d'intégration d'isolation multi-magasin.
 *
 * Politique (V22) : <strong>ADMIN omnipotent partout</strong>. Un ADMIN connecté
 * à n'importe quel nœud peut lire/écrire les données de tout magasin (utile pour
 * support et debug en prod). En revanche, un EMPLOYEE est strictement contraint
 * à son storeId (via le JWT, pas le nœud).
 *
 * Couvre :
 *   - ADMIN : accès cross-store autorisé (listings + read-by-id + transfer)
 *   - EMPLOYEE : isolation stricte sur son storeId (cross-store → 404, listings filtrés)
 *
 * Utilise TestContainers Postgres (cf {@link IntegrationTestBase}) et override
 * le bean {@link NodeContext} pour simuler le nœud S1.
 *
 * Exécution : {@code mvn verify -pl backend}. Skippé en {@code mvn package -DskipTests}.
 */
@Import(StoreIsolationAuditTest.NodeS1Config.class)
class StoreIsolationAuditTest extends IntegrationTestBase {

    /** Override le bean NodeContext pour simuler un nœud rattaché au store "s1". */
    @TestConfiguration
    static class NodeS1Config {
        @Bean @Primary
        NodeContext testNodeContext() {
            return new NodeContext("s1");
        }
    }

    @Autowired ProductController productController;
    @Autowired SaleController saleController;
    @Autowired StockController stockController;
    @Autowired CustomerController customerController;

    @Autowired ProductRepository products;
    @Autowired SaleRepository sales;
    @Autowired StockMovementRepository moves;
    @Autowired CustomerRepository customers;
    @Autowired StoreRepository stores;
    @Autowired SettingsRepository settings;
    @Autowired OperationLogRepository operationLog;

    @BeforeEach
    void seed() {
        moves.deleteAll();
        sales.deleteAll();
        customers.deleteAll();
        products.findAll().forEach(p -> products.delete(p));
        stores.findAll().forEach(s -> stores.delete(s));
        if (settings.count() == 0) {
            settings.save(Settings.builder().id(1L)
                    .maxDiscountPercent(10).pointsPerAr(0.0002).arPerPoint(100)
                    .shopName("test").currency("AR").build());
        }
        stores.save(Store.builder().id("s1").name("S1").location("").build());
        stores.save(Store.builder().id("s2").name("S2").location("").build());

        // Produits dans les 2 magasins
        products.save(productOf("p-s1", "s1"));
        products.save(productOf("p-s2", "s2"));

        // Clients dans les 2 magasins
        customers.save(Customer.builder().id("c-s1").name("Alice (S1)").points(100)
                .totalSpent(0).visits(0).createdAt(Instant.now()).storeId("s1").build());
        customers.save(Customer.builder().id("c-s2").name("Bob (S2)").points(100)
                .totalSpent(0).visits(0).createdAt(Instant.now()).storeId("s2").build());
    }

    private static Product productOf(String id, String store) {
        return Product.builder()
                .id(id).name("Produit " + id).price(1000).stock(10)
                .storeId(store).sku("SKU-" + id)
                .createdAt(Instant.now())
                .build();
    }

    // ─── 1) LISTING : ADMIN voit tout, EMPLOYEE strictement filtré ───

    @Test
    void productList_adminWithoutStoreId_seesAllStores() {
        authenticate("u-adm", "ADMIN", null);
        // Politique V22 : ADMIN sans filtre voit les produits de tous les magasins.
        ResponseEntity<?> res = productController.list(null, null, currentPrincipal());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Product> body = (List<Product>) res.getBody();
        assertThat(body).extracting(Product::getStoreId).contains("s1", "s2");
    }

    @Test
    void productList_adminRequestingS2_returnsS2Products() {
        authenticate("u-adm", "ADMIN", null);
        // Politique V22 : ADMIN obtient ce qu'il demande (pas de scope forcé).
        ResponseEntity<?> res = productController.list("s2", null, currentPrincipal());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Product> body = (List<Product>) res.getBody();
        assertThat(body).extracting(Product::getStoreId).containsOnly("s2");
    }

    // ─── 2) READ BY ID : ADMIN peut lire cross-store, EMPLOYEE → 404 ───

    @Test
    void productGetOne_crossStoreByAdmin_returnsProduct() {
        authenticate("u-adm", "ADMIN", null);
        ResponseEntity<Product> res = productController.getOne("p-s2", currentPrincipal());
        // Politique V22 : ADMIN omnipotent → 200 même si entité hors nœud.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getStoreId()).isEqualTo("s2");
    }

    @Test
    void productGetOne_inScope_returnsProduct() {
        authenticate("u-adm", "ADMIN", null);
        ResponseEntity<Product> res = productController.getOne("p-s1", currentPrincipal());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().getId()).isEqualTo("p-s1");
    }

    // ─── 3) WRITE : impossible de vendre un produit cross-store ───

    @Test
    void checkout_referencingS2Product_returnsBadRequest() {
        authenticate("u-emp", "EMPLOYEE", "s1");
        var req = new CheckoutReq(
                "s1",                                       // sale store = mon store
                "client-sale-cross",
                List.of(new SaleItemReq("p-s2", 1)),       // produit cross-store
                0.0, PaymentMode.CASH, 1000.0,
                null, 0, "AR");
        ResponseEntity<?> res = saleController.checkout(req, currentPrincipal());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Le stock du produit S2 ne doit PAS avoir bougé.
        assertThat(products.findById("p-s2").orElseThrow().getStock()).isEqualTo(10);
    }

    @Test
    void checkout_redeemingPointsOfS2Customer_returnsBadRequest() {
        authenticate("u-emp", "EMPLOYEE", "s1");
        var req = new CheckoutReq(
                "s1", "client-sale-pts",
                List.of(new SaleItemReq("p-s1", 1)),
                0.0, PaymentMode.CASH, 1000.0,
                "c-s2",                                     // client cross-store
                50,                                          // tente de débiter 50 pts
                "AR");
        ResponseEntity<?> res = saleController.checkout(req, currentPrincipal());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Les points du client S2 ne doivent PAS avoir été touchés.
        assertThat(customers.findById("c-s2").orElseThrow().getPoints()).isEqualTo(100);
    }

    // ─── 4) STOCK : transfer cross-store autorisé pour ADMIN ───

    @Test
    void stockTransfer_crossStoreByAdmin_succeeds() {
        authenticate("u-adm", "ADMIN", null);
        // Politique V22 : ADMIN omnipotent peut orchestrer un transfer
        // depuis un produit s2 vers s1 même depuis un nœud rattaché à s1.
        var req = new TransferReq("p-s2", "s1", 1, "client-mv-1");
        ResponseEntity<?> res = stockController.transfer(req, currentPrincipal());
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        // Stock du produit source décrémenté de 1.
        assertThat(products.findById("p-s2").orElseThrow().getStock()).isEqualTo(9);
    }

    /**
     * Régression sync (audit 2026-05) — un transfert qui AUTO-CRÉE le produit
     * cible (SKU absent dans le magasin destination) DOIT émettre un
     * {@code product.created} dans l'operation_log. Sans cet event, le
     * StockTransferHandler du central ne trouve pas le produit cible → saute
     * l'incrément + le mouvement côté destination → 2 unités évaporées de la
     * vue agrégée du central. Garde le fix de StockController.transfer.
     */
    @Test
    void stockTransfer_autoCreatingTarget_emitsProductCreatedForSync() {
        authenticate("u-adm", "ADMIN", null);
        operationLog.deleteAll();
        // Cible store "s3" : aucun produit avec le SKU de p-s1 → cible auto-créée.
        var req = new TransferReq("p-s1", "s3", 2, "client-mv-autocreate");
        ResponseEntity<?> res = stockController.transfer(req, currentPrincipal());
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();

        // operation_log a été purgé juste avant le transfert → les seuls events
        // présents sont ceux émis PAR ce transfert : 1 product.created (cible
        // auto-créée) + 1 stock.transfer. C'est précisément le product.created
        // qui manquait avant le fix (cf StockController.transfer).
        var ops = operationLog.findAll();
        long targetCreated = ops.stream()
                .filter(o -> "product.created".equals(o.getType()))
                .count();
        assertThat(targetCreated)
                .as("le produit cible auto-créé est journalisé pour la sync (product.created)")
                .isEqualTo(1);
        // Et le stock.transfer suit, ciblant le magasin destination s3.
        assertThat(ops).anyMatch(o -> "stock.transfer".equals(o.getType())
                && o.getPayload() != null && o.getPayload().contains("s3"));
    }

    // ─── 5) CUSTOMERS : listing isolé ───

    @Test
    void customerList_onStoreScopedNode_excludesOtherStores() {
        authenticate("u-emp", "EMPLOYEE", "s1");
        List<Customer> list = customerController.list();
        assertThat(list).extracting(Customer::getId).containsOnly("c-s1");
        assertThat(list).noneMatch(c -> "c-s2".equals(c.getId()));
    }

    @Test
    void customerGet_crossStore_returns404() {
        authenticate("u-emp", "EMPLOYEE", "s1");
        ResponseEntity<?> res = customerController.update("c-s2", new CustomerReq(null, "X", null, null, null, null), currentPrincipal());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

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
