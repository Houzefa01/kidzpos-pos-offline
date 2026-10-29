package com.kidzpos.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kidzpos.IntegrationTestBase;
import com.kidzpos.domain.Role;
import com.kidzpos.domain.User;
import com.kidzpos.repo.RefreshTokenRepository;
import com.kidzpos.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flow auth complet : login → refresh → rotation → reuse-detection → logout.
 *
 * Pour cumuler la validation d'attributs cookie httpOnly/path/maxAge, on lit
 * le header Set-Cookie brut (MockMvc.cookie() ne couvre pas tout).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class AuthRefreshFlowTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired RefreshTokenRepository refreshes;
    @Autowired ObjectMapper json;

    @BeforeEach
    void seedUser() {
        refreshes.deleteAll();
        users.findByEmailIgnoreCase("p2@test").ifPresent(users::delete);
        users.save(User.builder()
                .id("u-p2").name("P2").email("p2@test")
                .passwordHash(encoder.encode("p2-secret"))
                .role(Role.ADMIN).active(true).build());
    }

    @Test
    void loginSetsHttpOnlyRefreshCookieAndReturnsAccessToken() throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "p2@test", "password", "p2-secret"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.id").value("u-p2"))
                .andReturn();

        String setCookie = res.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).startsWith("kidzpos_rt=");
        assertThat(setCookie).containsIgnoringCase("HttpOnly");
        assertThat(setCookie).containsIgnoringCase("Path=/api/auth");
        assertThat(setCookie).containsIgnoringCase("SameSite=Lax");
        // En profil test cookie-secure=false par défaut → pas de Secure attendu

        // Token persisté en base
        assertThat(refreshes.findAll()).hasSize(1);
        assertThat(refreshes.findAll().get(0).getRevokedAt()).isNull();
    }

    @Test
    void refreshRotatesTokenAndRevokesPrevious() throws Exception {
        String firstCookie = loginAndExtractCookieValue();
        long beforeRefresh = refreshes.count();

        MvcResult res = mvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("kidzpos_rt", firstCookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        String newSetCookie = res.getResponse().getHeader("Set-Cookie");
        String secondCookie = extractCookieValue(newSetCookie);
        assertThat(secondCookie).isNotEqualTo(firstCookie);

        // 2 tokens en base : ancien revoked, nouveau actif
        assertThat(refreshes.count()).isEqualTo(beforeRefresh + 1);
        var tokens = refreshes.findAll();
        long active = tokens.stream().filter(t -> t.getRevokedAt() == null).count();
        long revoked = tokens.stream().filter(t -> t.getRevokedAt() != null).count();
        assertThat(active).isEqualTo(1);
        assertThat(revoked).isEqualTo(1);
        // L'ancien pointe vers le nouveau via replaced_by
        tokens.stream().filter(t -> t.getRevokedAt() != null).forEach(t ->
                assertThat(t.getReplacedBy()).isNotNull()
        );
    }

    @Test
    void reusingARevokedRefreshTokenInvalidatesAllSessionsForUser() throws Exception {
        String firstCookie = loginAndExtractCookieValue();

        // Première rotation OK
        mvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("kidzpos_rt", firstCookie)))
                .andExpect(status().isOk());

        // Crée un second login parallèle (second device) → second refresh actif
        String parallelCookie = loginAndExtractCookieValue();
        assertThat(refreshes.findAll().stream().filter(t -> t.getRevokedAt() == null).count())
                .isEqualTo(2);  // rotated-new + parallel-new

        // ATTAQUE : on tente de réutiliser firstCookie (déjà révoqué)
        mvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("kidzpos_rt", firstCookie)))
                .andExpect(status().isUnauthorized());

        // Conséquence : TOUS les tokens de cet user sont révoqués (cascade)
        long stillActive = refreshes.findAll().stream()
                .filter(t -> "u-p2".equals(t.getUserId()))
                .filter(t -> t.getRevokedAt() == null)
                .count();
        assertThat(stillActive).isEqualTo(0);

        // Le parallèle ne peut plus refresh non plus
        mvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("kidzpos_rt", parallelCookie)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshAndClearsCookie() throws Exception {
        String cleartextCookie = loginAndExtractCookieValue();
        assertThat(refreshes.count()).isEqualTo(1);

        MvcResult res = mvc.perform(post("/api/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie("kidzpos_rt", cleartextCookie)))
                .andExpect(status().isNoContent())
                .andReturn();

        String clear = res.getResponse().getHeader("Set-Cookie");
        assertThat(clear).isNotNull();
        assertThat(clear).containsIgnoringCase("Max-Age=0");

        // Token marqué revoked
        var t = refreshes.findAll().get(0);
        assertThat(t.getRevokedAt()).isNotNull();

        // Re-refresh impossible
        mvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("kidzpos_rt", cleartextCookie)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWithoutCookieReturns401() throws Exception {
        mvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String loginAndExtractCookieValue() throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "p2@test", "password", "p2-secret"))))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = res.getResponse().getHeader("Set-Cookie");
        return extractCookieValue(setCookie);
    }

    private static final Pattern COOKIE_VALUE = Pattern.compile("kidzpos_rt=([^;]+)");

    private static String extractCookieValue(String setCookieHeader) {
        if (setCookieHeader == null) return null;
        Matcher m = COOKIE_VALUE.matcher(setCookieHeader);
        return m.find() ? m.group(1) : null;
    }
}
