package com.kidzpos.web;

import com.kidzpos.events.EventBus;
import com.kidzpos.observability.BusinessMetrics;
import com.kidzpos.repo.CustomerRepository;
import com.kidzpos.repo.ProductRepository;
import com.kidzpos.repo.SaleRepository;
import com.kidzpos.repo.SettingsRepository;
import com.kidzpos.repo.StockMovementRepository;
import com.kidzpos.security.JwtAuthFilter.AuthPrincipal;
import com.kidzpos.sync.NodeContext;
import com.kidzpos.sync.OperationLogService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P4 — Politique GET cross-store STRICTE.
 *
 * Vérifie que les 3 endpoints GET store-scopés refusent l'accès cross-store
 * pour un EMPLOYEE (403) et autorisent l'ADMIN partout (sans interaction
 * repo lors du refus → constate que la guard est bien en amont).
 *
 * Tests purs JUnit + Mockito (pas de Spring context, pas de Docker).
 * Couvre les contrats du contrôleur indépendamment du backend réel.
 */
class CrossStoreReadGuardTest {

    private static AuthPrincipal employee(String storeId) {
        return new AuthPrincipal("u-emp", "Sarah", "sarah@x", "EMPLOYEE", storeId);
    }

    private static AuthPrincipal admin() {
        return new AuthPrincipal("u-adm", "Admin", "admin@x", "ADMIN", null);
    }

    // ──── ProductController.list ────────────────────────────────────────────

    @Test
    void productList_employeeWithoutStoreId_returns403() {
        ProductController c = new ProductController(
                mock(ProductRepository.class), mock(EventBus.class), new BusinessMetrics(new SimpleMeterRegistry()),
                mock(NodeContext.class), mock(OperationLogService.class));
        ResponseEntity<?> res = c.list(null, null, employee("s1"));
        assertForbidden(res);
    }

    @Test
    void productList_employeeRequestingOtherStore_returns403() {
        ProductController c = new ProductController(
                mock(ProductRepository.class), mock(EventBus.class), new BusinessMetrics(new SimpleMeterRegistry()),
                mock(NodeContext.class), mock(OperationLogService.class));
        ResponseEntity<?> res = c.list("s2", null, employee("s1"));
        assertForbidden(res);
    }

    @Test
    void productList_employeeOnOwnStore_returnsOk() {
        ProductRepository repo = mock(ProductRepository.class);
        when(repo.findByStoreId("s1")).thenReturn(List.of());
        ProductController c = new ProductController(repo, mock(EventBus.class), new BusinessMetrics(new SimpleMeterRegistry()), mock(NodeContext.class), mock(OperationLogService.class));
        ResponseEntity<?> res = c.list("s1", null, employee("s1"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void productList_adminWithoutStoreId_returnsAllProducts() {
        ProductRepository repo = mock(ProductRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        ProductController c = new ProductController(repo, mock(EventBus.class), new BusinessMetrics(new SimpleMeterRegistry()), mock(NodeContext.class), mock(OperationLogService.class));
        ResponseEntity<?> res = c.list(null, null, admin());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ──── PR http-hardening — ETag collection produits ──────────────────────

    @Test
    void productList_returnsEtagHeader() {
        ProductRepository repo = mock(ProductRepository.class);
        when(repo.findAll()).thenReturn(List.of(productFixture("p-1", 0), productFixture("p-2", 0)));
        ProductController c = new ProductController(repo, mock(EventBus.class), new BusinessMetrics(new SimpleMeterRegistry()), mock(NodeContext.class), mock(OperationLogService.class));
        ResponseEntity<?> res = c.list(null, null, admin());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getETag()).isNotBlank().startsWith("W/\"");
    }

    @Test
    void productList_with_ifNoneMatch_matching_returns304WithEmptyBody() {
        ProductRepository repo = mock(ProductRepository.class);
        when(repo.findAll()).thenReturn(List.of(productFixture("p-1", 7), productFixture("p-2", 3)));
        ProductController c = new ProductController(repo, mock(EventBus.class), new BusinessMetrics(new SimpleMeterRegistry()), mock(NodeContext.class), mock(OperationLogService.class));
        // 1er appel récupère l'ETag
        String etag = c.list(null, null, admin()).getHeaders().getETag();
        // 2e appel le repasse → 304 sans body, ETag rééémis (RFC 7232 §4.1)
        ResponseEntity<?> res = c.list(null, etag, admin());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(res.getBody()).isNull();
        assertThat(res.getHeaders().getETag()).isEqualTo(etag);
    }

    @Test
    void productList_with_ifNoneMatch_stale_returns200WithBody() {
        ProductRepository repo = mock(ProductRepository.class);
        when(repo.findAll()).thenReturn(List.of(productFixture("p-1", 0)));
        ProductController c = new ProductController(repo, mock(EventBus.class), new BusinessMetrics(new SimpleMeterRegistry()), mock(NodeContext.class), mock(OperationLogService.class));
        ResponseEntity<?> res = c.list(null, "W/\"obsolete-etag\"", admin());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
    }

    @Test
    void productList_etag_changesWhenVersionChanges() {
        ProductRepository repo = mock(ProductRepository.class);
        ProductController c = new ProductController(repo, mock(EventBus.class), new BusinessMetrics(new SimpleMeterRegistry()), mock(NodeContext.class), mock(OperationLogService.class));
        // Snapshot 1 : version 0
        when(repo.findAll()).thenReturn(List.of(productFixture("p-1", 0)));
        String etag1 = c.list(null, null, admin()).getHeaders().getETag();
        // Snapshot 2 : même id, version incrémentée → ETag doit changer
        when(repo.findAll()).thenReturn(List.of(productFixture("p-1", 1)));
        String etag2 = c.list(null, null, admin()).getHeaders().getETag();
        assertThat(etag2).isNotEqualTo(etag1);
    }

    private static com.kidzpos.domain.Product productFixture(String id, int version) {
        return com.kidzpos.domain.Product.builder()
                .id(id).name("test").price(1000).stock(10)
                .storeId("s1").sku("SKU-" + id)
                .createdAt(java.time.Instant.now())
                .version(version)
                .build();
    }

    // ──── StockController.movements ─────────────────────────────────────────

    @Test
    void stockMovements_employeeWithoutStoreId_returns403() {
        StockController c = new StockController(
                mock(ProductRepository.class), mock(StockMovementRepository.class),
                mock(EventBus.class), new BusinessMetrics(new SimpleMeterRegistry()),
                mock(OperationLogService.class), mock(NodeContext.class));
        ResponseEntity<?> res = c.movements(null, employee("s1"));
        assertForbidden(res);
    }

    @Test
    void stockMovements_employeeRequestingOtherStore_returns403() {
        StockController c = new StockController(
                mock(ProductRepository.class), mock(StockMovementRepository.class),
                mock(EventBus.class), new BusinessMetrics(new SimpleMeterRegistry()),
                mock(OperationLogService.class), mock(NodeContext.class));
        ResponseEntity<?> res = c.movements("s2", employee("s1"));
        assertForbidden(res);
    }

    @Test
    void stockMovements_adminWithoutStoreId_returnsOk() {
        StockMovementRepository moves = mock(StockMovementRepository.class);
        when(moves.findAllByOrderByDateDesc()).thenReturn(List.of());
        StockController c = new StockController(
                mock(ProductRepository.class), moves, mock(EventBus.class), new BusinessMetrics(new SimpleMeterRegistry()),
                mock(OperationLogService.class), mock(NodeContext.class));
        ResponseEntity<?> res = c.movements(null, admin());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ──── SaleController.list ───────────────────────────────────────────────

    @Test
    void saleList_employeeWithoutStoreId_returns403() {
        SaleController c = newSaleController();
        Object res = c.list(null, null, 50, employee("s1"));
        assertThat(res).isInstanceOf(ResponseEntity.class);
        assertForbidden((ResponseEntity<?>) res);
    }

    @Test
    void saleList_employeeRequestingOtherStore_returns403() {
        SaleController c = newSaleController();
        Object res = c.list("s2", null, 50, employee("s1"));
        assertThat(res).isInstanceOf(ResponseEntity.class);
        assertForbidden((ResponseEntity<?>) res);
    }

    @Test
    void saleList_employeeOnOwnStore_doesNotReturnForbidden() {
        SaleRepository repo = mock(SaleRepository.class);
        // T3 — Le path "sans page" passe maintenant par la variante paginée (cap interne 2000).
        org.springframework.data.domain.Page<com.kidzpos.domain.Sale> emptyPage =
                org.springframework.data.domain.Page.empty();
        when(repo.findByStoreIdOrderByDateDesc(org.mockito.ArgumentMatchers.eq("s1"),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(emptyPage);
        SaleController c = new SaleController(
                repo, mock(ProductRepository.class), mock(CustomerRepository.class),
                mock(SettingsRepository.class), mock(StockMovementRepository.class),
                mock(EventBus.class), new BusinessMetrics(new SimpleMeterRegistry()),
                mock(OperationLogService.class),
                mock(NodeContext.class),
                mock(PlatformTransactionManager.class),
                2000);
        Object res = c.list("s1", null, 50, employee("s1"));
        // Sur le path heureux, le controller retourne List<Sale> (page.getContent()), pas un ResponseEntity.
        assertThat(res).isInstanceOf(List.class);
    }

    // ──── Helpers ───────────────────────────────────────────────────────────

    private static SaleController newSaleController() {
        return new SaleController(
                mock(SaleRepository.class), mock(ProductRepository.class),
                mock(CustomerRepository.class), mock(SettingsRepository.class),
                mock(StockMovementRepository.class), mock(EventBus.class),
                new BusinessMetrics(new SimpleMeterRegistry()),
                mock(OperationLogService.class),
                mock(NodeContext.class),
                mock(PlatformTransactionManager.class),
                2000);
    }

    @SuppressWarnings("unchecked")
    private static void assertForbidden(ResponseEntity<?> res) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isInstanceOf(java.util.Map.class);
        java.util.Map<String, String> body = (java.util.Map<String, String>) res.getBody();
        assertThat(body).containsKey("error");
        // Message neutre — pas de fuite du storeId du caller
        assertThat(body.get("error")).doesNotContain("s1").doesNotContain("s2");
    }
}
