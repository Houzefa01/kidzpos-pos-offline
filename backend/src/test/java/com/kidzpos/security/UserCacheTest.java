package com.kidzpos.security;

import com.kidzpos.domain.Role;
import com.kidzpos.domain.User;
import com.kidzpos.repo.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests purs Mockito du cache user. TTL court (1 s pour le test), pas de
 * dépendance Spring ni Docker. Couvre :
 *   - cache hit (1 SELECT pour N lectures du même userId)
 *   - cache miss → delegate au repo
 *   - cache négatif (user inconnu) — pas de tempête de SELECT sur tokens orphelins
 *   - invalidation manuelle (utilisée par UserController après update/delete)
 *   - null userId → empty sans interaction repo
 */
class UserCacheTest {

    private UserRepository repo;
    private UserCache cache;

    @BeforeEach
    void setup() {
        repo = Mockito.mock(UserRepository.class);
        // TTL 60 s pour les tests qui n'expérimentent pas l'expiration.
        cache = new UserCache(repo, 60, 100, new SimpleMeterRegistry());
    }

    private static User userFixture(String id, boolean active) {
        return User.builder()
                .id(id).name("Test").email(id + "@x")
                .passwordHash("hash").role(Role.EMPLOYEE)
                .storeId("s1").active(active)
                .build();
    }

    @Test
    void firstLookupHitsRepo_subsequentLookupsHitCache() {
        when(repo.findById("u-1")).thenReturn(Optional.of(userFixture("u-1", true)));

        // 10 lookups — un seul SELECT doit partir vers le repo
        for (int i = 0; i < 10; i++) {
            Optional<User> u = cache.findById("u-1");
            assertThat(u).isPresent();
            assertThat(u.get().getId()).isEqualTo("u-1");
        }

        verify(repo, times(1)).findById(eq("u-1"));
        assertThat(cache.stats().hitCount()).isEqualTo(9);
        assertThat(cache.stats().missCount()).isEqualTo(1);
    }

    @Test
    void unknownUserIsCachedNegatively_noRepoStorm() {
        when(repo.findById("u-ghost")).thenReturn(Optional.empty());

        // Token JWT signé valide pointant vers un user supprimé entre 2 reqs :
        // le cache négatif évite N SELECT pour le même id orphelin.
        for (int i = 0; i < 20; i++) {
            assertThat(cache.findById("u-ghost")).isEmpty();
        }

        verify(repo, times(1)).findById(eq("u-ghost"));
    }

    @Test
    void invalidateForcesReFetch_picksUpFreshData() {
        // 1er état : actif
        when(repo.findById("u-2")).thenReturn(Optional.of(userFixture("u-2", true)));
        assertThat(cache.findById("u-2")).get().extracting(User::isActive).isEqualTo(true);

        // Mutation simulée : repo renvoie maintenant inactif
        when(repo.findById("u-2")).thenReturn(Optional.of(userFixture("u-2", false)));

        // Sans invalidation : cache stale (l'utilisateur reste vu comme actif)
        assertThat(cache.findById("u-2")).get().extracting(User::isActive).isEqualTo(true);

        // Invalidation explicite (cf UserController.update / delete)
        cache.invalidate("u-2");

        // Après invalidation : repo re-consulté, version fraîche
        assertThat(cache.findById("u-2")).get().extracting(User::isActive).isEqualTo(false);
        verify(repo, times(2)).findById(eq("u-2"));
    }

    @Test
    void invalidateAllResetsCacheCompletely() {
        when(repo.findById("u-a")).thenReturn(Optional.of(userFixture("u-a", true)));
        when(repo.findById("u-b")).thenReturn(Optional.of(userFixture("u-b", true)));

        cache.findById("u-a");
        cache.findById("u-b");
        cache.invalidateAll();
        cache.findById("u-a");
        cache.findById("u-b");

        verify(repo, times(2)).findById(eq("u-a"));
        verify(repo, times(2)).findById(eq("u-b"));
    }

    @Test
    void nullUserIdReturnsEmpty_noRepoInteraction() {
        assertThat(cache.findById(null)).isEmpty();
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void invalidateOnNull_noOp() {
        // Ne doit pas lever d'exception (UserController peut appeler invalidate
        // sur un id récupéré d'une lookup qui aurait pu être null en edge case)
        cache.invalidate(null);
        cache.invalidateAll();
    }

    @Test
    void ttlExpiry_evictsEntryAndForcesReFetch() throws InterruptedException {
        // TTL 1 s pour vérifier l'expiration sans ralentir la suite
        UserCache shortLived = new UserCache(repo, 1, 100, new SimpleMeterRegistry());
        when(repo.findById("u-ttl")).thenReturn(Optional.of(userFixture("u-ttl", true)));

        shortLived.findById("u-ttl");
        shortLived.findById("u-ttl");
        verify(repo, times(1)).findById(eq("u-ttl"));

        Thread.sleep(1100);

        shortLived.findById("u-ttl");
        verify(repo, times(2)).findById(eq("u-ttl"));
    }
}
