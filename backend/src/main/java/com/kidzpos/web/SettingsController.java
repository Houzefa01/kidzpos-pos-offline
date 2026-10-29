package com.kidzpos.web;

import com.kidzpos.domain.Settings;
import com.kidzpos.dto.Dtos.SettingsReq;
import com.kidzpos.events.EventBus;
import com.kidzpos.repo.SettingsRepository;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final SettingsRepository repo;
    private final EventBus bus;
    public SettingsController(SettingsRepository repo, EventBus bus) { this.repo = repo; this.bus = bus; }

    @GetMapping public Settings get() { return repo.findById(1L).orElseThrow(); }

    @PutMapping
    @Transactional
    public Settings update(@Valid @RequestBody SettingsReq r) {
        var s = repo.findById(1L).orElseThrow();
        s.setMaxDiscountPercent(r.maxDiscountPercent());
        s.setPointsPerAr(r.pointsPerAr());
        s.setArPerPoint(r.arPerPoint());
        s.setShopName(r.shopName());
        if (r.currency() != null && !r.currency().isBlank()) s.setCurrency(r.currency());
        var saved = repo.save(s);
        bus.publish("settings", "updated", saved);
        return saved;
    }
}
