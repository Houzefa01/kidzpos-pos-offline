package com.kidzpos.sync;

import com.kidzpos.repo.OperationLogRepository;
import com.kidzpos.repo.SyncInboxRepository;
import com.kidzpos.sync.SyncApiKeyFilter.SyncPrincipal;
import com.kidzpos.sync.SyncDtos.PullResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit-2026-05 — Vérifie le guard cross-store sur {@link SyncController#pull}.
 *
 * <p>Avant ce patch, un store authentifié par clé per-store V21 pouvait
 * récupérer les events de TOUS les magasins en omettant simplement
 * {@code ?storeId=}. C'est une fuite cross-store théorique mais réelle :
 * une clé volée donnait accès à l'historique global du central.
 *
 * <p>Le nouveau contrat :
 * <ul>
 *   <li>Mode per-store + pas de storeId → 400 (paramètre requis).</li>
 *   <li>Mode per-store + storeId ≠ celui de la clé → 403 strict.</li>
 *   <li>Mode per-store + storeId match → OK (comportement nominal).</li>
 *   <li>Mode ADMIN-JWT / legacy : storeId reste optionnel (rétrocompat).</li>
 * </ul>
 */
class SyncCrossStorePullGuardTest {

    private static SyncController newController(OperationLogRepository opLog) {
        return new SyncController("central", "test-node", opLog, mock(SyncInboxRepository.class));
    }

    @Test
    void perStoreAuth_withoutStoreIdParam_returns400() {
        OperationLogRepository opLog = mock(OperationLogRepository.class);
        SyncController c = newController(opLog);
        SyncPrincipal principal = new SyncPrincipal("s1", /* perStore */ true);

        ResponseEntity<PullResponse> res = c.pull(null, null, 100, principal);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(opLog, never()).findAll(any(PageRequest.class));
    }

    @Test
    void perStoreAuth_withBlankStoreIdParam_returns400() {
        SyncController c = newController(mock(OperationLogRepository.class));
        SyncPrincipal principal = new SyncPrincipal("s1", /* perStore */ true);

        ResponseEntity<PullResponse> res = c.pull(null, "  ", 100, principal);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void perStoreAuth_withForeignStoreId_returns403() {
        OperationLogRepository opLog = mock(OperationLogRepository.class);
        SyncController c = newController(opLog);
        SyncPrincipal principal = new SyncPrincipal("s1", /* perStore */ true);

        ResponseEntity<PullResponse> res = c.pull(null, "s2", 100, principal);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(opLog, never()).findByStoreIdOrderByCreatedAtAsc(any(), any());
    }

    @Test
    void perStoreAuth_withOwnStoreId_returnsOk() {
        OperationLogRepository opLog = mock(OperationLogRepository.class);
        when(opLog.findByStoreIdOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());
        SyncController c = newController(opLog);
        SyncPrincipal principal = new SyncPrincipal("s1", /* perStore */ true);

        ResponseEntity<PullResponse> res = c.pull(null, "s1", 100, principal);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().operations()).isEmpty();
    }

    @Test
    void legacyAuth_withoutStoreId_stillWorks_forBackwardCompat() {
        OperationLogRepository opLog = mock(OperationLogRepository.class);
        when(opLog.findAll(any(PageRequest.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());
        SyncController c = newController(opLog);
        SyncPrincipal principal = new SyncPrincipal(null, /* perStore */ false);

        ResponseEntity<PullResponse> res = c.pull(null, null, 100, principal);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void adminJwtAuth_withoutStoreId_stillWorks() {
        // ADMIN via JWT → principal n'est PAS un SyncPrincipal (c'est l'AuthPrincipal JWT).
        // On simule avec un String quelconque pour exercer le code-path "non-perStore".
        OperationLogRepository opLog = mock(OperationLogRepository.class);
        when(opLog.findAll(any(PageRequest.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());
        SyncController c = newController(opLog);

        ResponseEntity<PullResponse> res = c.pull(null, null, 100, "some-jwt-principal");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
