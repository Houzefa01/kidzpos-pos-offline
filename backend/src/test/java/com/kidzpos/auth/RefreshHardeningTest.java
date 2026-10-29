package com.kidzpos.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kidzpos.IntegrationTestBase;
import com.kidzpos.domain.RefreshToken;
import com.kidzpos.domain.Role;
import com.kidzpos.domain.User;
import com.kidzpos.repo.RefreshTokenRepository;
import com.kidzpos.repo.UserRepository;
import com.kidzpos.security.RefreshTokenService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2.2 — Hardening :
 *   - cleanup expirés (méthode + idempotence)
 *   - rate limit /api/auth/refresh → 11e tentative 401 → 429
 *   - counters Micrometer success/failure incrémentés correctement
 *
 * @DirtiesContext pour reset le filtre rate-limit entre tests
 *   (in-memory state, partagé entre méthodes sinon).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class RefreshHardeningTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired RefreshTokenRepository refreshes;
    @Autowired RefreshTokenService service;
    @Autowired MeterRegistry registry;
    @Autowired ObjectMapper json;

    @BeforeEach
    void seed() {
        refreshes.deleteAll();
        users.findByEmailIgnoreCase("hardening@test").ifPresent(users::delete);
        users.save(User.builder()
                .id("u-harden").name("H").email("hardening@test")
                .passwordHash(encoder.encode("pw"))
                .role(Role.ADMIN).active(true).build());
    }

    @Test
    void cleanupExpiredDeletesTokensOlderThan24h() {
        Instant now = Instant.now();
        // Token expiré il y a 48h → doit être supprimé
        refreshes.save(RefreshToken.builder()
                .id("rt-old").tokenHash("hash-old").userId("u-harden")
                .issuedAt(now.minusSeconds(86400 * 60))
                .expiresAt(now.minusSeconds(86400 * 2))
                .build());
        // Token expiré il y a 5h → grâce 24h, doit RESTER
        refreshes.save(RefreshToken.builder()
                .id("rt-grace").tokenHash("hash-grace").userId("u-harden")
                .issuedAt(now.minusSeconds(86400))
                .expiresAt(now.minusSeconds(3600 * 5))
                .build());
        // Token actif (expires_at futur) → doit RESTER
        refreshes.save(RefreshToken.builder()
                .id("rt-active").tokenHash("hash-active").userId("u-harden")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(86400))
                .build());

        int deleted = service.cleanupExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(refreshes.findById("rt-old")).isEmpty();
        assertThat(refreshes.findById("rt-grace")).isPresent();
        assertThat(refreshes.findById("rt-active")).isPresent();
    }

    @Test
    void cleanupIdempotentWhenNothingToDelete() {
        Instant now = Instant.now();
        refreshes.save(RefreshToken.builder()
                .id("rt-fresh").tokenHash("hash-fresh").userId("u-harden")
                .issuedAt(now).expiresAt(now.plusSeconds(86400)).build());

        assertThat(service.cleanupExpired()).isEqualTo(0);
        assertThat(service.cleanupExpired()).isEqualTo(0);
        assertThat(refreshes.count()).isEqualTo(1);
    }

    @Test
    void rateLimit10FailuresThenReturns429() throws Exception {
        // 10 tentatives avec un cookie inconnu → 10 × 401
        String randomCookie = "unknown-" + UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/auth/refresh")
                            .cookie(new jakarta.servlet.http.Cookie("kidzpos_rt", randomCookie)))
                    .andExpect(status().isUnauthorized());
        }
        // 11e tentative → 429 + Retry-After
        MvcResult res = mvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("kidzpos_rt", randomCookie)))
                .andExpect(status().is(429))
                .andReturn();
        assertThat(res.getResponse().getHeader("Retry-After")).isNotNull();
    }

    @Test
    void countersIncrementOnSuccessAndFailure() throws Exception {
        double successBefore = counter("kidzpos.auth.refresh_success");
        double failureBefore = counter("kidzpos.auth.refresh_failure");

        // 1 échec (cookie inconnu)
        mvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("kidzpos_rt", "inconnu")))
                .andExpect(status().isUnauthorized());

        // 1 succès : login puis refresh
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "hardening@test", "password", "pw"))))
                .andExpect(status().isOk()).andReturn();
        String cookie = extractCookie(login.getResponse().getHeader("Set-Cookie"));
        mvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("kidzpos_rt", cookie)))
                .andExpect(status().isOk());

        assertThat(counter("kidzpos.auth.refresh_success")).isGreaterThanOrEqualTo(successBefore + 1);
        assertThat(counter("kidzpos.auth.refresh_failure")).isGreaterThanOrEqualTo(failureBefore + 1);
    }

    private double counter(String name) {
        Counter c = registry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    private static String extractCookie(String setCookieHeader) {
        if (setCookieHeader == null) return null;
        var m = java.util.regex.Pattern.compile("kidzpos_rt=([^;]+)").matcher(setCookieHeader);
        return m.find() ? m.group(1) : null;
    }
}
