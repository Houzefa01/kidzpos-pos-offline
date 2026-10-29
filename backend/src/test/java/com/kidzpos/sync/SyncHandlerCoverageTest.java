package com.kidzpos.sync;

import com.kidzpos.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou producteur/consommateur de la sync.
 *
 * <p>Chaque type d'événement émis par un controller via {@code OperationLogService.record(type, …)}
 * DOIT avoir un {@link InboxHandler} correspondant, sinon l'événement traverse le push/pull mais
 * n'est jamais matérialisé sur les autres nœuds (bug silencieux : la donnée "disparaît" en sync).
 *
 * <p>Ce test fige la liste exhaustive des types émis (le contrat). Si quelqu'un ajoute un
 * {@code opLog.record("nouveau.type", …)} sans handler, ce test échoue → on ne peut pas merger
 * une régression de sync. Inversement, retirer un handler encore émis casse aussi le test.
 *
 * <p>Historique : ce garde-fou a été ajouté après que l'audit ait livré des
 * {@code product.*} et {@code user.*} émis SANS handler (product) ou sans handler du tout
 * (user) — bugs invisibles aux tests unitaires d'alors.
 */
@TestPropertySource(properties = "kidzpos.sync.inbox.enabled=true")
class SyncHandlerCoverageTest extends IntegrationTestBase {

    /**
     * Contrat : tous les types émis via opLog.record() dans les controllers.
     * À garder synchronisé avec les appels {@code opLog.record(...)} (grep le confirme).
     */
    private static final Set<String> EMITTED_TYPES = Set.of(
            "sale.checkout", "sale.refund",
            "product.created", "product.updated", "product.deleted",
            "customer.created", "customer.updated", "customer.deleted",
            "user.created", "user.updated", "user.deleted",
            "stock.adjust", "stock.transfer"
    );

    @Autowired
    SyncInboxProcessor processor;

    @Test
    void everyEmittedEventTypeHasAnInboxHandler() {
        Set<String> handled = processor.whitelist();
        assertThat(handled)
                .as("Tout type émis par un controller doit avoir un InboxHandler "
                        + "(sinon l'event traverse la sync mais n'est jamais appliqué)")
                .containsAll(EMITTED_TYPES);
    }
}
