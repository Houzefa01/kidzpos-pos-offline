# ops/launch/windows — Windows 10/11

Équivalent PowerShell des scripts bash `ops/launch/`. Permet de lancer le
central et le store de KidzPOS sur Windows sans Bash ni WSL.

| Fichier | Rôle |
|---|---|
| `install.ps1` | Installe via winget : OpenJDK 17, Node.js LTS, Maven, Caddy, PostgreSQL ; crée DB + user |
| `common.ps1` | Helpers partagés (Get-LanIp, Test-PortBusy, Wait-Port, Start-Backend, Start-Frontend, Start-Caddy) |
| `launch-central.ps1` | Démarre le central (HTTP par défaut, `-Tls` pour HTTPS Caddy) |
| `launch-store.ps1` | Démarre le store s1 |
| `launch-both.ps1` | Démarre central puis store |
| `stop.ps1` | Arrête tout (backends + frontends + Caddy) |
| `build-lan.ps1` | (Re)build les frontends câblés sur l'IP LAN |
| `start-*.bat` | Wrappers double-clic (lancent un PowerShell sur le `.ps1`) |
| `stop.bat` | Double-clic pour arrêter |

## Installation (une fois, avec internet)

Ouvre **PowerShell en administrateur** et exécute :

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned -Force
cd C:\chemin\vers\kidzpos
.\ops\launch\windows\install.ps1
```

Cela installe via **winget** :
- OpenJDK 17 (Microsoft Build)
- Node.js LTS
- Apache Maven
- Caddy
- PostgreSQL 16 (installateur interactif → choisir un mot de passe pour `postgres`)

Puis crée les bases `kidzpos`, `kidzpos_central`, `kidzpos_store_s1` et le
rôle `kidzpos` (mot de passe `kidzpos`).

Enfin : `npm ci` + `mvn -DskipTests package` pour préparer les artefacts.

> Si winget n'est pas dispo (Windows trop ancien) : installer **App Installer**
> depuis le Microsoft Store. Sinon installer manuellement chaque outil depuis
> son site officiel et passer `-SkipPostgres -SkipBuild`.

## Lancement

### HTTP local (mode dev, le plus simple)

```powershell
.\ops\launch\windows\launch-both.ps1
# → Central UI : http://localhost:3000
# → Store   UI : http://localhost:4001
# admin@kidzpos.com / admin123
```

Ou **double-clic** sur `start-both.bat`.

### HTTPS local via Caddy

```powershell
.\ops\launch\windows\launch-both.ps1 -Tls
# → Central : https://localhost (port 443)
# → Store   : https://localhost:4443
```

Ou **double-clic** sur `start-both-tls.bat`. Premier lancement : Caddy
installe sa CA locale → UAC (Contrôle de compte d'utilisateur) demande
l'autorisation. Accepter.

> Port 443 nécessite l'élévation de privilèges. Soit lance PowerShell en
> admin, soit utilise `-HttpsPort 8443` pour rester en utilisateur normal.

### Lancer un seul nœud

```powershell
.\ops\launch\windows\launch-central.ps1
.\ops\launch\windows\launch-store.ps1
```

### Accès depuis d'autres machines du LAN (multi-machine)

Par défaut, le frontend est buildé sur `localhost` → seul l'ordinateur local
peut s'y connecter. Pour autoriser d'autres machines :

```powershell
.\ops\launch\windows\build-lan.ps1                # rebuild dist-* avec l'IP LAN
.\ops\launch\windows\launch-both.ps1              # ou avec -Tls
# Depuis les autres machines, ouvrir :
#   http://<IP-serveur>:3000   (central)
#   http://<IP-serveur>:4001   (store)
```

Le pare-feu Windows demandera une autorisation au premier lancement —
choisir « Réseaux privés ».

## Arrêt

```powershell
.\ops\launch\windows\stop.ps1
```

Ou double-clic sur `stop.bat`.

## Surcharge par variables d'environnement

Les scripts honorent les variables (à poser AVANT de lancer) :

| Variable | Effet |
|---|---|
| `$env:SERVER_PORT` | Port backend (8080 central / 8081 store) |
| `$env:DB_PASSWORD` | Mot de passe DB Postgres (défaut `kidzpos`) |
| `$env:JWT_SECRET` | Secret JWT (généré à chaque restart sinon — invalide les tokens) |
| `$env:KIDZPOS_SYNC_CENTRAL_URL` | URL du central pour push/pull du store |
| `$env:KIDZPOS_SYNC_API_KEY` | Clé d'API de sync du store |
| `$env:KIDZPOS_ADMIN_PASSWORD` | Mot de passe initial de l'admin |
| `$env:LAN_IP` | Forcer l'IP LAN détectée |

Exemple — store qui pousse vers un central distant :

```powershell
$env:KIDZPOS_SYNC_CENTRAL_URL = "https://central.tondomaine.com"
$env:KIDZPOS_SYNC_API_KEY     = "kzp_2f4b7c9e8a1d…"
$env:KIDZPOS_NODE_STORE_ID    = "s1"
.\ops\launch\windows\launch-store.ps1
```

## Démarrage automatique au boot

Pour qu'un store redémarre seul après une coupure (cas usuel en magasin) :

### Option A — Tâche planifiée (recommandé)

```powershell
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument '-WindowStyle Hidden -ExecutionPolicy Bypass -File C:\kidzpos\ops\launch\windows\launch-both.ps1'
$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -RunLevel Highest
Register-ScheduledTask -TaskName 'KidzPOS' -Action $action -Trigger $trigger -Principal $principal
```

### Option B — Raccourci dans Démarrage

Mettre `start-both.bat` dans :
```
%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\
```

## Logs

Les logs sont dans le dossier `logs\` à la racine du projet :

```powershell
Get-Content logs\central.log -Wait -Tail 50
Get-Content logs\store-s1.log -Wait -Tail 50
```

## Pare-feu Windows

Si une autre machine du LAN ne peut pas atteindre les ports, autoriser
manuellement :

```powershell
# En administrateur
New-NetFirewallRule -DisplayName "KidzPOS Central" -Direction Inbound `
    -LocalPort 3000,8080 -Protocol TCP -Action Allow -Profile Private
New-NetFirewallRule -DisplayName "KidzPOS Store" -Direction Inbound `
    -LocalPort 4001,8081 -Protocol TCP -Action Allow -Profile Private
# HTTPS Caddy
New-NetFirewallRule -DisplayName "KidzPOS HTTPS" -Direction Inbound `
    -LocalPort 443,4443 -Protocol TCP -Action Allow -Profile Private
```

## Troubleshooting

| Symptôme | Cause | Solution |
|---|---|---|
| `winget : commande introuvable` | Windows trop ancien | Installer « App Installer » depuis Microsoft Store |
| `Stratégie d'exécution restreinte` PowerShell | ExecutionPolicy = Restricted | `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` |
| `port 8080 déjà occupé` | Autre service ou KidzPOS déjà lancé | `.\stop.ps1` puis vérifier `netstat -ano \| findstr :8080` |
| Login échoue avec HTTPS | Cookie `Secure=true` rejeté en HTTP | Lancer sans `-Tls` OU accéder via `https://` uniquement |
| `bind: permission denied` :443 | Port privilégié | Lancer PowerShell en admin OU `-HttpsPort 8443` |
| Pare-feu refuse les connexions LAN | Profil « Public » sur le réseau | Settings → Réseau → définir le réseau en « Privé » |
| Caddy : popup CA install | Premier lancement TLS | Cliquer « Oui » pour installer la CA locale Caddy |
| `Java introuvable` après install | PATH non rafraîchi | Fermer PowerShell, en rouvrir un nouveau |
| Postgres : `password authentication failed` | Mauvais mdp dans `$env:DB_PASSWORD` | Vérifier le mdp choisi à l'install, ou réinitialiser via pgAdmin |

## Différences avec les scripts Linux

| Aspect | Linux (bash) | Windows (PowerShell) |
|---|---|---|
| Détection IP LAN | `ip -4 -o addr show` | `Get-NetIPAddress` |
| Test port libre | `/dev/tcp/127.0.0.1/$port` | `Net.Sockets.TcpClient` |
| Background process | `&` + `$!` | `Start-Process -PassThru` |
| Setcap port 443 | `setcap cap_net_bind_service` | UAC élévation requise |
| Variables d'env | `export VAR=…` | `$env:VAR = …` |
| Serveur statique | même `serve-static.mjs` (Node) | même `serve-static.mjs` (Node) |
| Caddy data dir | `~/.local/share/caddy` | `%LOCALAPPDATA%\Caddy` |
