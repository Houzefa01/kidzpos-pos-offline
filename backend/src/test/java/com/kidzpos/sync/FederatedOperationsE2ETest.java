package com.kidzpos.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kidzpos.IntegrationTestBase;
import com.kidzpos.domain.Customer;
import com.kidzpos.domain.MovementType;
import com.kidzpos.domain.PaymentMode;
import com.kidzpos.domain.Product;
import com.kidzpos.domain.Role;
import com.kidzpos.domain.Sale;
import com.kidzpos.domain.SaleItem;
import com.kidzpos.domain.StockMovement;
import com.kidzpos.domain.SyncInbox;
import com.kidzpos.domain.User;
import com.kidzpos.repo.ConflictLogRepository;
import com.kidzpos.repo.CustomerRepository;
import com.kidzpos.repo.ProductRepository;
import com.kidzpos.repo.SaleRepository;
import com.kidzpos.repo.StockMovementRepository;
import com.kidzpos.repo.SyncInboxRepository;
import com.kidzpos.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit 2026-05 — Test fédéré E2E "toutes opérations utilisateur".
 *
 * <p>OBJECTIF : prouver que CHAQUE opération réelle déclenchable par un
 * utilisateur via l'UI est correctement MATÉRIALISÉE par le pipeline de
 * synchronisation (operation_log → push/pull → sync_inbox → handler → tables
 * métier). C'est la garantie "tout est vu et synchronisé sur le store ET le
 * central".
 *
 * <p>MODÈLE : on simule un nœud destinataire (central ou autre store) qui
 * REÇOIT des events poussés/tirés depuis un autre nœud. On insère donc
 * directement dans {@code sync_inbox} le payload EXACT que les controllers
 * sérialisent via {@code opLog.record(...)} (contrats vérifiés par lecture du
 * code, gardés cohérents par {@link SyncHandlerCoverageTest}), puis on draine le
 * {@link SyncInboxProcessor} RÉEL et on asserte l'état métier.
 *
 * <p>BIDIRECTIONNALITÉ : les handlers sont symétriques — ils tournent à
 * l'identique que l'event vienne d'un push (store→central) ou d'un pull
 * (central→store). Ce test couvre donc les deux sens au niveau matérialisation ;
 * le transport (push/pull + auth) est couvert par {@link SyncEndToEndTest} et
 * {@link SyncCrossStorePullGuardTest}.
 *
 * <p>RÉGRESSION LWW (correctif sync audit 2026-05) : deux tests dédiés vérifient
 * que les changements de stock ne polluent plus le {@code updatedAt} servant au
 * Last-Writer-Wins catalogue, et qu'un event réellement obsolète est bien skippé
 * + journalisé en {@code conflict_log}.
 */
@TestPropertySource(properties = {
        "kidzpos.sync.inbox.enabled=true",
        "kidzpos.sync.inbox.batch-size=50",
        // Scheduler repoussé très loin : on pilote processAll() manuellement
        // pour un déterminisme total (pas de tick concurrent pendant les étapes).
        "kidzpos.sync.inbox.interval-ms=3600000",
        "kidzpos.sync.inbox.initial-delay-ms=3600000",
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FederatedOperationsE2ETest extends IntegrationTestBase {

    @Autowired SyncInboxProcessor processor;
    @Autowired SyncInboxRepository inbox;
    @Autowired ObjectMapper json;
    @Autowired ProductRepository products;
    @Autowired SaleRepository sales;
    @Autowired StockMovementRepository movements;
    @Autowired CustomerRepository customers;
    @Autowired UserRepository users;
    @Autowired ConflictLogRepository conflicts;

    private static final String S1 = "store-fed-1";
    private static final String S2 = "store-fed-2";

    @BeforeEach
    void clean() {
        // On ne touche QUE les tables que ce test pilote, avec des IDs uniques
        // (UUID) par test → pas de collision avec les autres classes ni entre runs
        // (le container Postgres est réutilisé). On purge le plumbing sync pour
        // des assertions déterministes.
        inbox.deleteAll();
        conflicts.deleteAll();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Insère un event dans sync_inbox (= "reçu d'un autre nœud") puis draine. */
    private void receiveAndApply(String type, String payloadJson, String storeId) {
        inbox.save(SyncInbox.builder()
                .id(UUID.randomUUID())
                .type(type)
                .payload(payloadJson)
                .createdAt(Instant.now())
                .processed(false)
                .storeId(storeId)
                .build());
        processor.processAll(10);
    }

    private void receive(String type, Map<String, ?> payload, String storeId) {
        try {
            receiveAndApply(type, json.writeValueAsString(payload), storeId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void receiveEntity(String type, Object entity, String storeId) {
        try {
            receiveAndApply(type, json.writeValueAsString(entity), storeId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Product seedProduct(String id, String storeId, String sku, int stock) {
        return products.save(Product.builder()
                .id(id).name("Seed " + sku).price(1000).stock(stock)
                .storeId(storeId).sku(sku).category("Jouets")
                .createdAt(Instant.now())
                .build());
    }

    private int stockOf(String productId) {
        return products.findByIdIncludingDeleted(productId).orElseThrow().getStock();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRODUIT : ajout → modification → suppression (soft-delete)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void product_create_update_delete_lifecycle_materializes() {
        String id = "p-" + UUID.randomUUID();
        String sku = "SKU-" + UUID.randomUUID();
        String now = Instant.now().toString();

        // AJOUT (product.created — cf ProductController.create → opLog.record)
        receive("product.created", Map.of(
                "id", id, "name", "Cube", "price", 4500.0, "stock", 12,
                "storeId", S1, "sku", sku, "category", "Jouets",
                "createdAt", now, "updatedAt", now), S1);
        Product created = products.findByIdIncludingDeleted(id).orElseThrow();
        assertThat(created.getName()).isEqualTo("Cube");
        assertThat(created.getStock()).isEqualTo(12);
        assertThat(created.getStoreId()).isEqualTo(S1);

        // MODIFICATION (product.updated) — updatedAt postérieur → appliqué
        String later = Instant.now().plusSeconds(1).toString();
        receive("product.updated", Map.of(
                "id", id, "name", "Cube XL", "price", 5000.0, "updatedAt", later), S1);
        Product updated = products.findByIdIncludingDeleted(id).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Cube XL");
        assertThat(updated.getPrice()).isEqualTo(5000.0);

        // SUPPRESSION (product.deleted = { id }) → soft-delete
        receive("product.deleted", Map.of("id", id), S1);
        Product deleted = products.findByIdIncludingDeleted(id).orElseThrow();
        assertThat(deleted.getDeletedAt()).as("soft-delete: deletedAt posé").isNotNull();
        assertThat(products.findById(id)).as("@SQLRestriction masque le supprimé").isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRODUIT : vente (sale.checkout) → décrément stock + mouvement + idempotence
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void sale_checkout_materializes_sale_decrements_stock_and_is_idempotent() throws Exception {
        String pid = "p-" + UUID.randomUUID();
        seedProduct(pid, S1, "SKU-" + UUID.randomUUID(), 10);
        String saleId = "s-" + UUID.randomUUID();

        Sale sale = buildSale(saleId, S1, pid, 3, MovementType.SALE);
        String payload = json.writeValueAsString(sale);

        receiveAndApply("sale.checkout", payload, S1);

        assertThat(sales.existsById(saleId)).isTrue();
        assertThat(stockOf(pid)).as("stock décrémenté de 3").isEqualTo(7);
        List<StockMovement> ms = movements.findAll().stream()
                .filter(m -> pid.equals(m.getProductId()) && m.getType() == MovementType.SALE)
                .toList();
        assertThat(ms).as("un mouvement SALE matérialisé").hasSize(1);
        assertThat(ms.get(0).getQuantity()).isEqualTo(-3);

        // Idempotence : rejeu du MÊME sale (autre id inbox, même sale.id) → no double
        receiveAndApply("sale.checkout", payload, S1);
        assertThat(stockOf(pid)).as("pas de double décrément (existsById)").isEqualTo(7);
        assertThat(sales.findAll().stream().filter(s -> saleId.equals(s.getId())).count()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRODUIT : remboursement / retour (sale.refund) → restock
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void sale_refund_materializes_and_restocks() throws Exception {
        String pid = "p-" + UUID.randomUUID();
        seedProduct(pid, S1, "SKU-" + UUID.randomUUID(), 7);
        String refundId = "r-" + UUID.randomUUID();

        // Un refund a des items à quantité NÉGATIVE (cf SaleController.doRefund)
        Sale refund = buildSale(refundId, S1, pid, -3, MovementType.REFUND);
        refund.setRefundedFrom("s-original");
        String payload = json.writeValueAsString(refund);

        receiveAndApply("sale.refund", payload, S1);

        assertThat(sales.existsById(refundId)).isTrue();
        assertThat(stockOf(pid)).as("restock de 3 (7 → 10)").isEqualTo(10);
        List<StockMovement> ms = movements.findAll().stream()
                .filter(m -> pid.equals(m.getProductId()) && m.getType() == MovementType.REFUND)
                .toList();
        assertThat(ms).as("un mouvement REFUND matérialisé").hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRODUIT : ajustement de stock (stock.adjust) + idempotence clientMovementId
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void stock_adjust_materializes_newStock_and_is_idempotent() {
        String pid = "p-" + UUID.randomUUID();
        seedProduct(pid, S1, "SKU-" + UUID.randomUUID(), 5);
        String cmid = "cm-" + UUID.randomUUID();

        receive("stock.adjust", Map.of(
                "productId", pid, "storeId", S1, "delta", 8,
                "reason", "Réassort", "newStock", 13, "clientMovementId", cmid), S1);

        assertThat(stockOf(pid)).as("alignement absolu sur newStock").isEqualTo(13);
        long adj = movements.findAll().stream()
                .filter(m -> pid.equals(m.getProductId()) && cmid.equals(m.getClientMovementId()))
                .count();
        assertThat(adj).isEqualTo(1);

        // Idempotence forte par clientMovementId : rejeu → skip
        receive("stock.adjust", Map.of(
                "productId", pid, "storeId", S1, "delta", 8,
                "reason", "Réassort", "newStock", 13, "clientMovementId", cmid), S1);
        assertThat(stockOf(pid)).as("pas de re-application").isEqualTo(13);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRODUIT : transfert inter-magasins (stock.transfer)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void stock_transfer_moves_quantity_between_stores() {
        String src = "p-" + UUID.randomUUID();
        String dst = "p-" + UUID.randomUUID();
        String sku = "SKU-" + UUID.randomUUID();
        seedProduct(src, S1, sku, 10);
        seedProduct(dst, S2, sku, 0);
        String cmid = "cm-" + UUID.randomUUID();

        receive("stock.transfer", Map.of(
                "sourceProductId", src, "sourceStoreId", S1,
                "targetProductId", dst, "targetStoreId", S2,
                "quantity", 4, "clientMovementId", cmid), S1);

        assertThat(stockOf(src)).as("source décrémentée").isEqualTo(6);
        assertThat(stockOf(dst)).as("cible incrémentée").isEqualTo(4);
        long transfers = movements.findAll().stream()
                .filter(m -> m.getType() == MovementType.TRANSFER
                        && (src.equals(m.getProductId()) || dst.equals(m.getProductId())))
                .count();
        assertThat(transfers).as("2 mouvements TRANSFER (out + in)").isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLIENT : ajout → modification → suppression
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void customer_create_update_delete_lifecycle_materializes() {
        String id = "c-" + UUID.randomUUID();
        Customer c = Customer.builder()
                .id(id).name("Rakoto").phone("034").email("rakoto@x.mg")
                .points(0).totalSpent(0).visits(0).storeId(S1)
                .createdAt(Instant.now()).build();

        receiveEntity("customer.created", c, S1);
        assertThat(customers.findById(id)).isPresent();
        assertThat(customers.findById(id).get().getName()).isEqualTo("Rakoto");

        receive("customer.updated", Map.of("id", id, "name", "Rakoto B", "points", 50), S1);
        Customer up = customers.findById(id).orElseThrow();
        assertThat(up.getName()).isEqualTo("Rakoto B");
        assertThat(up.getPoints()).isEqualTo(50);

        receive("customer.deleted", Map.of("id", id), S1);
        assertThat(customers.findById(id)).as("client supprimé").isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILISATEUR : ajout → désactivation (inactif) → réactivation+édition → suppression
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void user_lifecycle_including_active_inactive_materializes() {
        String id = "u-" + UUID.randomUUID();
        String email = "emp-" + UUID.randomUUID() + "@kidz.mg";
        User u = User.builder()
                .id(id).name("Sarah").email(email).passwordHash("$2a$bcryptfake")
                .role(Role.EMPLOYEE).storeId(S1).active(true).build();

        // AJOUT
        receiveEntity("user.created", u, S1);
        User created = users.findById(id).orElseThrow();
        assertThat(created.isActive()).isTrue();
        assertThat(created.getRole()).isEqualTo(Role.EMPLOYEE);

        // DÉSACTIVATION (actif → inactif) : user.updated avec active=false
        User deact = cloneUser(created); deact.setActive(false);
        receiveEntity("user.updated", deact, S1);
        assertThat(users.findById(id).orElseThrow().isActive()).as("inactif synchronisé").isFalse();

        // RÉACTIVATION + MODIFICATION (nom)
        User react = cloneUser(created); react.setActive(true); react.setName("Sarah R.");
        receiveEntity("user.updated", react, S1);
        User after = users.findById(id).orElseThrow();
        assertThat(after.isActive()).isTrue();
        assertThat(after.getName()).isEqualTo("Sarah R.");

        // SUPPRESSION (user.deleted = { id, storeId })
        receive("user.deleted", Map.of("id", id, "storeId", S1), S1);
        assertThat(users.findById(id)).as("utilisateur supprimé").isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RÉGRESSION LWW (correctif sync audit 2026-05)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Cœur du correctif : un changement de STOCK ne doit PLUS bumper le
     * {@code updatedAt} servant au LWW catalogue. Sinon une édition (nom/prix)
     * arrivée APRÈS une matérialisation de stock serait jugée "stale" et perdue.
     *
     * <p>Sans le correctif (ancien {@code @PreUpdate} qui re-stampait now()),
     * l'assertion "updatedAt reste T1 après stock.adjust" ÉCHOUE, puis l'édition
     * catalogue T2 est skippée → ce test garde la régression.
     */
    @Test
    void lww_stock_change_does_not_pollute_catalog_timestamp_and_later_edit_applies() {
        String id = "p-" + UUID.randomUUID();
        Instant t1 = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        Instant t2 = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MILLIS);

        // 1) Création avec horodatages SOURCE passés (préservés, pas re-stampés now)
        receive("product.created", Map.of(
                "id", id, "name", "Origine", "price", 1000.0, "stock", 10,
                "storeId", S1, "sku", "SKU-" + UUID.randomUUID(), "category", "Jouets",
                "createdAt", t1.toString(), "updatedAt", t1.toString()), S1);
        assertThat(products.findByIdIncludingDeleted(id).orElseThrow().getUpdatedAt())
                .as("created préserve l'updatedAt SOURCE (pas now())").isEqualTo(t1);

        // 2) Changement de stock → NE DOIT PAS toucher updatedAt
        receive("stock.adjust", Map.of(
                "productId", id, "storeId", S1, "newStock", 3,
                "reason", "vente", "clientMovementId", "cm-" + UUID.randomUUID()), S1);
        Product afterStock = products.findByIdIncludingDeleted(id).orElseThrow();
        assertThat(afterStock.getStock()).isEqualTo(3);
        assertThat(afterStock.getUpdatedAt())
                .as("stock.adjust ne pollue PAS le updatedAt catalogue (cœur du fix)")
                .isEqualTo(t1);

        // 3) Édition catalogue postérieure (T2 > T1) → DOIT s'appliquer (non stale)
        receive("product.updated", Map.of(
                "id", id, "name", "Éditée", "updatedAt", t2.toString()), S1);
        Product edited = products.findByIdIncludingDeleted(id).orElseThrow();
        assertThat(edited.getName()).as("édition postérieure appliquée").isEqualTo("Éditée");
        assertThat(edited.getUpdatedAt()).isEqualTo(t2);
    }

    /**
     * L'autre versant du LWW : un event RÉELLEMENT obsolète (updatedAt < local)
     * doit être SKIPPÉ et journalisé en conflict_log (type stale_update).
     */
    @Test
    void lww_genuinely_stale_update_is_skipped_and_logged() {
        String id = "p-" + UUID.randomUUID();
        Instant recent = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MILLIS);
        Instant old = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);

        receive("product.created", Map.of(
                "id", id, "name", "Récente", "price", 2000.0, "stock", 4,
                "storeId", S1, "sku", "SKU-" + UUID.randomUUID(), "category", "Jouets",
                "createdAt", recent.toString(), "updatedAt", recent.toString()), S1);

        // Event venu "du passé" (old < recent) → doit être ignoré
        receive("product.updated", Map.of(
                "id", id, "name", "OBSOLETE", "updatedAt", old.toString()), S1);

        Product p = products.findByIdIncludingDeleted(id).orElseThrow();
        assertThat(p.getName()).as("event stale ignoré (local gagne)").isEqualTo("Récente");
        assertThat(conflicts.findByEntityTypeAndEntityIdOrderByDetectedAtDesc("Product", id))
                .as("conflit stale_update journalisé").isNotEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Builders
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Construit une Sale sérialisable telle que SaleController.checkout/refund
     * l'émettrait. {@code itemQty} négatif simule un refund.
     */
    private Sale buildSale(String id, String storeId, String productId, int itemQty, MovementType kind) {
        Sale s = Sale.builder()
                .id(id).seq(1).storeId(storeId).userId("u-sys").userName("Caissier")
                .subtotal(3000).discount(0).total(3000).date(Instant.now())
                .pointsEarned(0).pointsRedeemed(0).paymentMode(PaymentMode.CASH)
                .currency("AR")
                .build();
        SaleItem item = SaleItem.builder()
                .productId(productId).name("Article").quantity(itemQty).price(1000)
                .build();
        item.setSale(s);
        s.getItems().add(item);
        return s;
    }

    private User cloneUser(User src) {
        return User.builder()
                .id(src.getId()).name(src.getName()).email(src.getEmail())
                .passwordHash(src.getPasswordHash()).role(src.getRole())
                .storeId(src.getStoreId()).active(src.isActive())
                .build();
    }
}
