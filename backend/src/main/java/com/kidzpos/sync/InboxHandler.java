package com.kidzpos.sync;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Handler d'application d'un événement {@code sync_inbox} sur l'état métier local.
 *
 * Chaque handler déclare son {@link #type()} (string strictement égale à
 * {@link com.kidzpos.domain.SyncInbox#getType()}). L'ensemble des handlers
 * détectés par Spring forme la WHITELIST stricte — un type d'événement
 * sans handler associé reste {@code processed=false} avec un log WARN.
 *
 * Contrat :
 *  - Le handler doit être idempotent : appel répété = effet identique au premier.
 *  - Le handler est invoqué DANS une transaction ouverte par le processor.
 *    Toute exception non-trappée fait rollback → la ligne reste à processed=false
 *    et sera retentée au prochain tick.
 *  - Le handler NE doit PAS bypasser les invariants métier (FK, contraintes).
 *    En cas de payload invalide ou ressource manquante, deux choix :
 *      (a) skip silencieux (return) → la ligne sera marquée processed=true,
 *          ne sera plus tentée. Bon choix pour "ressource non concernée".
 *      (b) throw IllegalArgumentException → la ligne reste à processed=false
 *          (loggée en ERROR). Bon choix pour "payload corrompu, peut-être
 *          réparable en amont".
 */
public interface InboxHandler {

    /** Type d'événement géré (ex: "product.created"). */
    String type();

    /**
     * Applique l'événement.
     *
     * @param eventId UUID de la ligne sync_inbox (peut servir à logger ou
     *                à dédupliquer si le handler crée des sous-entités).
     * @param payload Arbre JSON parsé du champ {@code payload} de l'événement.
     */
    void apply(UUID eventId, JsonNode payload);
}
