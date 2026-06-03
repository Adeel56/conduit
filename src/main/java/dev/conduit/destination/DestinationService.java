package dev.conduit.destination;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Destination CRUD, strictly org-scoped (CON-10). Every method takes the {@code orgId} resolved from
 * the authenticated principal — never from client input — and the org filter is pushed into the
 * repository so a caller can only ever see or mutate its own rows. Not-found and not-yours are
 * indistinguishable (both return an empty Optional) so the controller can answer an identical 404.
 */
@Service
public class DestinationService {

    private final DestinationRepository destinations;

    public DestinationService(DestinationRepository destinations) {
        this.destinations = destinations;
    }

    /** Create a destination for the caller's org. Rejects a non-http(s)/relative URL. */
    @Transactional
    public Destination create(UUID orgId, DestinationRequest request) {
        String url = requireValidUrl(request.url());
        return destinations.save(new Destination(orgId, requireName(request.name()), url));
    }

    /** Org-scoped, paginated list. */
    @Transactional(readOnly = true)
    public Page<Destination> list(UUID orgId, Pageable pageable) {
        return destinations.findByOrgId(orgId, pageable);
    }

    /** One destination by id, only if it is the caller's; otherwise empty (→ 404). */
    @Transactional(readOnly = true)
    public Optional<Destination> get(UUID orgId, UUID id) {
        return destinations.findByIdAndOrgId(id, orgId);
    }

    /** Update a destination the caller owns. Empty if not theirs/not found (→ 404). */
    @Transactional
    public Optional<Destination> update(UUID orgId, UUID id, DestinationRequest request) {
        String url = requireValidUrl(request.url());
        return destinations.findByIdAndOrgId(id, orgId).map(destination -> {
            destination.update(requireName(request.name()), url);
            return destination; // dirty-checked within the transaction
        });
    }

    /** Soft-deactivate a destination the caller owns. Empty if not theirs/not found (→ 404). */
    @Transactional
    public Optional<Destination> deactivate(UUID orgId, UUID id) {
        return destinations.findByIdAndOrgId(id, orgId).map(destination -> {
            destination.deactivate();
            return destination;
        });
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidDestinationException("name must not be blank");
        }
        return name.trim();
    }

    private static String requireValidUrl(String url) {
        if (!DestinationUrlValidator.isValid(url)) {
            throw new InvalidDestinationException("url must be a valid absolute http or https URL");
        }
        return url.trim();
    }
}
