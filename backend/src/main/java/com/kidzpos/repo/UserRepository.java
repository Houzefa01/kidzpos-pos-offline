package com.kidzpos.repo;

import com.kidzpos.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    long countByRoleAndActiveTrue(com.kidzpos.domain.Role role);

    /**
     * V19-audit — Liste des users d'un store. Inclut les ADMINs (storeId=NULL)
     * pour qu'ils restent visibles sur un local store-scoped — sinon plus aucun
     * admin n'apparaîtrait dans la console users du local.
     */
    @Query("SELECT u FROM User u WHERE u.storeId = :storeId OR u.storeId IS NULL")
    List<User> findByStoreIdOrAdmin(@Param("storeId") String storeId);
}
