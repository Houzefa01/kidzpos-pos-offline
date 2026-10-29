# KidzPOS

Application de caisse (POS) **offline-first** pour une chaîne de magasins — contexte Madagascar, devise **Ariary**.

Fonctionne en LAN (sans Internet), sur Internet, et continue de servir même quand le serveur tombe (cache local + file d'attente + synchronisation automatique au retour réseau).

```
┌──────────────┐   LAN / Internet   ┌────────────────────┐
│  Caisse 1    │ ─────  HTTPS  ───► │  Backend Spring    │
│  (PWA React) │ ◄────   SSE   ────  │  Boot + PostgreSQL │
└──────────────┘                    └────────────────────┘
     ▲  cache localStorage + outbox (rejoue au retour réseau)
     │
┌──────────────┐   tous les changements diffusés en temps réel via SSE
│  Caisse 2…N  │   + synchronisation magasin ↔ central (fédération)
└──────────────┘
```

## Stack

| Couche | Technologie |
|---|---|
| Frontend | React 18 + TypeScript + Vite, PWA installable, Zustand (offline-first), shadcn/ui, Tailwind |
| Backend | Spring Boot 3.3 / Java 17, Spring Security (JWT + refresh tokens rotatifs), JPA, Actuator |
| Base | PostgreSQL 14+ (seul SGBD supporté), migrations Flyway V1–V22 |
| Temps réel | Server-Sent Events authentifiés |
| Sync fédérée | Push/Pull magasin ↔ central, inbox idempotente, clés API par magasin |
| Observabilité | Micrometer/Prometheus, Grafana, Alertmanager, runbooks (`ops/`) |

## Fonctionnalités

- **POS** : panier, remises (plafonnées par rôle), modes de paiement (espèces, carte, mobile money), fidélité (points), reçu PDF.
- **Offline-first** : chaque caisse garde un cache local + une file d'attente (outbox) rejouée à la reconnexion ; mutations idempotentes (pas de double-vente au replay).
- **Multi-magasin** : isolation stricte par magasin (un employé n'agit que sur le sien), admin omnipotent, synchronisation vers un central.
- **Stock** : ajustements, transferts inter-magasins, mouvements tracés, soft-delete produit.
- **Gestion** : produits, clients/fidélité, utilisateurs & rôles, paramètres, taux de change EUR↔Ar, tableau de bord (Recharts).

## Démarrage rapide (LAN d'un magasin)

Pré-requis : **Java 17+**, **PostgreSQL 14+**, **Node 18+**.

```bash
# 1. Base de données (voir backend/README.md § 2.1 pour le détail)
sudo -u postgres psql -c "CREATE DATABASE kidzpos;"

# 2. Frontend
npm install
npm run build          # build de prod dans dist/

# 3. Backend
cd backend && mvn package -DskipTests

# 4. Serveur LAN complet (backend + frontend statique)
./start-server.sh        # démarre  ·  status  ·  stop  ·  rebuild
```

Installation détaillée, configuration prod (HTTPS, CORS, secrets) et fédération multi-magasin : **`backend/README.md`** et **`ops/`**.

## Tests

```bash
npm test                 # frontend (Vitest) — 144 tests
npm run lint             # eslint
cd backend && mvn test   # backend (JUnit + TestContainers Postgres réel) — 97 tests
```

La CI (`.github/workflows/ci.yml`) exécute `lint + tsc --noEmit + tests` (front) et `compile + verify` TestContainers (back) sur chaque PR.

## Configuration

Toutes les variables sont optionnelles avec des valeurs par défaut (cf `.env.example` et `application.yml`). À durcir **obligatoirement** en production publique :

| Variable | Rôle |
|---|---|
| `JWT_SECRET` | Signature des JWT (≥ 32 octets, sinon refus de démarrer) |
| `DB_HOST/PORT/NAME/USER/PASSWORD` | Connexion PostgreSQL |
| `CORS_ALLOWED_ORIGINS` | Origines autorisées (mettre votre domaine HTTPS, sans wildcard) |
| `KIDZPOS_AUTH_COOKIE_SECURE` | `true` derrière HTTPS |

## Documentation

- `backend/README.md` — installation complète, prod, dépannage.
- `ops/` — déploiement, monitoring, sauvegarde, runbooks d'incident.
- `CLAUDE.md` — architecture détaillée et conventions de code.
- `TODO_FUTURE.md` — dette technique suivie (résolu ✅ / à faire 🟡).

## Licence

Propriétaire — tous droits réservés. Voir [`LICENSE`](./LICENSE).
