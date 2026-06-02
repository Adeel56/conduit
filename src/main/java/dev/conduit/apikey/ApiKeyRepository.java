package dev.conduit.apikey;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /** Narrow the lookup by the non-secret prefix; the caller then verifies the hash. */
    Optional<ApiKey> findByKeyPrefix(String keyPrefix);
}
