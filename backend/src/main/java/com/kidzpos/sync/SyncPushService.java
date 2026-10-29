package com.kidzpos.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kidzpos.domain.OperationLog;
import com.kidzpos.repo.OperationLogRepository;
import com.kidzpos.sync.SyncDtos.PushOperation;
import com.kidzpos.sync.SyncDtos.PushRequest;
import com.kidzpos.sync.SyncDtos.PushResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Push des opérations locales vers le serveur central (PUSH-only).
 *
 * Pipeline d'un tick (best-effort, ne lance aucune exception au caller) :
 *  1. Charger un fenêtre de candidats avec synced=false (FIFO).
 *  2. Filtrer l'éligibilité (V14) — backoff exponentiel sur les items qui ont
 *     déjà échoué, max-attempts optionnel (frozen).
 *  3. POST {centralUrl}/api/sync/push (auth via X-Sync-Api-Key).
 *  4. Sur HTTP 2xx + réponse parsable :
 *     - marquer synced=true UNIQUEMENT pour les ids qui sont à la fois
 *       (a) dans le batch envoyé et (b) acquittés (accepted ∪ duplicate)
 *     - bump attemptCount + lastAttemptAt sur les ids envoyés MAIS non
 *       acquittés (réponse partielle/corrompue → retry au prochain tick éligible)
 *  5. Sur échec (réseau, timeout, 5xx, parse) : bump attemptCount sur le batch
 *     → backoff appliqué au prochain tick.
 *
 * Activation : @ConditionalOnProperty kidzpos.sync.push.enabled=true.
 *              Sans cette propriété, le bean n'est PAS créé → ZÉRO impact runtime.
 *
 * Aucune logique métier. Pas de thread custom. Pas de boucle bloquante.
 * Tout le pacing est piloté par le scheduler appelant (SyncScheduler).
 */
@Service
@ConditionalOnProperty(prefix = "kidzpos.sync.push", name = "enabled", havingValue = "true")
public class SyncPushService {

    private static final Logger log = LoggerFactory.getLogger(SyncPushService.class);

    private final OperationLogRepository repo;
    private final ObjectMapper mapper;
    private final NodeContext nodeContext;
    private final String centralUrl;
    private final String apiKey;
    private final String nodeId;
    private final int batchSize;
    private final Duration timeout;
    private final long baseBackoffMs;
    private final long maxBackoffMs;
    private final int maxAttempts;          // ≤0 = illimité soft (jamais frozen)
    private final HttpClient http;

    public SyncPushService(OperationLogRepository repo,
                           ObjectMapper mapper,
                           NodeContext nodeContext,
                           @Value("${kidzpos.sync.push.central-url:}") String centralUrl,
                           @Value("${kidzpos.sync.push.api-key:}") String apiKey,
                           @Value("${kidzpos.node.id:local-default}") String nodeId,
                           @Value("${kidzpos.sync.push.batch-size:20}") int batchSize,
                           @Value("${kidzpos.sync.push.timeout-ms:3000}") long timeoutMs,
                           @Value("${kidzpos.sync.push.retry.base-backoff-ms:30000}") long baseBackoffMs,
                           @Value("${kidzpos.sync.push.retry.max-backoff-ms:600000}") long maxBackoffMs,
                           @Value("${kidzpos.sync.push.retry.max-attempts:0}") int maxAttempts) {
        this.repo = repo;
        this.mapper = mapper;
        this.nodeContext = nodeContext;
        this.centralUrl = centralUrl == null ? "" : centralUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.nodeId = nodeId;
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
        this.timeout = Duration.ofMillis(Math.max(500, timeoutMs));
        this.baseBackoffMs = Math.max(1_000L, baseBackoffMs);
        this.maxBackoffMs = Math.max(this.baseBackoffMs, maxBackoffMs);
        this.maxAttempts = maxAttempts;     // ≤0 conservé tel quel → illimité soft
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /** Vrai ssi la configuration est suffisante pour tenter un push. */
    public boolean isConfigured() {
        return !centralUrl.isBlank() && !apiKey.isBlank();
    }

    /**
     * Tente un push d'un batch. Retourne le nombre d'opérations effectivement
     * marquées synced=true. Toujours 0 sur erreur (silencieux côté caller).
     */
    public int pushOnce() {
        if (!isConfigured()) {
            log.debug("[sync-push] not configured (centralUrl or apiKey missing) — skipping");
            return 0;
        }

        // 1) Fenêtre élargie pour absorber les items en backoff sans appel
        //    SQL supplémentaire. Bornée pour rester O(1) en mémoire.
        int fetchSize = batchSize * 3;
        List<OperationLog> candidates = repo.findBySyncedFalseOrderByCreatedAtAsc(PageRequest.of(0, fetchSize));
        if (candidates.isEmpty()) {
            log.debug("[sync-push] no pending operation");
            return 0;
        }

        // 2) Filtrage éligibilité (backoff / frozen)
        long now = System.currentTimeMillis();
        List<OperationLog> eligible = new ArrayList<>();
        int inBackoff = 0;
        int frozen = 0;
        for (OperationLog op : candidates) {
            if (eligible.size() >= batchSize) break;
            switch (checkEligibility(op, now)) {
                case READY -> eligible.add(op);
                case BACKOFF -> {
                    inBackoff++;
                    if (log.isDebugEnabled()) {
                        log.debug("[sync-push] skip {} (backoff): attempts={} sinceLast={}s nextIn={}s",
                                op.getId(), attempts(op), secondsSince(op.getLastAttemptAt(), now),
                                secondsUntilEligible(op, now));
                    }
                }
                case FROZEN -> frozen++;
            }
        }

        if (eligible.isEmpty()) {
            log.debug("[sync-push] {} pending candidates, none eligible (backoff={}, frozen={})",
                    candidates.size(), inBackoff, frozen);
            return 0;
        }
        log.debug("[sync-push] batch prep: eligible={}/{} (backoff={}, frozen={})",
                eligible.size(), candidates.size(), inBackoff, frozen);

        // 3) Sérialiser + envoyer
        // V18 : on inclut storeId du row dans la DTO. NULL préservé pour les
        // lignes legacy (pré-V18) — le central l'accepte et stocke NULL aussi.
        List<PushOperation> dtos = eligible.stream()
                .map(op -> new PushOperation(op.getId(), op.getType(), op.getPayload(), op.getCreatedAt(), op.getStoreId()))
                .toList();
        PushRequest payload = new PushRequest(nodeId, dtos);

        String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("[sync-push] serialization failed (batch size={}): {}", eligible.size(), e.getMessage());
            bumpAttempts(toIdSet(eligible));      // évite de retenter en boucle au prochain tick
            return 0;
        }

        // V21 — Si le nœud est store-scoped, ajout du header X-Sync-Store-Id.
        // Le central l'utilise pour cross-valider (le push n'est accepté que
        // si toutes les ops appartiennent à ce store). Backward compat : si le
        // central est en mode legacy (clé partagée), le header est ignoré.
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(centralUrl + "/api/sync/push"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-Sync-Api-Key", apiKey);
        if (nodeContext.isStoreScoped()) {
            builder.header("X-Sync-Store-Id", nodeContext.storeId());
        }
        HttpRequest req = builder
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpConnectTimeoutException e) {
            log.info("[sync-push] central unreachable (connect timeout) — backing off batch of {}", eligible.size());
            bumpAttempts(toIdSet(eligible));
            return 0;
        } catch (java.net.http.HttpTimeoutException e) {
            log.info("[sync-push] central timeout — backing off batch of {}", eligible.size());
            bumpAttempts(toIdSet(eligible));
            return 0;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.info("[sync-push] network error: {} — backing off batch of {}", e.getMessage(), eligible.size());
            bumpAttempts(toIdSet(eligible));
            return 0;
        }

        if (res.statusCode() / 100 != 2) {
            log.warn("[sync-push] central returned HTTP {} — body={} — backing off batch of {}",
                    res.statusCode(), abbreviate(res.body()), eligible.size());
            bumpAttempts(toIdSet(eligible));
            return 0;
        }

        PushResponse parsed;
        try {
            parsed = mapper.readValue(res.body(), PushResponse.class);
        } catch (Exception e) {
            log.warn("[sync-push] central response unparseable: {} — backing off batch of {}",
                    e.getMessage(), eligible.size());
            bumpAttempts(toIdSet(eligible));
            return 0;
        }

        // 4) Marquage STRICT : intersection (sent ∩ centralAcked).
        //    Protège contre une réponse corrompue qui inclurait des ids inconnus,
        //    et contre une réponse partielle qui omettrait des ids envoyés.
        Set<UUID> sentIds = toIdSet(eligible);
        Set<UUID> centralAcked = new HashSet<>();
        if (parsed.acceptedIds() != null) centralAcked.addAll(parsed.acceptedIds());
        if (parsed.duplicateIds() != null) centralAcked.addAll(parsed.duplicateIds());

        Set<UUID> toMark = new HashSet<>(centralAcked);
        toMark.retainAll(sentIds);                  // ne marque QUE ce qu'on a envoyé

        Set<UUID> unacked = new HashSet<>(sentIds);
        unacked.removeAll(toMark);

        int marked = markSynced(toMark);
        int bumped = 0;
        if (!unacked.isEmpty()) {
            log.warn("[sync-push] central returned 2xx but did not ack {} sent items — bumping attempt",
                    unacked.size());
            bumped = bumpAttempts(unacked);
        }

        // Détection d'un noise éventuel (ids inconnus dans la réponse). Diagnostic uniquement.
        if (log.isDebugEnabled()) {
            int unknown = centralAcked.size() - toMark.size();
            if (unknown > 0) {
                log.debug("[sync-push] central response contained {} ids not in our sent batch (ignored)", unknown);
            }
        }

        log.info("[sync-push] batch sent: size={} accepted={} duplicates={} marked={} unacked={}",
                eligible.size(), parsed.acceptedCount(), parsed.duplicateCount(), marked, bumped);
        return marked;
    }

    /**
     * Drain : enchaîne les batches tant qu'il y a du backlog ET que le central
     * répond. Borné à `maxBatches` pour éviter de monopoliser le scheduler.
     */
    public int pushAll(int maxBatches) {
        int total = 0;
        for (int i = 0; i < Math.max(1, maxBatches); i++) {
            int n = pushOnce();
            if (n == 0) break;
            total += n;
        }
        return total;
    }

    // ── Helpers internes (pas @Transactional : per-row save = per-row tx, suffisant) ──

    private int markSynced(Set<UUID> ids) {
        if (ids.isEmpty()) return 0;
        int count = 0;
        for (UUID id : ids) {
            var opt = repo.findById(id);
            if (opt.isEmpty()) continue;
            OperationLog op = opt.get();
            if (!op.isSynced()) {
                op.setSynced(true);
                repo.save(op);
                count++;
            }
        }
        return count;
    }

    private int bumpAttempts(Set<UUID> ids) {
        if (ids.isEmpty()) return 0;
        Instant now = Instant.now();
        int count = 0;
        for (UUID id : ids) {
            var opt = repo.findById(id);
            if (opt.isEmpty()) continue;
            OperationLog op = opt.get();
            if (op.isSynced()) continue;             // race / déjà acquitté par ailleurs
            op.setAttemptCount(attempts(op) + 1);
            op.setLastAttemptAt(now);
            repo.save(op);
            count++;
        }
        return count;
    }

    private static Set<UUID> toIdSet(List<OperationLog> ops) {
        return ops.stream().map(OperationLog::getId).collect(Collectors.toSet());
    }

    private static int attempts(OperationLog op) {
        Integer n = op.getAttemptCount();
        return n == null ? 0 : n;
    }

    // ── Éligibilité ────────────────────────────────────────────────────────

    private enum Eligibility { READY, BACKOFF, FROZEN }

    private Eligibility checkEligibility(OperationLog op, long now) {
        int n = attempts(op);
        if (n == 0) return Eligibility.READY;                    // jamais tenté
        if (maxAttempts > 0 && n >= maxAttempts) return Eligibility.FROZEN;
        Instant last = op.getLastAttemptAt();
        if (last == null) return Eligibility.READY;              // état incohérent : on retente
        long delay = computeBackoff(n);
        return last.toEpochMilli() + delay <= now ? Eligibility.READY : Eligibility.BACKOFF;
    }

    /**
     * Backoff exponentiel borné :
     *   attempt=1 → baseBackoffMs
     *   attempt=2 → baseBackoffMs × 2
     *   attempt=n → baseBackoffMs × 2^(n-1)  (capé à maxBackoffMs)
     *
     * Avec base=30s et max=10min : 30s, 1m, 2m, 4m, 8m, 10m, 10m… (illimité soft)
     */
    long computeBackoff(int attempt) {
        if (attempt <= 0) return 0L;
        int shift = Math.min(attempt - 1, 10);                   // cap anti-overflow
        long delay = baseBackoffMs << shift;
        if (delay <= 0) delay = maxBackoffMs;                    // sécurité overflow
        return Math.min(delay, maxBackoffMs);
    }

    private static long secondsSince(Instant t, long nowMs) {
        if (t == null) return -1;
        return Math.max(0, (nowMs - t.toEpochMilli()) / 1000);
    }

    private long secondsUntilEligible(OperationLog op, long nowMs) {
        Instant last = op.getLastAttemptAt();
        if (last == null) return 0;
        long target = last.toEpochMilli() + computeBackoff(attempts(op));
        return Math.max(0, (target - nowMs) / 1000);
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
