package dev.conduit.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The tenant. It is the root of every ownership chain: {@code Source}, {@code ApiKey}, and
 * {@code Event} all carry an {@code org_id} that is now a real foreign key to this table (CON-12).
 *
 * <p>Deliberately minimal — just identity, a display {@link #name}, and a unique {@link #slug}.
 * User membership, roles, billing, etc. are their own later tickets (data-model.md / ADR-0008).
 *
 * <p>Schema owned by Flyway ({@code V4__organizations.sql}); {@code ddl-auto} is {@code validate},
 * so this mapping must match the migration exactly.
 */
@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private String slug;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Organization() {
        // for JPA
    }

    public Organization(String name, String slug) {
        this.name = name;
        this.slug = slug;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
