package com.kidzpos.debug;

import com.kidzpos.domain.ConflictLog;
import com.kidzpos.domain.OperationLog;
import com.kidzpos.domain.QuarantineEvent;
import com.kidzpos.domain.SyncInbox;
import com.kidzpos.repo.ConflictLogRepository;
import com.kidzpos.repo.OperationLogRepository;
import com.kidzpos.repo.QuarantineEventRepository;
import com.kidzpos.repo.SyncInboxRepository;
import com.kidzpos.sync.NodeContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints DEBUG read-only pour le test local multi-serveur.
 *
 * Activation EXCLUSIVEMENT via {@code kidzpos.debug.enabled=true} (cf. scripts
 * de test). En production cette propriété est absente → le bean n'est même pas
 * créé → endpoints renvoient 404. Aucune surface d'attaque ajoutée hors test.
 *
 * Tout est lecture seule, aucune mutation, aucune logique métier. Requiert une
 * authentification valide via la chaîne de sécurité existante (couvert par
 * {@code anyRequest().authenticated()} ; aucun rôle spécifique exigé, l'endpoint
 * n'étant de toute façon instancié qu'en environnement de test/debug).
 *
 * Routes :
 *   GET /api/debug/status      — snapshot complet du nœud (compteurs sync)
 *   GET /api/debug/operations  — derniers événements operation_log
 *   GET /api/debug/inbox       — derniers événements sync_inbox
 *   GET /api/debug/conflicts   — derniers conflits détectés
 *   GET /api/debug/quarantine  — derniers événements quarantinés
 */
@RestController
@RequestMapping("/api/debug")
@ConditionalOnProperty(prefix = "kidzpos.debug", name = "enabled", havingValue = "true")
public class SyncDebugController {

    private final NodeContext nodeContext;
    private final String nodeId;
    private final String nodeRole;
    private final OperationLogRepository opLog;
    private final SyncInboxRepository inbox;
    private final ConflictLogRepository conflicts;
    private final QuarantineEventRepository quarantine;

    public SyncDebugController(NodeContext nodeContext,
                               @Value("${kidzpos.node.id:default}") String nodeId,
                               @Value("${kidzpos.node.role:standalone}") String nodeRole,
                               OperationLogRepository opLog,
                               SyncInboxRepository inbox,
                               ConflictLogRepository conflicts,
                               QuarantineEventRepository quarantine) {
        this.nodeContext = nodeContext;
        this.nodeId = nodeId;
        this.nodeRole = nodeRole;
        this.opLog = opLog;
        this.inbox = inbox;
        this.conflicts = conflicts;
        this.quarantine = quarantine;
    }

    /** Snapshot ultra-rapide de l'état du nœud — utile en début/fin de test. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", nodeId);
        node.put("role", nodeRole);
        node.put("storeId", nodeContext.storeId());
        node.put("storeScoped", nodeContext.isStoreScoped());

        Map<String, Object> opLogStats = new LinkedHashMap<>();
        opLogStats.put("total", opLog.count());
        opLogStats.put("unsynced", opLog.countBySyncedFalse());

        Map<String, Object> inboxStats = new LinkedHashMap<>();
        inboxStats.put("total", inbox.count());
        inboxStats.put("unprocessed", inbox.countByProcessedFalse());

        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("operationLog", opLogStats);
        sync.put("syncInbox", inboxStats);
        sync.put("quarantineEvents", quarantine.count());
        sync.put("conflictLog", conflicts.count());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", node);
        out.put("sync", sync);
        return out;
    }

    @GetMapping("/operations")
    public List<OperationLog> operations(@RequestParam(defaultValue = "20") int limit) {
        return opLog.findAll(PageRequest.of(0, clamp(limit), Sort.by("createdAt").descending()))
                .getContent();
    }

    @GetMapping("/inbox")
    public List<SyncInbox> inboxEvents(@RequestParam(defaultValue = "20") int limit) {
        return inbox.findAll(PageRequest.of(0, clamp(limit), Sort.by("createdAt").descending()))
                .getContent();
    }

    @GetMapping("/conflicts")
    public List<ConflictLog> recentConflicts(@RequestParam(defaultValue = "20") int limit) {
        return conflicts.findAll(PageRequest.of(0, clamp(limit), Sort.by("detectedAt").descending()))
                .getContent();
    }

    @GetMapping("/quarantine")
    public List<QuarantineEvent> recentQuarantine(@RequestParam(defaultValue = "20") int limit) {
        return quarantine.findAll(PageRequest.of(0, clamp(limit), Sort.by("createdAt").descending()))
                .getContent();
    }

    private static int clamp(int n) {
        return Math.min(Math.max(1, n), 200);
    }
}
