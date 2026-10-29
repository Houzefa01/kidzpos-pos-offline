package com.kidzpos.web;

import com.kidzpos.dto.Dtos.FrontendMetricsReq;
import com.kidzpos.observability.FrontendMetricsCollector;
import com.kidzpos.security.JwtAuthFilter.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingestion des métriques offline-first remontées par chaque caisse.
 *
 * Auth Bearer requise (héritage SecurityConfig.anyRequest().authenticated()).
 * Endpoint léger : 204 No Content, pas de body de retour — limite la bande passante
 * et signale clairement "fire-and-forget" côté client.
 */
@RestController
@RequestMapping("/api/metrics")
public class FrontendMetricsController {

    private final FrontendMetricsCollector collector;

    public FrontendMetricsController(FrontendMetricsCollector collector) {
        this.collector = collector;
    }

    @PostMapping("/frontend")
    public ResponseEntity<Void> report(@Valid @RequestBody FrontendMetricsReq req,
                                       @AuthenticationPrincipal AuthPrincipal me) {
        collector.record(me.id(), req);
        return ResponseEntity.noContent().build();
    }
}
