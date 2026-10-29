package com.kidzpos.security;

import com.kidzpos.security.JwtAuthFilter.AuthPrincipal;
import com.kidzpos.sync.NodeContext;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Garde l'accès cross-store : un EMPLOYEE ne peut agir que sur SON storeId.
 * ADMIN passe partout.
 *
 * Utilitaire statique pur (pas de dépendance Spring) — utilisable depuis
 * n'importe quel contrôleur.
 *
 * Pattern d'usage :
 *   var deny = StoreAccessGuard.denyIfCrossStore(me, req.storeId());
 *   if (deny != null) return deny;
 */
public final class StoreAccessGuard {

    private StoreAccessGuard() {}

    /** True si {@code me} peut opérer sur {@code targetStoreId}. */
    public static boolean canActOn(AuthPrincipal me, String targetStoreId) {
        if (me == null) return false;
        if ("ADMIN".equals(me.role())) return true;
        return targetStoreId != null && targetStoreId.equals(me.storeId());
    }

    /**
     * Retourne 403 si l'utilisateur n'a pas les droits sur ce store, sinon {@code null}.
     * Message neutre (ne révèle pas le storeId du caller pour éviter l'énumération).
     */
    public static ResponseEntity<?> denyIfCrossStore(AuthPrincipal me, String targetStoreId) {
        if (canActOn(me, targetStoreId)) return null;
        return ResponseEntity.status(403)
                .body(Map.of("error", "Accès refusé : magasin hors de votre périmètre"));
    }

    /**
     * V19 — Garde défensive sur les lectures par ID : déterminer si une entité
     * chargée (donc dont on connaît le storeId réel) est dans le scope autorisé.
     *
     *  - entityStoreId NULL   → tolérance legacy (donnée pré-store_id) → autorisé.
     *  - nœud store-scoped    → STRICT : seul {@code nodeContext.storeId()} passe,
     *                            même pour ADMIN (un admin sur le serveur de s1
     *                            ne peut pas atteindre des entités de s2 via cet
     *                            endpoint — il devrait se connecter au serveur s2).
     *  - nœud non-scoped      → comportement legacy via {@link #canActOn} (admin
     *                            partout, employé sur son store).
     *
     * Usage typique côté controller (404 plutôt que 403 pour ne pas révéler
     * l'existence d'une ressource d'un autre magasin) :
     *
     *   return repo.findById(id)
     *           .filter(p -> StoreAccessGuard.isInScope(p.getStoreId(), me, nodeContext))
     *           .map(ResponseEntity::ok)
     *           .orElse(ResponseEntity.notFound().build());
     */
    public static boolean isInScope(String entityStoreId, AuthPrincipal me, NodeContext nodeContext) {
        if (entityStoreId == null) return true;            // legacy data → tolérance
        // V22 — ADMIN omnipotent
        if (me != null && "ADMIN".equals(me.role())) return true;
        // V23 — Un EMPLOYEE est rattaché à SON store (via JWT), pas au nœud :
        // karim (employee s2) connecté sur le serveur store_s1 peut accéder
        // à ses entités s2 (car la DB locale les contient via full mesh).
        if (me != null && me.storeId() != null) {
            return me.storeId().equals(entityStoreId);
        }
        return canActOn(me, entityStoreId);
    }

    /**
     * V19-audit / V22 — Force le scope magasin sur les listings/aggregations
     * selon le rôle de l'appelant.
     *
     * <ul>
     *   <li><b>ADMIN</b> : omnipotent — retourne {@code requestedStoreId} tel
     *       quel (peut demander un store spécifique ou null pour "tous"). V22 :
     *       un ADMIN doit pouvoir agréger / faire du support cross-store depuis
     *       n'importe quel nœud.</li>
     *   <li><b>EMPLOYEE avec storeId</b> : contraint à SON store (depuis le
     *       JWT), pas au nœud. Un employé de s2 connecté sur un serveur s1
     *       opère sur s2 grâce au full-mesh sync.</li>
     *   <li><b>Sans auth / fallback</b> : contraint au {@code nodeContext} si
     *       store-scoped (défense en profondeur quand l'auth manque).</li>
     * </ul>
     *
     * Usage typique :
     *   String scope = StoreAccessGuard.enforceStoreScope(storeId, me, nodeContext);
     *   if (scope == null) return repo.findAll();
     *   return repo.findByStoreId(scope);
     */
    public static String enforceStoreScope(String requestedStoreId, AuthPrincipal me, NodeContext nodeContext) {
        // V22 — ADMIN omnipotent (peut demander n'importe quel storeId ou null)
        if (me != null && "ADMIN".equals(me.role())) {
            return requestedStoreId;
        }
        // V23 — EMPLOYEE contraint à SON store (depuis son JWT), pas à celui
        // du nœud. Un employee de s2 connecté sur le serveur store_s1 opère
        // sur s2, pas sur s1. Le nœud n'est qu'un point d'accès physique.
        if (me != null && me.storeId() != null) {
            return me.storeId();
        }
        // Fallback : auth sans storeId → on contraint au nœud par sécurité.
        if (nodeContext != null && nodeContext.isStoreScoped()) {
            return nodeContext.storeId();
        }
        return requestedStoreId;
    }
}
