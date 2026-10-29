# Runbook — Auth refresh failures / token reuse

**Alertes associées** :
- `RefreshTokenReuse` (critical) — un cookie refresh déjà révoqué a été présenté.
- `HighRefreshFailureRate` (warning) — >50% des refreshs échouent sur 10 min.

**Sévérité** :
- `RefreshTokenReuse` : **P0** (suspicion de vol de cookie, attaque active).
- `HighRefreshFailureRate` : P1 (impact UX, sessions tombent).

---

## 1. Diagnostic en 3 commandes

```bash
# A. Combien de reuse détectés sur la dernière heure ?
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c \
  "SELECT user_id, COUNT(*), MAX(detected_at) FROM refresh_tokens
   WHERE reuse_detected_at > NOW() - INTERVAL '1 hour'
   GROUP BY user_id ORDER BY 2 DESC LIMIT 10;" 2>&1 || \
  echo "Note: si la colonne reuse_detected_at n'existe pas, utiliser les logs"

# B. Logs auth récents
grep -E "(refresh|cascadedRevoke|invalid refresh)" /chemin/.../logs/backend.log \
  | tail -50

# C. IPs sources des refresh failures
grep -E "POST /api/auth/refresh.*401" /chemin/.../logs/access.log \
  | awk '{print $2}' | sort | uniq -c | sort -rn | head -10
```

---

## 2. Cas A — `RefreshTokenReuse` détecté (P0 — VOL SUSPECTÉ)

### Sémantique
Un attaquant a présenté un refresh token déjà rotaté (donc révoqué) → 2 copies
du même cookie existent dans la nature. Le backend a **cascade-revoqué tous les
tokens de cet user** automatiquement (cf `RefreshTokenService.detectReuse`).

### Action immédiate
1. **Identifier l'utilisateur** via `userId` dans le log.
2. **Forcer le re-login** (déjà fait par cascade-revoke ; user voit "Session expirée").
3. **Reset password** côté admin :
   ```bash
   curl -X PUT -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"password":"<nouveau>"}' \
     "$BACKEND_URL/api/users/$USER_ID/password"
   ```
4. **Auditer les IPs** d'où sont venus le refresh légitime ET le reuse — si
   différentes, confirme le vol (souvent XSS ou cookie volé en HTTP).
5. **Communiquer** à l'utilisateur que sa session a été compromise.

### Investigation longue
- Vérifier que le frontend est sur HTTPS en prod (`cookie-secure: true`).
- Auditer les XSS récents (logs CSP violation report — si Report-To configuré).
- Considérer rotation `JWT_SECRET` (invalide TOUS les access tokens immédiatement).

---

## 3. Cas B — `HighRefreshFailureRate` (P1)

### Causes possibles

#### B.1 Frontend bugué / boucle
- Symptôme : même IP, hundreds de POST /api/auth/refresh par minute.
- Action : identifier la version frontend déployée, vérifier le single-flight
  `refreshInFlight` dans `apiClient.ts`. Si rollback nécessaire :
  ```bash
  # Rétablir la version précédente
  ln -sfn dist-prev/ dist
  ./start-server.sh restart
  ```

#### B.2 JWT_SECRET tourné côté backend (sans redéploiement frontend)
- Symptôme : 100% des refresh échouent depuis un timestamp précis.
- Action : si rotation involontaire (restart sans `JWT_SECRET` fixé) →
  forcer la même valeur que précédemment, sinon attendre que tous les users
  se reloguent (impact 1h max).
  ```bash
  # Vérifier le secret courant
  systemctl show kidzpos -p Environment | grep JWT
  ```

#### B.3 Cookie not sent (CORS / SameSite cassé)
- Symptôme : `last_error` = "no refresh cookie".
- Causes : déploiement HTTPS récent sans `cookie-secure: true`, ou changement
  d'origin sans MAJ `cookie-same-site`.
- Action :
  ```bash
  # Vérifier les headers Set-Cookie envoyés par le backend
  curl -i -X POST -d '{"email":"<test>","password":"<test>"}' \
    -H "Content-Type: application/json" \
    "$BACKEND_URL/api/auth/login" | grep -i set-cookie
  ```
  Le cookie doit avoir `HttpOnly; Secure; SameSite=Lax` (LAN) ou `None; Secure`
  (cross-origin HTTPS).

#### B.4 Brute force ciblé
- Symptôme : N IPs distinctes, peu de hits chacune, échecs distribués.
- Action : le `RefreshRateLimitFilter` devrait bloquer mais cf "in-memory mono-instance".
  Si confirmé multi-IP : bloquer via reverse-proxy (fail2ban, Caddy block).

---

## 4. Recovery generic

```bash
# 1. Nettoyer les refresh tokens orphelins (legacy, expirés)
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c \
  "DELETE FROM refresh_tokens WHERE expires_at < NOW() - INTERVAL '7 days';"

# 2. Si l'incident persiste : restart backend (réinitialise le cache JwtAuthFilter)
./start-server.sh restart

# 3. Surveiller la métrique
watch -n 10 'curl -s localhost:8080/actuator/prometheus | grep -E "refresh_(success|failure)"'
```

---

## 5. Communication

- `RefreshTokenReuse` : prévenir l'utilisateur impacté + manager (security incident).
- `HighRefreshFailureRate` : si > 5 min, prévenir les opérateurs que des
  déconnexions peuvent survenir, leur demander de se relog si besoin.

---

## 6. Post-mortem (obligatoire pour P0 reuse)

Documenter dans `ops/runbooks/post-mortems/YYYYMMDD-auth-incident.md` :
- Type (reuse / bulk failure)
- IPs sources, user(s) impacté(s)
- Mécanisme suspecté de fuite (XSS, MITM, cookie en clair, etc.)
- Mitigations : rotation JWT_SECRET ? Force HTTPS ? CSP plus stricte ?
