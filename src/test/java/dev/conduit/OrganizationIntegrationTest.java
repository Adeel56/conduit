package dev.conduit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.conduit.apikey.ApiKeyService;
import dev.conduit.organization.Organization;
import dev.conduit.organization.OrganizationRepository;
import dev.conduit.organization.OrganizationService;
import dev.conduit.source.Source;
import dev.conduit.source.SourceService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for the organizations table (CON-12). The headline is FK integrity: with
 * {@code org_id} now a real foreign key, a tenant row can no longer reference a non-existent org —
 * the database rejects it. This strengthens tenant isolation; it never loosens it.
 */
class OrganizationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    OrganizationService organizations;
    @Autowired
    OrganizationRepository organizationRepository;
    @Autowired
    SourceService sources;
    @Autowired
    ApiKeyService apiKeys;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void organizationIsPersistedWithAUniqueSlugAndTimestamps() {
        Organization first = organizations.create("Acme Inc");
        Organization second = organizations.create("Acme Inc"); // same name on purpose

        assertThat(organizationRepository.findById(first.getId())).isPresent();
        assertThat(first.getName()).isEqualTo("Acme Inc");
        assertThat(first.getSlug()).startsWith("acme-inc-");
        assertThat(first.getCreatedAt()).isNotNull();
        assertThat(first.getUpdatedAt()).isNotNull();
        // The random suffix keeps slugs unique even when two orgs share a display name.
        assertThat(second.getSlug()).isNotEqualTo(first.getSlug());
    }

    @Test
    void foreignKeyRejectsATenantRowWithANonexistentOrg() {
        // Insert a source whose org_id points at no organization. Before CON-12 this was allowed
        // (org_id was a bare UUID); now the FK must reject it at the database level.
        UUID nonexistentOrg = UUID.randomUUID();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sources (id, org_id, name, ingest_key) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), nonexistentOrg, "orphan-source", "ingest-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_sources_org_id"); // the FK we added in V4, by name
    }

    @Test
    void tenantRowsReferencingARealOrgAreAccepted() {
        UUID orgId = organizations.create("real-org").getId();
        assertThat(organizationRepository.findById(orgId)).isPresent();

        // source + api key via the services, and an event via JDBC — all carry org_id, all satisfy
        // the FK because the org exists. No DataIntegrityViolationException is thrown.
        Source source = sources.create(orgId, "src");
        apiKeys.create(orgId, "key");
        jdbc.update("INSERT INTO events (id, org_id, source_id, payload, headers, received_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, now(), now())",
                UUID.randomUUID(), orgId, source.getId(), "x".getBytes(StandardCharsets.UTF_8), "{}");

        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE org_id = ?", Integer.class, orgId);
        assertThat(events).isEqualTo(1);
    }
}
