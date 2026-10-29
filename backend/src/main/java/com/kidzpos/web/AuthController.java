package com.kidzpos.web;

import com.kidzpos.domain.User;
import com.kidzpos.dto.Dtos.*;
import com.kidzpos.repo.UserRepository;
import com.kidzpos.security.JwtService;
import com.kidzpos.security.RefreshCookie;
import com.kidzpos.security.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refreshSvc;
    private final RefreshCookie cookie;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwt,
                          RefreshTokenService refreshSvc, RefreshCookie cookie) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshSvc = refreshSvc;
        this.cookie = cookie;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginReq req,
                                   HttpServletRequest httpReq,
                                   HttpServletResponse httpRes) {
        var user = users.findByEmailIgnoreCase(req.email().trim()).orElse(null);
        if (user == null || !user.isActive() || !encoder.matches(req.password(), user.getPasswordHash())) {
            return ResponseEntity.status(401).body(Map.of("error", "Identifiants invalides"));
        }
        // P2 : access token court (15 min par défaut). Le refresh token (30j) est posé
        // en cookie httpOnly — jamais retourné dans le body, jamais accessible au JS.
        String accessToken = jwt.generate(user.getId(), user.getEmail(), user.getRole().name(), user.getStoreId());
        var refresh = refreshSvc.issue(user.getId(), httpReq);
        cookie.set(httpRes, refresh.cleartext());
        return ResponseEntity.ok(new LoginRes(accessToken, toRes(user)));
    }

    /**
     * Rotation refresh. Lit le cookie httpOnly, valide, émet un nouvel access token
     * + nouveau refresh (l'ancien est révoqué). Si le cookie est manquant, expiré,
     * inconnu, ou déjà-révoqué (signe de vol) → 401 + reuse-detection en cascade.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest httpReq, HttpServletResponse httpRes) {
        String oldCleartext = cookie.read(httpReq);
        var rotated = refreshSvc.rotate(oldCleartext, httpReq);
        if (rotated.isEmpty()) {
            // Clear cookie + 401 — l'opérateur doit se ré-authentifier.
            cookie.clear(httpRes);
            return ResponseEntity.status(401).body(Map.of("error", "Refresh invalide"));
        }
        // Récupérer le user (le service ne le renvoie pas pour rester minimal)
        String userId = refreshSvc.lookupActiveUserId(rotated.get().cleartext()).orElse(null);
        var user = userId != null ? users.findById(userId).orElse(null) : null;
        if (user == null || !user.isActive()) {
            cookie.clear(httpRes);
            return ResponseEntity.status(401).body(Map.of("error", "Compte inactif"));
        }
        cookie.set(httpRes, rotated.get().cleartext());
        String accessToken = jwt.generate(user.getId(), user.getEmail(), user.getRole().name(), user.getStoreId());
        return ResponseEntity.ok(new RefreshRes(accessToken));
    }

    /**
     * Logout : révoque le refresh côté serveur ET clear le cookie côté client.
     * Idempotent (no-op si cookie absent ou déjà révoqué).
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest httpReq, HttpServletResponse httpRes) {
        String cleartext = cookie.read(httpReq);
        refreshSvc.revoke(cleartext);
        cookie.clear(httpRes);
        return ResponseEntity.noContent().build();
    }

    static UserRes toRes(User u) {
        return new UserRes(u.getId(), u.getName(), u.getEmail(), u.getRole().name(), u.getStoreId(), u.isActive());
    }
}
