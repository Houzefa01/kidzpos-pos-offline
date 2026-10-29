package com.kidzpos.security;

import com.kidzpos.domain.RefreshToken;
import com.kidzpos.observability.BusinessMetrics;
import com.kidzpos.repo.RefreshTokenRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Cycle de vie des refresh tokens :
 *  - issue(userId, …)  → émet un nouveau token cleartext (cookie) + persiste son hash
 *  - rotate(cleartext) → vérifie, révoque l'ancien, émet un nouveau (cascade revoke-all
 *                       si signe de réutilisation)
 *  - revoke(cleartext) → marque revokedAt (logout)
 *
 * Le cleartext n'est JAMAIS persisté. Seul le SHA-256 est en base.
 * Le cleartext est 32 octets de SecureRandom, encodé base64url (~43 chars sans padding).
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository repo;
    private final long ttlMs;
    private final SecureRandom random = new SecureRandom();
    private final Counter reuseDetected;
    private final Counter refreshSuccess;
    private final Counter refreshFailure;
    private final Counter cleanupDeleted;

    public RefreshTokenService(RefreshTokenRepository repo,
                               @Value("${kidzpos.auth.refresh-token-days:30}") long days,
                               MeterRegistry meterRegistry,
                               @SuppressWarnings("unused") BusinessMetrics _existingMetrics) {
        this.repo = repo;
        this.ttlMs = Duration.ofDays(days).toMillis();
        this.reuseDetected = Counter.builder("kidzpos.auth.refresh_reuse_detected")
                .description("Refresh tokens réutilisés après révocation (vol suspecté)")
                .register(meterRegistry);
        this.refreshSuccess = Counter.builder("kidzpos.auth.refresh_success")
                .description("Rotations refresh token réussies")
                .register(meterRegistry);
        this.refreshFailure = Counter.builder("kidzpos.auth.refresh_failure")
                .description("Tentatives de refresh rejetées (raison dans les logs)")
                .register(meterRegistry);
        this.cleanupDeleted = Counter.builder("kidzpos.auth.refresh_cleanup_deleted")
                .description("Refresh tokens expirés supprimés par le job de cleanup")
                .register(meterRegistry);
    }

    /**
     * Émet un nouveau refresh token. Retourne le CLEARTEXT (à placer dans le cookie) ;
     * seul le hash est persisté.
     */
    @Transactional
    public Issued issue(String userId, HttpServletRequest req) {
        String cleartext = generateCleartext();
        String hash = sha256(cleartext);
        Instant now = Instant.now();
        RefreshToken rt = RefreshToken.builder()
                .id(UUID.randomUUID().toString())
                .tokenHash(hash)
                .userId(userId)
                .issuedAt(now)
                .expiresAt(now.plusMillis(ttlMs))
                .userAgent(truncate(req.getHeader("User-Agent"), 255))
                .ip(req.getRemoteAddr())
                .build();
        repo.save(rt);
        log.info("Refresh token issued: tokenId={} userId={}", rt.getId(), userId);
        return new Issued(cleartext, rt.getExpiresAt());
    }

    /**
     * Rotation : un refresh OK révoque l'ancien et émet un nouveau.
     * Cas d'erreur :
     *  - token inconnu → empty
     *  - token expiré → empty
     *  - token déjà revoked_at → REUSE DETECTED : invalide tous les tokens de l'user et empty
     */
    @Transactional
    public Optional<Issued> rotate(String cleartext, HttpServletRequest req) {
        if (cleartext == null || cleartext.isBlank()) {
            refreshFailure.increment();
            log.info("Refresh failed: reason=no_cookie ip={}", req.getRemoteAddr());
            return Optional.empty();
        }
        String hash = sha256(cleartext);
        Optional<RefreshToken> opt = repo.findByTokenHash(hash);
        if (opt.isEmpty()) {
            refreshFailure.increment();
            log.info("Refresh failed: reason=unknown ip={}", req.getRemoteAddr());
            return Optional.empty();
        }
        RefreshToken rt = opt.get();
        Instant now = Instant.now();

        if (rt.getRevokedAt() != null) {
            // Réutilisation d'un token déjà révoqué → vol suspecté.
            // On invalide TOUS les tokens actifs de cet user (force re-login partout).
            refreshFailure.increment();
            reuseDetected.increment();
            int revokedNow = repo.revokeAllForUser(rt.getUserId(), now);
            log.warn("Refresh failed: reason=reuse_detected tokenId={} userId={} ip={} cascadedRevoke={}",
                    rt.getId(), rt.getUserId(), req.getRemoteAddr(), revokedNow);
            return Optional.empty();
        }

        if (rt.getExpiresAt().isBefore(now)) {
            refreshFailure.increment();
            log.info("Refresh failed: reason=expired tokenId={} userId={} ip={}",
                    rt.getId(), rt.getUserId(), req.getRemoteAddr());
            return Optional.empty();
        }

        // Émettre le nouveau, puis révoquer l'ancien en pointant replaced_by → audit chain.
        Issued issued = issue(rt.getUserId(), req);
        String newId = repo.findByTokenHash(sha256(issued.cleartext())).map(RefreshToken::getId).orElse(null);
        rt.setRevokedAt(now);
        rt.setReplacedBy(newId);
        repo.save(rt);
        refreshSuccess.increment();
        log.info("Refresh OK: rotated tokenId={} → newId={} userId={} ip={}",
                rt.getId(), newId, rt.getUserId(), req.getRemoteAddr());
        return Optional.of(issued);
    }

    /** Logout : révoque le refresh fourni dans le cookie. No-op si déjà révoqué/inconnu. */
    @Transactional
    public void revoke(String cleartext) {
        if (cleartext == null || cleartext.isBlank()) return;
        String hash = sha256(cleartext);
        repo.findByTokenHash(hash).ifPresent(rt -> {
            if (rt.getRevokedAt() == null) {
                rt.setRevokedAt(Instant.now());
                repo.save(rt);
                log.info("Refresh token revoked on logout: tokenId={} userId={}", rt.getId(), rt.getUserId());
            }
        });
    }

    /** Lookup user pour le rotate (retourne l'userId ssi token actif et non-expiré). */
    @Transactional(readOnly = true)
    public Optional<String> lookupActiveUserId(String cleartext) {
        if (cleartext == null || cleartext.isBlank()) return Optional.empty();
        return repo.findByTokenHash(sha256(cleartext))
                .filter(rt -> rt.getRevokedAt() == null)
                .filter(rt -> rt.getExpiresAt().isAfter(Instant.now()))
                .map(RefreshToken::getUserId);
    }

    /**
     * Cleanup quotidien des refresh tokens expirés.
     *
     * Cron 03:00 chaque jour ; supprime les tokens dont expires_at < (now - 24h)
     * — on garde 24h de grâce après expiration pour permettre une investigation
     * en cas d'incident (audit chain replaced_by). Retourne le count pour
     * faciliter les tests unitaires.
     *
     * Idempotent : si rien à supprimer, no-op silencieux (log debug).
     */
    @Scheduled(cron = "${kidzpos.auth.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public int cleanupExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        int deleted = repo.deleteExpiredBefore(cutoff);
        if (deleted > 0) {
            cleanupDeleted.increment(deleted);
            log.info("Refresh tokens cleanup: deleted {} expired entries (cutoff={})", deleted, cutoff);
        } else {
            log.debug("Refresh tokens cleanup: no expired entries to delete");
        }
        return deleted;
    }

    private String generateCleartext() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 absent — JVM incompatible", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public record Issued(String cleartext, Instant expiresAt) {}
}
