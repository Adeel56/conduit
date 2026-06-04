package dev.conduit.delivery;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Attempt}. The worker appends one per try ({@code save}); the read API lists a
 * delivery's attempts oldest-first for the detail view. Shared CON-13 contract — complete as-is.
 */
public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    List<Attempt> findByDeliveryIdOrderByAttemptedAtAsc(UUID deliveryId);
}
