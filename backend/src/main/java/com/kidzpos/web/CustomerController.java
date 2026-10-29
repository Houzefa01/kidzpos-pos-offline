package com.kidzpos.web;

import com.kidzpos.domain.Customer;
import com.kidzpos.dto.Dtos.CustomerReq;
import com.kidzpos.events.EventBus;
import com.kidzpos.repo.CustomerRepository;
import com.kidzpos.security.JwtAuthFilter.AuthPrincipal;
import com.kidzpos.sync.NodeContext;
import com.kidzpos.sync.OperationLogService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final CustomerRepository repo;
    private final EventBus bus;
    private final NodeContext nodeContext;
    private final OperationLogService opLog;

    public CustomerController(CustomerRepository repo, EventBus bus, NodeContext nodeContext,
                              OperationLogService opLog) {
        this.repo = repo;
        this.bus = bus;
        this.nodeContext = nodeContext;
        this.opLog = opLog;
    }

    @GetMapping
    public List<Customer> list() {
        if (nodeContext.isStoreScoped()) {
            return repo.findAllByStoreIdOrLegacy(nodeContext.storeId());
        }
        return repo.findAll();
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CustomerReq r) {
        if (isBlank(r.name()) && isBlank(r.phone())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nom ou téléphone requis"));
        }
        // V21-bidir : storeId vient en priorité du request (admin sur le central
        // peut cibler un magasin précis), sinon fallback NodeContext (cas store
        // local où le client est implicitement celui du nœud).
        String effectiveStoreId = (r.storeId() != null && !r.storeId().isBlank())
                ? r.storeId() : nodeContext.storeId();
        var c = repo.save(Customer.builder()
                .id(r.id() != null ? r.id() : "c" + System.currentTimeMillis())
                .name(r.name()).phone(r.phone()).email(r.email())
                .points(r.points() == null ? 0 : r.points())
                .totalSpent(0).visits(0)
                .createdAt(Instant.now())
                .storeId(effectiveStoreId)
                .build());
        // V21-bidir : journalise pour propagation sync ↔ central/store.
        opLog.record("customer.created", c);
        bus.publish("customer", "created", c);
        return ResponseEntity.ok(c);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @Valid @RequestBody CustomerReq r,
                                    @AuthenticationPrincipal AuthPrincipal me) {
        var c = loadInScope(id);
        if (c == null) return ResponseEntity.notFound().build();
        if (r.name() != null) c.setName(r.name());
        if (r.phone() != null) c.setPhone(r.phone());
        if (r.email() != null) c.setEmail(r.email());
        if (r.points() != null) {
            if (!"ADMIN".equals(me.role())) {
                return ResponseEntity.status(403).body(Map.of("error", "Édition des points réservée à l'admin"));
            }
            c.setPoints(r.points());
        }
        if (c.getStoreId() == null && nodeContext.isStoreScoped()) {
            c.setStoreId(nodeContext.storeId());
        }
        try {
            var saved = repo.save(c);
            // V21-bidir : journalise pour propagation.
            opLog.record("customer.updated", saved);
            bus.publish("customer", "updated", saved);
            return ResponseEntity.ok(saved);
        } catch (ObjectOptimisticLockingFailureException e) {
            return ResponseEntity.status(412)
                    .body(Map.of("error", "Le client a été modifié entretemps, rechargez"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        var c = loadInScope(id);
        if (c == null) return ResponseEntity.notFound().build();
        repo.delete(c);
        // V21-bidir : payload inclut storeId pour que OperationLogService
        // tag correctement l'event → filtrable au pull par le store cible.
        opLog.record("customer.deleted",
                Map.of("id", id, "storeId", c.getStoreId() == null ? "" : c.getStoreId()));
        bus.publish("customer", "deleted", Map.of("id", id));
        return ResponseEntity.noContent().build();
    }

    private Customer loadInScope(String id) {
        if (nodeContext.isStoreScoped()) {
            return repo.findByIdAndStoreIdOrLegacy(id, nodeContext.storeId()).orElse(null);
        }
        return repo.findById(id).orElse(null);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
