package vn.garage.identity.infrastructure;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserJpaRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsername(String username);
    boolean existsByUsername(String username);
    Page<UserEntity> findAllByOrderByIdAsc(Pageable pageable);
    long countByActiveTrueAndRole(String role);

    @Query(value = "select count_big(*) from dbo.users with (TABLOCKX, HOLDLOCK)", nativeQuery = true)
    long lockAndCountUsers();

    @Query(value = "select u.id, u.username, u.display_name, u.role, u.active, u.auth_version from dbo.users u where u.id = :id", nativeQuery = true)
    Object[] securityState(@Param("id") long id);
}
