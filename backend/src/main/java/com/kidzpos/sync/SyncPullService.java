package com.kidzpos.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kidzpos.domain.SyncInbox;
import com.kidzpos.repo.OperationLogRepository;
import com.kidzpos.repo.SyncInboxRepository;
import com.kidzpos.sync.SyncDtos.PullResponse;
import com.kidzpos.sync.SyncDtos.PushOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Pull des événements du serveur central vers la table locale {@code sync_inbox}.
 *
 * Pipeline d'un tick (best-effort, ne lance aucune exception au caller) :
 *  1. Cursor = max(createdAt) de sync_inbox (ou null si vide → cold start).
 *  2. GET {centralUrl}/api/sync/pull?since=...&limit=N
 *  3. Sur HTTP 2xx + JSON parsable :
 *     - pour chaque opération : insert si UUID non encore connu localement
 *     - les duplicates sont silencieusement ignorés (idempotence)
 *  4. Sur échec (réseau, timeout, 5xx, parse) : silencieux, retry au tick suivant.
 *
 * AUCUNE logique métier : on JOURNALISE dans sync_inbox avec processed=false.
 * Aucun controller métier ne lit cette table. Une future itération (HORS scope)
 * introduira un dispatcher qui appliquera les événements et basculera processed=true.
 *
 * Activation : @ConditionalOnProperty kidzpos.sync.pull.enabled=true.
 *              Sans cette propriété, le bean n'est PAS créé → ZÉRO impact runtime.
 */
@Service
@ConditionalOnProperty(prefix = "kidzpos.sync.pull", name = "enabled", havingValue = "true")
public class SyncPullService {

    private static final Logger log = LoggerFactory.getLogger(SyncPullService.class);

    private final SyncInboxRepository inbox;
    private final OperationLogRepository operationLog;
    private final ObjectMapper mapper;
    private final NodeContext nodeContext;
    private final String centralUrl;
    private final String apiKey;
    private final int batchSize;
    private final Duration timeout;
    private final HttpClient http;

    /** Compteur d'échecs consécutifs (réseau / timeout / 5xx). Reset sur 2xx. */
    private int consecutiveFailures = 0;
    /** Seuil au-delà duquel on passe d'INFO à WARN — signal "central durablement KO". */
    private static final int FAILURE_WARN_THRESHOLD = 5;

    public SyncPullService(SyncInboxRepository inbox,
                           OperationLogRepository operationLog,
                           ObjectMapper mapper,
                           NodeContext nodeContext,
                           @Value("${kidzpos.sync.pull.central-url:}") String centralUrl,
                           @Value("${kidzpos.sync.pull.api-key:}") String apiKey,
                           @Value("${kidzpos.sync.pull.batch-size:100}") int batchSize,
                           @Value("${kidzpos.sync.pull.timeout-ms:5000}") long timeoutMs) {
        this.inbox = inbox;
        this.operationLog = operationLog;
        this.mapper = mapper;
        this.nodeContext = nodeContext;
        this.centralUrl = centralUrl == null ? "" : centralUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
        this.timeout = Duration.ofMillis(Math.max(500, timeoutMs));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /** Vrai ssi la configuration est suffisante pour tenter un pull. */
    public boolean isConfigured() {
        return !centralUrl.isBlank() && !apiKey.isBlank();
    }

    /**
     * Tente un pull. Retourne le nombre d'événements effectivement INSÉRÉS
     * (hors duplicates). Retourne 0 sur tout échec ou si rien de neuf.
     *
     * Reentrant : safe à appeler en boucle bornée (cf {@link #pullAll(int)}).
     */
    public int pullOnce() {
        if (!isConfigured()) {
            log.debug("[sync-pull] not configured (centralUrl or apiKey missing) — skipping");
            return 0;
        }

        // 1) Cursor
        Instant since = inbox.findTopByOrderByCreatedAtDesc()
                .map(SyncInbox::getCreatedAt)
                .orElse(null);

        // 2) GET central
        // V22 — full mesh : on NE filtre PLUS par storeId au pull. Chaque nœud
        // reçoit TOUS les events de TOUS les magasins → permet à un admin de
        // voir l'activité globale depuis n'importe quel store. L'isolation
        // restante se fait au niveau API via enforceStoreScope (EMPLOYEE
        // contraint à son store, ADMIN voit tout).
        StringBuilder url = new StringBuilder(centralUrl).append("/api/sync/pull?limit=").append(batchSize);
        if (since != null) {
            url.append("&since=").append(URLEncoder.encode(since.toString(), StandardCharsets.UTF_8));
        }

        // V21 — Header X-Sync-Store-Id si nœud store-scoped (cf SyncPushService).
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(timeout)
                .header("X-Sync-Api-Key", apiKey)
                .header("Accept", "application/json");
        if (nodeContext.isStoreScoped()) {
            builder.header("X-Sync-Store-Id", nodeContext.storeId());
        }
        HttpRequest req = builder.GET().build();

        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpConnectTimeoutException e) {
            noteFailure("connect timeout");
            return 0;
        } catch (java.net.http.HttpTimeoutException e) {
            noteFailure("read timeout");
            return 0;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            noteFailure("network error: " + e.getMessage());
            return 0;
        }

        if (res.statusCode() / 100 != 2) {
            noteFailure("HTTP " + res.statusCode() + " body=" + abbreviate(res.body()));
            return 0;
        }

        PullResponse parsed;
        try {
            parsed = mapper.readValue(res.body(), PullResponse.class);
        } catch (Exception e) {
            noteFailure("response unparseable: " + e.getMessage());
            return 0;
        }

        // Réponse 2xx + parsable → central healthy, on reset le compteur.
        noteSuccess();

        if (parsed.operations() == null || parsed.operations().isEmpty()) {
            log.debug("[sync-pull] no new events (since={})", since);
            return 0;
        }

        // 3) Insertion idempotente
        int inserted = 0;
        int duplicates = 0;
        int ownSkipped = 0;
        int errors = 0;
        for (PushOperation op : parsed.operations()) {
            if (op.id() == null) continue;
            if (inbox.existsById(op.id())) {
                duplicates++;
                continue;
            }
            // Fix sync (audit 2026-05) — round-trip pull : depuis le full-mesh V22, le
            // pull ne filtre plus par storeId → le central RENVOIE aussi les events que
            // CE nœud lui a poussés. Or un event présent dans l'operation_log local a
            // été ÉMIS ici → son effet métier est DÉJÀ appliqué (opLog.record est
            // appelé APRÈS le saveAndFlush du controller). Le re-matérialiser via les
            // handlers est au mieux redondant, au pire destructeur : product.updated
            // ré-écrirait le `stock` figé à l'instant de l'édition catalogue, écrasant
            // les ventes/ajustements/transferts survenus depuis (stock divergent
            // store vs central). On l'insère donc processed=true (acquitté) : ça fait
            // avancer le curseur de pull (évite de le re-fetcher en boucle) SANS jamais
            // le rejouer. Les events d'AUTRES nœuds (absents de notre operation_log)
            // restent processed=false → matérialisés normalement (sens central→store).
            boolean ownEvent = operationLog.existsById(op.id());
            try {
                inbox.save(SyncInbox.builder()
                        .id(op.id())
                        .type(op.type() == null ? "unknown" : op.type())
                        .payload(op.payload() == null ? "{}" : op.payload())
                        .createdAt(op.createdAt() == null ? Instant.now() : op.createdAt())
                        .processed(ownEvent)
                        // V18 : preserve l'origine magasin de l'event (peut être NULL pour
                        // les events legacy du central pré-V18). Le processor a un
                        // garde cross-store pour empêcher l'application si != nodeContext.
                        .storeId(op.storeId())
                        .build());
                if (ownEvent) ownSkipped++; else inserted++;
            } catch (Exception e) {
                // Cas: contrainte unique violée si deux pulls concurrents inséraient
                // le même id. existsById l'évite déjà, mais en défense en profondeur
                // on swallow et on traite comme duplicate.
                errors++;
                log.debug("[sync-pull] insert failed for {} ({}): {}", op.id(), op.type(), e.getMessage());
            }
        }

        log.info("[sync-pull] received={} inserted={} ownSkipped={} duplicates={} errors={} hasMore={} since={}",
                parsed.operations().size(), inserted, ownSkipped, duplicates, errors, parsed.hasMore(), since);
        return inserted;
    }

    /**
     * Drain : enchaîne jusqu'à `maxBatches` pulls tant que le central signale
     * qu'il y a plus de données ET qu'on a effectivement inséré quelque chose
     * au dernier tour. Borné pour ne pas monopoliser le scheduler.
     */
    public int pullAll(int maxBatches) {
        int total = 0;
        for (int i = 0; i < Math.max(1, maxBatches); i++) {
            int n = pullOnce();
            if (n == 0) break;
            total += n;
        }
        return total;
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /**
     * Compte un échec consécutif et choisit le niveau de log :
     *   - n ≤ THRESHOLD → INFO (bruit acceptable, central juste down momentanément)
     *   - n > THRESHOLD → WARN (central durablement injoignable → ops doit regarder)
     *
     * La cadence des retries n'est PAS modifiée (déjà pilotée par le scheduler à
     * 60s prod / 10s test). Ce compteur ne fait QUE remonter le niveau d'urgence
     * du log pour les alertes Loki/ELK.
     */
    private void noteFailure(String reason) {
        consecutiveFailures++;
        if (consecutiveFailures > FAILURE_WARN_THRESHOLD) {
            log.warn("[sync-pull] central down for {} consecutive ticks: {}",
                    consecutiveFailures, reason);
        } else {
            log.info("[sync-pull] failure {}/{}: {} — will retry",
                    consecutiveFailures, FAILURE_WARN_THRESHOLD, reason);
        }
    }

    /** Reset le compteur — central a répondu 2xx + payload parsable. */
    private void noteSuccess() {
        if (consecutiveFailures > FAILURE_WARN_THRESHOLD) {
            log.info("[sync-pull] central recovered after {} consecutive failures", consecutiveFailures);
        }
        consecutiveFailures = 0;
    }
}
