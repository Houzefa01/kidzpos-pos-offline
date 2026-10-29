# ops/launch/arch — Arch Linux

Les scripts bash dans `ops/launch/` (`launch-central.sh`, `launch-store.sh`,
`launch-both.sh`, `launch-*-tls.sh`, `stop.sh`, `build-lan.sh`) marchent
**nativement sur Arch Linux**. Ce dossier ne contient que les pas
Arch-spécifiques (paquets pacman, init Postgres).

## Installation des prérequis (une fois)

```bash
sudo bash ops/launch/arch/install.sh
```

Le script installe via `pacman -S --needed` :
- `jdk17-openjdk` + `maven`
- `postgresql` (+ `initdb` si jamais lancé) → DB `kidzpos`, `kidzpos_central`, `kidzpos_store_s1`
- `caddy` (+ `setcap cap_net_bind_service` → bind port 443 sans sudo)
- `nodejs` + `npm`
- `curl` + `jq` + `rsync`

Mots de passe Postgres par défaut : user `kidzpos` / pass `kidzpos` (LAN privé,
surchargeable via `DB_USER=` / `DB_PASSWORD=`).

## Build une fois (avec internet)

```bash
npm ci
(cd backend && mvn -DskipTests package)
```

## Lancement

### HTTP local (plus simple, idéal pour dev/test)

```bash
./ops/launch/launch-both.sh        # central :3000 + store :4001
./ops/launch/stop.sh
```

### HTTPS local via Caddy (Secure cookies + même origine)

```bash
./ops/launch/launch-both-tls.sh    # central :443 + store :4443
```

### Accès depuis d'autres machines du LAN

```bash
./ops/launch/build-lan.sh both             # rebuild dist-* avec l'IP LAN
./ops/launch/launch-both.sh                # ou launch-both-tls.sh
# → afficher l'URL LAN à donner aux autres machines
```

## Spécificités Arch à connaître

- **PostgreSQL** : sous Arch, le service s'appelle `postgresql.service` et le
  user système est `postgres`. La data dir par défaut est `/var/lib/postgres/data`.
- **Caddy** : paquet officiel à jour (`pacman -Si caddy` → ≥ 2.7). Pas besoin
  du dépôt cloudsmith comme sur Debian/Ubuntu.
- **Setcap** : Arch n'inclut PAS `cap_net_bind_service` par défaut sur le
  binaire Caddy → install.sh le pose pour permettre le bind :443 sans sudo.
- **Firewall** : Arch n'a pas de firewall activé par défaut. Si tu utilises
  `ufw` ou `firewalld`, autoriser les ports `3000/4001` (HTTP) ou `443/4443`
  (HTTPS) en zone LAN.
- **Java** : si plusieurs JDK installés, vérifier `archlinux-java status` pour
  que JDK 17 soit actif.

## Cohabitation avec d'autres services

Si tu utilises déjà Apache/Nginx sur :80/:443, lance Caddy sur des ports alternatifs :

```bash
HTTPS_PORT=8443 ./ops/launch/launch-central-tls.sh
HTTPS_PORT=4443 ./ops/launch/launch-store-tls.sh
```

## Troubleshooting Arch

| Symptôme | Solution |
|---|---|
| `psql: could not connect to server` | `sudo systemctl status postgresql` ; voir `/var/lib/postgres/data/log/` |
| `Permission denied` sur `/var/lib/postgres/data` | data dir possédée par user `postgres` → utiliser `sudo -u postgres` |
| `bind: permission denied` :443 | `sudo setcap cap_net_bind_service=+ep $(command -v caddy)` |
| JDK 11 détecté au lieu de 17 | `sudo archlinux-java set java-17-openjdk` |
| `pacman` lock file | `sudo rm /var/lib/pacman/db.lck` (uniquement si certain qu'aucun pacman ne tourne) |
