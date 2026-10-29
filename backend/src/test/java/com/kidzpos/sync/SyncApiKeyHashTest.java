package com.kidzpos.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sanity tests sur le hash SHA-256 utilisé par {@link SyncApiKeyService}.
 *
 * Pas de test sur le service complet (nécessite DB) ; on couvre uniquement
 * l'invariant cryptographique (déterminisme, hex, longueur, sensibilité au
 * moindre bit). Le filter + endpoint sont couverts par les tests d'intégration
 * (TestContainers).
 */
class SyncApiKeyHashTest {

    @Test
    void hashIsDeterministic() {
        String a = SyncApiKeyService.sha256Hex("hello");
        String b = SyncApiKeyService.sha256Hex("hello");
        assertEquals(a, b);
    }

    @Test
    void hashIsHexAnd64Chars() {
        String h = SyncApiKeyService.sha256Hex("any-input");
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]{64}"));
    }

    @Test
    void differentInputsProduceDifferentHashes() {
        String a = SyncApiKeyService.sha256Hex("key-abc");
        String b = SyncApiKeyService.sha256Hex("key-abd");
        assertNotEquals(a, b);
    }

    @Test
    void emptyAndNonEmptyDiffer() {
        String empty = SyncApiKeyService.sha256Hex("");
        String x = SyncApiKeyService.sha256Hex("x");
        assertNotEquals(empty, x);
    }

    @Test
    void knownVector() {
        // Vecteur de test public : SHA-256("abc") =
        //   ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                SyncApiKeyService.sha256Hex("abc"));
    }
}
