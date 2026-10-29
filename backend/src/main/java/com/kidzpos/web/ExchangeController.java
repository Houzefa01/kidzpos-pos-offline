package com.kidzpos.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Service taux de change EUR -> MGA (Ariary).
 * - Si internet dispo : appelle open.er-api.com (gratuit, sans clé) — I7.
 *   (api.exchangerate.host nécessite désormais une clé API → abandonné.)
 * - Sinon : renvoie le dernier taux connu + flag offline.
 */
@RestController
@RequestMapping("/api/exchange")
public class ExchangeController {

    /** Throttle inter-appels : 60s entre deux fetchs externes effectifs.
     *  open.er-api.com est gratuit mais a un quota ; on cache au-delà. */
    private static final long REFRESH_MIN_INTERVAL_MS = 60_000L;

    @Value("${kidzpos.exchange.eur-to-ar-default:4900}")
    private double defaultRate;

    private volatile double lastRate = -1;
    private volatile Instant lastFetched = null;
    private final java.util.concurrent.atomic.AtomicLong lastRefreshAttemptMs = new java.util.concurrent.atomic.AtomicLong(0);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper json = new ObjectMapper();

    @GetMapping("/eur-to-ar")
    public Map<String, Object> get() {
        Map<String, Object> out = new HashMap<>();
        out.put("rate", lastRate > 0 ? lastRate : defaultRate);
        out.put("fetchedAt", lastFetched == null ? null : lastFetched.toString());
        out.put("source", lastRate > 0 ? "live" : "default");
        return out;
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh() {
        // Throttle : si on a tenté un refresh il y a < 60s, renvoyer le cache sans appel externe.
        long now = System.currentTimeMillis();
        long last = lastRefreshAttemptMs.get();
        if (last != 0 && now - last < REFRESH_MIN_INTERVAL_MS) {
            double rate = lastRate > 0 ? lastRate : defaultRate;
            Map<String, Object> body = new HashMap<>();
            body.put("rate", rate);
            body.put("fetchedAt", lastFetched == null ? null : lastFetched.toString());
            body.put("source", lastRate > 0 ? "cached" : "default");
            body.put("note", "throttled");
            return ResponseEntity.ok(body);
        }
        lastRefreshAttemptMs.set(now);
        try {
            // I7 : open.er-api.com renvoie {"result":"success","rates":{"MGA":...}, ...}
            var req = HttpRequest.newBuilder()
                    .uri(URI.create("https://open.er-api.com/v6/latest/EUR"))
                    .timeout(Duration.ofSeconds(4))
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new RuntimeException("HTTP " + res.statusCode());
            JsonNode node = json.readTree(res.body());
            double rate = node.path("rates").path("MGA").asDouble(0);
            if (rate <= 0) throw new RuntimeException("Pas de taux MGA");
            lastRate = rate;
            lastFetched = Instant.now();
            return ResponseEntity.ok(Map.of(
                    "rate", rate,
                    "fetchedAt", lastFetched.toString(),
                    "source", "live"
            ));
        } catch (Exception e) {
            // Pas d'internet côté backend → retourner 200 avec le dernier taux connu (ou défaut)
            // Le client retentera lui-même via le navigateur s'il a internet
            double fallbackRate = lastRate > 0 ? lastRate : defaultRate;
            String source = lastRate > 0 ? "cached" : "default";
            Map<String, Object> body = new HashMap<>();
            body.put("rate", fallbackRate);
            body.put("fetchedAt", lastFetched == null ? null : lastFetched.toString());
            body.put("source", source);
            body.put("note", "Backend offline — taux de fallback");
            return ResponseEntity.ok(body);
        }
    }
}
