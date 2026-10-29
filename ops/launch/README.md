# ops/launch — Scripts de lancement (100% hors-ligne)

Démarrage des nœuds **central** et **store** sans aucune connexion internet.

| Script | Rôle |
|---|---|
| `launch-central.sh` | Central — API `:8080`, UI `:3000`, DB `kidzpos_central` |
| `launch-store.sh` | Store s1 — API `:8081`, UI `:4001`, DB `kidzpos_store_s1`, push/pull → central |
| `launch-both.sh` | Lance le central puis le store |
| `stop.sh` | Arrête backends + frontends + Caddy |
| `serve-static.mjs` | Serveur statique SPA en **Node natif** (zéro dépendance npm) |
| `launch-central-tls.sh` · `launch-store-tls.sh` · `launch-both-tls.sh` | Variante HTTPS via Caddy (cf § HTTPS) |
| `Caddyfile` | Reverse proxy HTTPS : sert le frontend ET proxy l'API sous la même origine |

## Pourquoi c'est hors-ligne

- **Backend** : `java -jar` du JAR déjà buildé + PostgreSQL local + Flyway local. Aucun appel sortant au démarrage.
- **Frontend** : servi par `serve-static.mjs` qui n'importe que des modules `node:` (http, fs, path). **Pas de `npx serve`** (qui téléchargerait le paquet `serve` depuis internet).
- **Readiness** : testée via `/dev/tcp` (bash pur), pas de `curl`/proxy.

→ Les seules connexions sont **locales** : navigateur↔frontend, frontend/JDBC↔backend, store↔central (LAN).

## Prérequis (build = une seule fois, avec internet)

Le *lancement* est hors-ligne ; le *build* des artefacts ne l'est pas :

```bash
cd backend && mvn -DskipTests package          # → backend/target/kidzpos-backend-*.jar
./ops/test-env/build-frontends.sh              # → dist-central/ et dist-store/
# Bases (une fois) : psql -U postgres -f ops/test-env/init-test-databases.sql
```

## Lancer

```bash
./ops/launch/launch-both.sh          # central + store
# ou séparément :
./ops/launch/launch-central.sh
./ops/launch/launch-store.sh
./ops/launch/stop.sh                 # tout arrêter
```

UIs : Central → http://localhost:3000 · Store → http://localhost:4001 (admin@kidzpos.com / `admin123`).

## Vérifier le hors-ligne

```bash
# Bloque tout HTTP/npm externe (localhost autorisé), puis lance :
export http_proxy=http://127.0.0.1:9 https_proxy=http://127.0.0.1:9 \
       no_proxy=localhost,127.0.0.1 npm_config_offline=true
./ops/launch/launch-both.sh
curl -s http://localhost:8080/actuator/health   # {"status":"UP"} → OK hors-ligne
```

Tout est surchargeable par variable d'env (`SERVER_PORT`, `FRONTEND_PORT`, `DB_*`,
`JWT_SECRET`, `KIDZPOS_SYNC_CENTRAL_URL`, `KIDZPOS_ADMIN_PASSWORD`, `LAN_IP`, …).

---

## Multi-machine (accès depuis d'autres machines du LAN)

Par défaut le frontend est buildé sur `localhost` → une autre machine appelle
**son propre** localhost et échoue. Il faut le builder sur l'**IP LAN** du serveur.
Les scripts de lancement posent déjà : cookie `SameSite=Lax` + `Secure=false`
(obligatoire en HTTP hors localhost) et CORS autorisant l'IP LAN.

### Scénario B — 1 serveur, plusieurs clients (caisses/tablettes)

Central **et** store sur cette machine ; les autres machines ouvrent l'UI via l'IP.

```bash
./ops/launch/build-lan.sh both        # rebuild dist-central(→IP:8080) + dist-store(→IP:8081)
./ops/launch/launch-both.sh           # affiche l'URL LAN à ouvrir
# Depuis une autre machine du LAN :
#   Central → http://<IP-serveur>:3000     Store → http://<IP-serveur>:4001
```

`build-lan.sh both 192.168.1.10` pour forcer l'IP (sinon auto-détectée).

### Scénario A — 2 machines séparées

Central sur la machine A, store sur la machine B (chacun son backend `:8080`).

```bash
# ── Machine A (central, IP par ex. 192.168.1.10) ──
./ops/launch/build-lan.sh central 192.168.1.10        # → http://192.168.1.10:8080
./ops/launch/launch-central.sh

# ── Machine B (store, IP par ex. 192.168.1.20) ──
#   backend store sur :8080, frontend câblé sur son IP, sync → central
STORE_API_URL=http://192.168.1.20:8080 ./ops/launch/build-lan.sh store
SERVER_PORT=8080 FRONTEND_PORT=3000 \
KIDZPOS_SYNC_CENTRAL_URL=http://192.168.1.10:8080 \
  ./ops/launch/launch-store.sh
```

> Le **démarrage** reste hors-ligne. Si le central est injoignable, le store
> démarre quand même et retente push/pull (backoff) — sans casse.

### Diagnostic rapide multi-machine

| Symptôme | Cause | Fix |
|---|---|---|
| « seul localhost se connecte » | frontend buildé sur `localhost` | `build-lan.sh` (câble l'IP) |
| login échoue / déconnexion au F5 sur IP | cookie `Secure=true` en HTTP | défaut déjà `Secure=false`+`Lax` |
| erreurs CORS dans la console | origine LAN absente | défaut inclut déjà `http://<IP>:<port>` |
| pare-feu | ports 8080/8081/3000/4001 fermés | ouvrir en entrée sur le LAN |

---

## HTTPS (Caddy reverse proxy)

Pour servir KidzPOS en HTTPS sur le LAN, on met **Caddy** devant Spring : il
sert le frontend (`dist-*`) **et** reverse-proxy l'API/SSE/Actuator sous la
**même origine HTTPS**. Avantages : plus de CORS du tout, cookies `Secure=true`
+ `SameSite=Lax`, certs auto via la CA locale Caddy (zéro download).

```
┌─ Navigateur ──────────────────────────────────────┐
│ https://<IP>/  →  SPA  +  /api/* → backend  +  SSE│
└──────┬────────────────────────────────────────────┘
       │ TLS
┌──────▼──── Caddy (:443 central, :4443 store) ─────┐
│  file_server dist-central/        ←── SPA          │
│  reverse_proxy /api/* /actuator/* /v3/api-docs/*  │
│             → 127.0.0.1:8080 (HTTP loopback)       │
└────────────────────────────────────────────────────┘
```

### Prérequis (une seule fois, avec internet)

```bash
sudo pacman -S caddy            # Arch  (ou: apt-get install caddy)
caddy version                   # ≥ 2.7
# Bind sur port <1024 sans root (sinon HTTPS_PORT=8443 dans les commandes) :
sudo setcap cap_net_bind_service=+ep "$(command -v caddy)"
```

### Lancement HTTPS hors-ligne

```bash
./ops/launch/build-lan.sh both --https   # frontends câblés https://<IP>(:4443)
./ops/launch/launch-both-tls.sh          # central :443 + store :4443
./ops/launch/stop.sh
```

Ports par défaut :
- Central → `https://<IP>:443` (omis dans l'URL navigateur)
- Store   → `https://<IP>:4443`

Surcharge : `HTTPS_PORT=8443 ./ops/launch/launch-central-tls.sh` (utile pour
tester sans `setcap`).

### Premier run : CA locale Caddy

`tls internal` génère une CA Caddy **locale** la première fois. Sur le serveur,
Caddy l'installe automatiquement dans le trust store du compte courant
(NSS/Firefox/Chrome). Pour **les machines clientes** (autres caisses), copier
et installer manuellement le root cert :

```bash
# Sur le serveur :
cat ~/.local/share/caddy/pki/authorities/local/root.crt   # → fournir à chaque client

# Sur Linux client :
sudo cp root.crt /usr/local/share/ca-certificates/kidzpos-ca.crt
sudo update-ca-certificates

# Sur Android :  Paramètres → Sécurité → Crypter / Identifiants → Installer
# Sur iOS    :  Réglages → Général → VPN et gestion d'appareil → Installer le profil
```

Sans cette étape, le navigateur affiche « non sécurisé » mais l'application
reste fonctionnelle (clic « Continuer »).

### Choix de configuration côté cookie / CORS

| Mode | `SameSite` | `Secure` | `CORS_ALLOWED_ORIGINS` |
|---|---|---|---|
| HTTP LAN (`launch-*.sh`) | `Lax` | `false` | inclut `http://<IP>:port` |
| **HTTPS Caddy** (`launch-*-tls.sh`) | `Lax` | `true` | restreint loopback (même origine) |

Toutes ces valeurs sont **surchargeables** par variable d'env :
`KIDZPOS_AUTH_COOKIE_SECURE=false` (debug seulement), `CORS_ALLOWED_ORIGINS=…`,
`KIDZPOS_HTTPS_ADDRS="https://kidzpos.local:443"` pour ajouter un FQDN.

### Sync store ↔ central en HTTPS

Si store et central tournent sur la **même machine**, garder
`KIDZPOS_SYNC_CENTRAL_URL=http://localhost:8080` (loopback HTTP) — c'est plus
simple que de configurer un truststore Java avec la CA Caddy.

Si store et central sur **machines séparées** avec central TLS, deux options :

1. **Garder HTTP entre les nœuds** (recommandé en LAN privé sans accès externe) :
   ouvrir le port backend `:8080` du central sur l'IP LAN, et :
   ```bash
   KIDZPOS_SYNC_CENTRAL_URL=http://<IP-central>:8080 ./ops/launch/launch-store-tls.sh
   ```
   Le TLS sert uniquement les **navigateurs** ; le trafic store↔central reste
   HTTP intra-LAN (acceptable si le LAN est isolé).

2. **TLS de bout en bout** : il faut alors fournir au JVM du store un
   truststore contenant la CA Caddy du central :
   ```bash
   # Une fois sur le store, depuis le root.crt du central :
   keytool -import -alias caddy-central -file root.crt \
           -keystore ~/.kidzpos-truststore.jks -storepass changeit -noprompt
   JDK_JAVA_OPTIONS="-Djavax.net.ssl.trustStore=$HOME/.kidzpos-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit" \
   KIDZPOS_SYNC_CENTRAL_URL=https://<IP-central>:443 \
     ./ops/launch/launch-store-tls.sh
   ```

### Diagnostic rapide HTTPS

| Symptôme | Cause | Fix |
|---|---|---|
| navigateur « non sécurisé » sur le client | CA Caddy pas installée | copier root.crt → trust store client |
| `curl: (35) tlsv1 alert internal error` | hôte non listé dans `KIDZPOS_HTTPS_ADDRS` | ajouter le FQDN/IP, relancer |
| « bind: permission denied » sur :443 | port privilégié sans capacité | `sudo setcap cap_net_bind_service=+ep $(command -v caddy)` |
| 504 sur SSE | buffering proxy actif | déjà géré par `flush_interval -1` dans le Caddyfile |
| store ne syncronise plus en TLS | JVM ne fait pas confiance à la CA | option 1 (HTTP loopback intra-LAN) OU truststore (option 2) |

