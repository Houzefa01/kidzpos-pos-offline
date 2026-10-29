package com.kidzpos.web;

import com.kidzpos.domain.Role;
import com.kidzpos.domain.User;
import com.kidzpos.dto.Dtos.*;
import com.kidzpos.repo.UserRepository;
import com.kidzpos.security.UserCache;
import com.kidzpos.sync.NodeContext;
import com.kidzpos.sync.OperationLogService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.kidzpos.events.EventBus;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final EventBus bus;
    private final UserCache userCache;
    private final NodeContext nodeContext;
    private final OperationLogService opLog;

    public UserController(UserRepository users, PasswordEncoder encoder, EventBus bus, UserCache userCache,
                          NodeContext nodeContext, OperationLogService opLog) {
        this.users = users; this.encoder = encoder; this.bus = bus; this.userCache = userCache;
        this.nodeContext = nodeContext;
        this.opLog = opLog;
    }

    /**
     * V22 — Admin (seul à accéder à cet endpoint via UI) voit TOUS les users
     * de TOUS les stores. La filtration V19 par nœud était trop stricte pour
     * la console admin globale.
     */
    @GetMapping
    public List<UserRes> list() {
        return users.findAll().stream().map(AuthController::toRes).toList();
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateUserReq req) {
        if (users.existsByEmailIgnoreCase(req.email())) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Email déjà utilisé"));
        }
        User u = User.builder()
                .id("u" + System.currentTimeMillis())
                .name(req.name())
                .email(req.email().toLowerCase())
                .passwordHash(encoder.encode(req.password()))
                .role(Role.valueOf(req.role()))
                .storeId(req.storeId())
                .active(req.active() == null ? true : req.active())
                .build();
        var saved = users.save(u);
        // V23 — journalise pour sync : le User complet (avec passwordHash) part
        // dans operation_log → matérialisé sur les autres nœuds.
        opLog.record("user.created", saved);
        var res = AuthController.toRes(saved);
        bus.publish("user", "created", res);
        return ResponseEntity.ok(res);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @Valid @RequestBody UpdateUserReq req) {
        var u = users.findById(id).orElse(null);
        if (u == null) return ResponseEntity.notFound().build();
        // Garde du dernier admin actif
        boolean willDemote = (req.role() != null && !"ADMIN".equals(req.role()) && u.getRole() == Role.ADMIN);
        boolean willDeactivate = (Boolean.FALSE.equals(req.active()) && u.isActive() && u.getRole() == Role.ADMIN);
        if ((willDemote || willDeactivate) && users.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Impossible : dernier admin actif"));
        }
        if (req.name() != null) u.setName(req.name());
        if (req.email() != null) u.setEmail(req.email().toLowerCase());
        if (req.role() != null) u.setRole(Role.valueOf(req.role()));
        if (req.storeId() != null) u.setStoreId(req.storeId().isBlank() ? null : req.storeId());
        if (req.active() != null) u.setActive(req.active());
        if (req.password() != null && !req.password().isBlank()) u.setPasswordHash(encoder.encode(req.password()));
        var saved = users.save(u);
        userCache.invalidate(id);
        // V23 — journalise pour sync.
        opLog.record("user.updated", saved);
        var res = AuthController.toRes(saved);
        bus.publish("user", "updated", res);
        return ResponseEntity.ok(res);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        var u = users.findById(id).orElse(null);
        if (u == null) return ResponseEntity.notFound().build();
        if (u.getRole() == Role.ADMIN && users.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Impossible : dernier admin"));
        }
        users.deleteById(id);
        userCache.invalidate(id);
        // V23 — journalise pour sync (id + storeId pour le routage du pull).
        opLog.record("user.deleted",
                java.util.Map.of("id", id, "storeId", u.getStoreId() == null ? "" : u.getStoreId()));
        bus.publish("user", "deleted", java.util.Map.of("id", id));
        return ResponseEntity.noContent().build();
    }
}
