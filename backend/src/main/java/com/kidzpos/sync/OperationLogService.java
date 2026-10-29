package com.kidzpos.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kidzpos.domain.OperationLog;
import com.kidzpos.repo.OperationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Écriture du journal d'opérations métier (ETAPE 3 plan multi-serveurs).
 *
 * CONTRAT DE NON-RÉGRESSION (essentiel) :
 *   - Toute exception est avalée. Le log NE DOIT JAMAIS faire échouer la
 *     mutation métier appelante (vente, refund, ajustement, transfert).
 *   - Insertion dans la transaction courante : si la transaction parent
 *     rollback, la ligne est rollback aussi (cohérent). Conséquence : on
 *     appelle {@link #record(String, Object)} APRÈS le saveAndFlush du
 *     métier, pour ne logguer que des opérations effectivement persistées.
 *   - Aucun side-effect réseau, aucun event publié — pur INSERT.
 *
 * Lié à :
 *   - V13__operation_log.sql (DDL)
 *   - {@link OperationLog}, {@link OperationLogRepository}
 *   - {@link SyncController} (futurs consumers)
 */
@Service
public class OperationLogService {

    private static final Logger log = LoggerFactory.getLogger(OperationLogService.class);

    private final OperationLogRepository repo;
    private final ObjectMapper mapper;
    private final NodeContext nodeContext;

    public OperationLogService(OperationLogRepository repo, ObjectMapper mapper, NodeContext nodeContext) {
        this.repo = repo;
        this.mapper = mapper;
        this.nodeContext = nodeContext;
    }

    /**
     * Enregistre une opération métier.
     *
     * V18 : tag automatique avec {@code NodeContext.storeId()} — toute opération
     * journalisée sur ce serveur appartient au magasin qu'il dessert. Si le nœud
     * n'a pas de storeId configuré (legacy / serveur central pur), storeId reste
     * NULL en base (rétrocompat).
     *
     * @param type     identifiant logique (ex: "sale.checkout", "stock.adjust").
     *                 Limité à 64 caractères (cf colonne SQL).
     * @param payload  objet sérialisable Jackson (entité JPA, DTO, Map…).
     *                 {@code null} → payload JSON "{}".
     */
    public void record(String type, Object payload) {
        try {
            String json = payload == null ? "{}" : mapper.writeValueAsString(payload);
            // V21-bidir : extraire storeId DU PAYLOAD prioritairement.
            //   - Sale entity / Product / StockMovement ont un champ "storeId" top-level
            //   - StockController.adjust passe un Map avec "storeId"
            //   - StockController.transfer passe "sourceStoreId" (pas "storeId") — on tag
            //     alors avec sourceStoreId (le magasin d'origine du mouvement)
            // Fallback : NodeContext.storeId() pour les events sans storeId apparent.
            // Bénéfice : sur le CENTRAL (NodeContext.storeId=null), un sale.checkout
            // créé pour s1 est tagué storeId=s1 → visible au pull du store s1.
            String resolvedStoreId = extractStoreIdFromJson(json);
            if (resolvedStoreId == null) resolvedStoreId = nodeContext.storeId();
            repo.save(OperationLog.builder()
                    .id(UUID.randomUUID())
                    .type(type)
                    .payload(json)
                    .createdAt(Instant.now())
                    .synced(false)
                    .storeId(resolvedStoreId)
                    .build());
        } catch (Exception e) {
            // Best-effort : on log mais on n'interrompt PAS le flux métier.
            log.warn("operation_log skipped (type={}): {}", type, e.getMessage());
        }
    }

    /**
     * Extrait le storeId du payload JSON. Cherche dans l'ordre :
     *   "storeId" (entités Sale/Product/Customer, Map adjust)
     *   "sourceStoreId" (Map transfer)
     * Retourne null si aucun trouvé.
     */
    private String extractStoreIdFromJson(String json) {
        try {
            var node = mapper.readTree(json);
            var direct = node.get("storeId");
            if (direct != null && !direct.isNull()) return direct.asText();
            var source = node.get("sourceStoreId");
            if (source != null && !source.isNull()) return source.asText();
        } catch (Exception ignored) {
            // payload non-JSON ou erreur → on retombe sur NodeContext
        }
        return null;
    }
}
