package dev.conduit.organization;

import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates organizations. Like {@code SourceService} / {@code ApiKeyService}, this is the minimal,
 * secure seed seam the tests use — there is intentionally NO unauthenticated org-creation HTTP
 * endpoint in this ticket. Full org management (named slugs, membership, RBAC) is a later ticket.
 */
@Service
public class OrganizationService {

    private final OrganizationRepository organizations;

    public OrganizationService(OrganizationRepository organizations) {
        this.organizations = organizations;
    }

    /**
     * Create an organization with the given display name and a unique, URL-safe slug derived from it.
     * The slug carries a short random suffix so repeated names (common across tests sharing one DB)
     * never collide on the unique constraint; a real org-management ticket would let the user pick it.
     */
    @Transactional
    public Organization create(String name) {
        return organizations.save(new Organization(name, slugify(name)));
    }

    private static String slugify(String name) {
        String base = (name == null ? "" : name).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isEmpty()) {
            base = "org";
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
