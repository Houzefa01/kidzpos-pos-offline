package com.kidzpos.sync;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Identité de magasin du nœud (serveur local).
 *
 * Résolution unique et explicite via {@code kidzpos.node.store-id} (config).
 * Lu une fois au démarrage, immuable ensuite (pas de header HTTP runtime ni
 * de contexte session — la machine physique dessert UN magasin et c'est tout).
 *
 * Si non configuré (cas legacy ou serveur central pur agrégateur) :
 * {@link #storeId()} retourne {@code null}. Le code consommateur traite NULL
 * comme "non-store-scoped" → comportement rétrocompatible.
 *
 * Centralisé dans un bean pour éviter de dupliquer le {@code @Value} dans
 * chaque service et pour faciliter les futurs ajouts de métadonnées (region,
 * tenant, etc.) sans toucher aux call-sites.
 */
@Component
public class NodeContext {

    private final String storeId;

    public NodeContext(@Value("${kidzpos.node.store-id:}") String storeId) {
        this.storeId = (storeId == null || storeId.isBlank()) ? null : storeId.trim();
    }

    /** Identifiant du magasin servi par ce nœud, ou {@code null} si non configuré. */
    public String storeId() {
        return storeId;
    }

    /** {@code true} ssi le nœud a un magasin configuré (vrai en profil "local"). */
    public boolean isStoreScoped() {
        return storeId != null;
    }
}
