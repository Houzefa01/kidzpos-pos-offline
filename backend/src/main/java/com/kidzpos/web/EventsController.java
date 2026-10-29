package com.kidzpos.web;

import com.kidzpos.events.EventBus;
import com.kidzpos.events.EventTokenStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/events")
public class EventsController {

    private final EventBus bus;
    private final EventTokenStore tokens;

    public EventsController(EventBus bus, EventTokenStore tokens) {
        this.bus = bus;
        this.tokens = tokens;
    }

    /**
     * M5 : émet un token à usage unique (60s) pour ouvrir un flux SSE.
     * Auth JWT requise (cf SecurityConfig.anyRequest().authenticated()).
     */
    @PostMapping("/auth")
    public Map<String, Object> auth() {
        return Map.of("token", tokens.issue(), "expiresInSec", EventTokenStore.TTL_MS / 1000);
    }

    /**
     * Le stream reste permitAll côté SecurityConfig (EventSource ne pose pas
     * de header), mais on exige un eventToken consommable en query param.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam(required = false) String token) {
        if (!tokens.consume(token)) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(bus.subscribe());
    }
}
