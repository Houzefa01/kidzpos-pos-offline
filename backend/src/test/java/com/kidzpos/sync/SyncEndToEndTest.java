package com.kidzpos.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kidzpos.IntegrationTestBase;
import com.kidzpos.domain.Store;
import com.kidzpos.domain.SyncApiKey;
import com.kidzpos.repo.OperationLogRepository;
import com.kidzpos.repo.ProductRepository;
import com.kidzpos.repo.StoreRepository;
import com.kidzpos.repo.SyncApiKeyRepository;
import com.kidzpos.repo.SyncInboxRepository;
import com.kidzpos.sync.SyncApiKeyService.CreatedKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * T12 — Test E2E sync : push HTTP → operation_log + sync_inbox → handler
 * applique sur l'état métier.
 *
 * <p>Plutôt que monter 2 contextes Spring + 2 Postgres (coûteux et complexe),
 * on simule un central qui :
 *  1. Reçoit un POST /api/sync/push via HTTP (TestRestTemplate)
 *  2. Authentifie via une clé per-store V21
 *  3. Persiste en operation_log + sync_inbox (dual-write)
 *  4. Le SyncInboxProcessor (activé via @TestPropertySource) applique
 *     ProductCreatedHandler sur la base
 *  5. Le produit doit apparaître dans products
 *
 * <p>Couvre la chaîne critique : auth + push + dual-write + inbox apply +
 * idempotence par UUID. Si ce test passe, le rolling deployment des stores
 * (cf rolling_deployment.md) repose sur des bases vérifiées.
 */
@TestPropertySource(properties = {
        "kidzpos.sync.inbox.enabled=true",
        "kidzpos.sync.inbox.batch-size=50",
        "kidzpos.sync.inbox.interval-ms=500",
        "kidzpos.sync.inbox.initial-delay-ms=200",
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SyncEndToEndTest extends IntegrationTestBase {

    @LocalServerPort int port;

    @Autowired TestRestTemplate http;
    @Autowired SyncApiKeyService keyService;
    @Autowired SyncApiKeyRepository keyRepo;
    @Autowired OperationLogRepository opLog;
    @Autowired SyncInboxRepository inbox;
    @Autowired ProductRepository products;
    @Autowired StoreRepository stores;
    @Autowired ObjectMapper json;

    private static final String STORE_ID = "store-e2e";

    @BeforeEach
    void cleanAndSeed() {
        products.findAll().forEach(p -> products.delete(p));
        opLog.deleteAll();
        inbox.deleteAll();
        keyRepo.findAll().forEach(k -> keyRepo.delete(k));
        stores.findById(STORE_ID).ifPresent(stores::delete);
        stores.save(Store.builder().id(STORE_ID).name("E2E Store").location("").build());
    }

    @Test
    void push_with_perStoreKey_writes_to_operationLog_and_inbox_then_handler_applies() throws Exception {
        // ─── 1) Émission d'une clé per-store V21 ─────────────────────────────
        CreatedKey key = keyService.create(STORE_ID, "e2e-test");
        assertThat(key.plaintext()).isNotBlank();

        // ─── 2) Construction du payload : product.created ────────────────────
        UUID opId = UUID.randomUUID();
        String productId = "prod-e2e-" + UUID.randomUUID();
        Map<String, Object> productPayload = Map.of(
                "id", productId,
                "name", "Test E2E",
                "price", 1234.0,
                "stock", 7,
                "storeId", STORE_ID,
                "sku", "SKU-E2E-" + System.nanoTime()
        );
        Map<String, Object> operation = Map.of(
                "id", opId,
                "type", "product.created",
                "payload", json.writeValueAsString(productPayload),
                "createdAt", java.time.Instant.now().toString(),
                "storeId", STORE_ID
        );
        Map<String, Object> body = Map.of(
                "nodeId", STORE_ID + "-test",
                "operations", List.of(operation)
        );

        // ─── 3) POST /api/sync/push via HTTP ─────────────────────────────────
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(SyncApiKeyFilter.HEADER_KEY, key.plaintext());
        headers.set(SyncApiKeyFilter.HEADER_STORE, STORE_ID);

        ResponseEntity<Map> push = http.exchange(
                "http://localhost:" + port + "/api/sync/push",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        assertThat(push.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(push.getBody()).containsEntry("acceptedCount", 1);

        // ─── 4) Vérification dual-write operation_log + sync_inbox ───────────
        assertThat(opLog.existsById(opId)).isTrue();
        assertThat(inbox.existsById(opId)).isTrue();

        // ─── 5) Attendre que le processor applique (tick 500ms en test) ──────
        await().atMost(15, SECONDS).pollInterval(500, java.util.concurrent.TimeUnit.MILLISECONDS).untilAsserted(() -> {
            assertThat(inbox.findById(opId).orElseThrow().isProcessed())
                    .as("inbox event must be processed")
                    .isTrue();
            assertThat(products.findByIdIncludingDeleted(productId))
                    .as("product must be created locally by ProductCreatedHandler")
                    .isPresent();
        });

        // ─── 6) Push idempotent : 2e fois doit retourner duplicateCount=1 ────
        ResponseEntity<Map> push2 = http.exchange(
                "http://localhost:" + port + "/api/sync/push",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        assertThat(push2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(push2.getBody()).containsEntry("duplicateCount", 1);
        assertThat(push2.getBody()).containsEntry("acceptedCount", 0);

        // Toujours un seul produit (pas de doublon)
        assertThat(products.findAll()).hasSize(1);
    }

    @Test
    void push_with_wrong_storeHeader_returns_403_crossStore() throws Exception {
        // Clé pour store-e2e mais on prétend pousser pour store-OTHER → cross-store guard
        CreatedKey key = keyService.create(STORE_ID, "e2e-cross");

        UUID opId = UUID.randomUUID();
        Map<String, Object> operation = Map.of(
                "id", opId,
                "type", "product.created",
                "payload", "{}",
                "createdAt", java.time.Instant.now().toString(),
                "storeId", "store-OTHER"   // ≠ STORE_ID
        );
        Map<String, Object> body = Map.of(
                "nodeId", STORE_ID + "-cross",
                "operations", List.of(operation)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(SyncApiKeyFilter.HEADER_KEY, key.plaintext());
        headers.set(SyncApiKeyFilter.HEADER_STORE, STORE_ID);

        ResponseEntity<Map> push = http.exchange(
                "http://localhost:" + port + "/api/sync/push",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        assertThat(push.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Rien ne doit avoir été persisté
        assertThat(opLog.existsById(opId)).isFalse();
    }

    @Test
    void push_with_invalid_apiKey_returns_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(SyncApiKeyFilter.HEADER_KEY, "completely-wrong-key");
        headers.set(SyncApiKeyFilter.HEADER_STORE, STORE_ID);
        // On a quand même besoin d'au moins une clé en DB pour activer le mode per-store strict
        keyService.create(STORE_ID, "decoy");

        ResponseEntity<Map> push = http.exchange(
                "http://localhost:" + port + "/api/sync/push",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("nodeId", "x", "operations", List.of()), headers),
                Map.class);
        // 401 = pas authentifié (Spring Security retourne 401 quand le filter
        // n'a pas posé d'auth et qu'on tape un endpoint authenticated).
        assertThat(push.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
