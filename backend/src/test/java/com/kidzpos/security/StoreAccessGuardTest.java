package com.kidzpos.security;

import com.kidzpos.security.JwtAuthFilter.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1.1 — Vérifie le contrat de la guard cross-store.
 *
 * Test unitaire pur (pas de Spring, pas de Docker) — exécutable sur n'importe
 * quel poste, complète les tests d'intégration TestContainers qui exigent Docker.
 */
class StoreAccessGuardTest {

    private static AuthPrincipal employee(String storeId) {
        return new AuthPrincipal("u-emp", "Sarah", "sarah@kidzpos.com", "EMPLOYEE", storeId);
    }

    private static AuthPrincipal admin() {
        return new AuthPrincipal("u-adm", "Admin", "admin@kidzpos.com", "ADMIN", null);
    }

    @Test
    void adminCanActOnAnyStore() {
        assertThat(StoreAccessGuard.canActOn(admin(), "s1")).isTrue();
        assertThat(StoreAccessGuard.canActOn(admin(), "s2")).isTrue();
        assertThat(StoreAccessGuard.canActOn(admin(), null)).isTrue();
        assertThat(StoreAccessGuard.denyIfCrossStore(admin(), "s2")).isNull();
    }

    @Test
    void employeeCanActOnTheirOwnStore() {
        assertThat(StoreAccessGuard.canActOn(employee("s1"), "s1")).isTrue();
        assertThat(StoreAccessGuard.denyIfCrossStore(employee("s1"), "s1")).isNull();
    }

    @Test
    void employeeCannotActOnAnotherStore() {
        ResponseEntity<?> deny = StoreAccessGuard.denyIfCrossStore(employee("s1"), "s2");
        assertThat(deny).isNotNull();
        assertThat(deny.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(deny.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) deny.getBody();
        assertThat(body).containsKey("error");
        // Message neutre — ne révèle pas le storeId du caller pour éviter l'énumération
        assertThat(body.get("error")).doesNotContain("s1").doesNotContain("s2");
    }

    @Test
    void employeeWithoutStoreIsRejectedEverywhere() {
        // Un EMPLOYEE sans storeId (cas légacy / mal configuré) ne doit avoir accès à rien
        assertThat(StoreAccessGuard.canActOn(employee(null), "s1")).isFalse();
        assertThat(StoreAccessGuard.denyIfCrossStore(employee(null), "s1")).isNotNull();
    }

    @Test
    void nullPrincipalIsRejected() {
        assertThat(StoreAccessGuard.canActOn(null, "s1")).isFalse();
        assertThat(StoreAccessGuard.denyIfCrossStore(null, "s1")).isNotNull();
    }

    @Test
    void nullTargetStoreIsRejectedForEmployee() {
        // Une requête sans storeId ne doit pas accidentellement passer pour un EMPLOYEE
        assertThat(StoreAccessGuard.canActOn(employee("s1"), null)).isFalse();
    }
}
