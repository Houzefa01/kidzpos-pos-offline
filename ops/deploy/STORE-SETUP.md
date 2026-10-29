# Configurer un store pour la synchronisation avec le central distant

Une fois ton **central déployé** sur la VM cloud (voir [`README.md`](README.md)),
il reste à connecter les nœuds **store** (chaque magasin sur son LAN local)
pour qu'ils poussent et tirent les changements vers/depuis ce central distant.

## Architecture

```
┌─ Magasin (LAN privé) ────────────────────┐    ┌─ Cloud (Oracle/Google) ──────┐
│                                           │    │                              │
│  ┌─ Caisses (navigateurs sur LAN) ──┐    │    │   https://central.dom.com   │
│  │ https://192.168.1.10:4443        │    │    │                              │
│  └───────────┬──────────────────────┘    │    │   Caddy :443                 │
│              │ HTTPS (Caddy local)         │    │     └→ Spring :8080         │
│  ┌───────────▼────────────────────┐       │    │     PostgreSQL :5432         │
│  │ Backend store :8081 (loopback) │ ──────┼────┼──→ /api/sync/push           │
│  │ PostgreSQL store local         │ ◄────┼────┼──  /api/sync/pull           │
│  │                                 │       │    │                              │
│  └─────────────────────────────────┘       │    │  Reçoit X-Sync-Api-Key       │
│                                             │    │       + X-Sync-Store-Id     │
└─────────────────────────────────────────────┘    └──────────────────────────────┘
```

Chaque magasin a sa propre clé API. Le central la valide (hash bcrypt en DB)
et lie chaque event à son store d'origine.

## Étape 1 (sur le central) — Provisionner une clé par store

Depuis ta machine, pour chaque magasin :

```bash
./ops/deploy/issue-store-key.sh https://central.tondomaine.com \
    admin@kidzpos.com '<mot-de-passe-admin>' \
    s1 'Magasin Antananarivo'
```

Sortie typique :
```
✅ Clé générée — À CONSERVER MAINTENANT
  storeId : s1
  keyId   : 9d2c…
  KEY     : kzp_2f4b7c9e8a1d6b3c5a8e2f1d…   ← UNIQUE chance de la voir
```

⚠ La clé est affichée **une seule fois**. Le central ne stocke qu'un hash.
Si tu la perds, révoque-la (`DELETE /api/sync/keys/{id}`) et en émets une nouvelle.

> 💡 Le `storeId` doit correspondre à une ligne existante dans la table
> `stores` du central. Crée le magasin via l'UI Settings → Magasins avant
> d'émettre sa clé.

## Étape 2 (sur le serveur du magasin) — Renseigner les env vars

Que tu lances le store en HTTP (`launch-store.sh`) ou HTTPS (`launch-store-tls.sh`),
3 variables d'environnement sont indispensables :

```bash
export KIDZPOS_NODE_STORE_ID=s1                                      # identique au storeId du central
export KIDZPOS_SYNC_CENTRAL_URL=https://central.tondomaine.com       # URL publique du central
export KIDZPOS_SYNC_API_KEY=kzp_2f4b7c9e8a1d6b3c5a8e2f1d…            # clé reçue à l'étape 1
```

Plus simplement, ajoute-les dans un fichier `~/.kidzpos-store.env` :

```bash
# ~/.kidzpos-store.env (chmod 600)
KIDZPOS_NODE_STORE_ID=s1
KIDZPOS_SYNC_CENTRAL_URL=https://central.tondomaine.com
KIDZPOS_SYNC_API_KEY=kzp_2f4b7c9e8a1d6b3c5a8e2f1d…
KIDZPOS_NODE_ID=store-s1
JWT_SECRET=<openssl rand -hex 32>         # propre au store
KIDZPOS_ADMIN_PASSWORD=<mot de passe admin local>
```

Puis lance le store en sourçant ce fichier :

```bash
set -a; source ~/.kidzpos-store.env; set +a
./ops/launch/launch-both-tls.sh            # store en HTTPS local (Caddy)
# ou:
./ops/launch/launch-both.sh                # store en HTTP LAN
```

## Étape 3 — Vérifier la sync

Sur le serveur du magasin, regarde les logs :

```bash
tail -f logs/store-s1.log | grep -i sync
```

Tu dois voir, dans les 30s suivant un changement local :
```
[sync-push] sent 3 ops to central — ack
[sync-pull] received 0 new ops from central
```

Si rien : vérifie que `KIDZPOS_SYNC_CENTRAL_URL` est joignable depuis le store :
```bash
curl -sf https://central.tondomaine.com/actuator/health
# {"status":"UP"}
```

Sur le central :
```bash
ssh ubuntu@<ip-vm> 'sudo journalctl -u kidzpos -n 100 | grep -i sync'
# [sync-push] received 3 ops from store=s1
# [sync-inbox] processed 3 ops from store=s1
```

## Que se passe-t-il si le LAN du magasin perd internet ?

**Rien ne casse.** KidzPOS est offline-first :
- Les caisses du magasin continuent à vendre normalement (backend store local)
- Les ops sont stockées dans `operation_log` du store
- Quand internet revient, `SyncScheduler` reprend le push automatiquement (backoff exponentiel)
- Le pull rattrape les changements d'autres magasins

Aucune action manuelle requise — la sync est conçue pour être résiliente aux coupures
intermittentes.

## Cas particulier : 2 stores → même central

Refaire l'étape 1 avec un autre `storeId` (`s2`, `s3`…), puis l'étape 2 sur chaque
serveur magasin. Chaque store a SA clé, son storeId. Le central agrège tout et
redistribue à tous les nœuds via pull (full-mesh depuis V22).

## Sécurité

- `KIDZPOS_SYNC_API_KEY` = secret long-terme → traiter comme un mot de passe.
  Stocker dans un fichier `chmod 600`. JAMAIS dans git.
- Si compromission suspectée : révoquer côté central, regénérer.
  ```bash
  # Lister les clés actives
  curl -sf -H "Authorization: Bearer $JWT" https://central.tondomaine.com/api/sync/keys
  # Révoquer
  curl -sf -X DELETE -H "Authorization: Bearer $JWT" \
       https://central.tondomaine.com/api/sync/keys/<keyId>
  ```
- Le central rate-limite le sync (cf `application.yml`) → un store compromis ne
  peut pas spammer.
- `X-Sync-Store-Id` cross-validé contre la clé : un store qui essaierait de
  pousser pour `s2` avec la clé de `s1` est rejeté en 403.

## Mise à jour applicative du store

Comme le central, mais en local sur la machine du magasin :

```bash
# Sur le serveur du magasin, après git pull
cd /chemin/vers/kidzpos
git pull
(cd backend && mvn -DskipTests package)
./ops/launch/build-lan.sh store --https
./ops/launch/stop.sh && set -a; source ~/.kidzpos-store.env; set +a; ./ops/launch/launch-both-tls.sh
```

Aucun impact côté central — chaque store se met à jour indépendamment.
